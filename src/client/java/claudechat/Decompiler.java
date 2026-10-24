package claudechat;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Decompiles mod and game code with Vineflower, in its own JVM so a big decompile can't run the game out of memory.
 * Several decompiles may run at once (the first-launch Minecraft build and a mod a question names): they share only
 * the Vineflower jar, which is written once per session, and keep their other files in their own work folders.
 */
final class Decompiler {
	private static final int TIMEOUT_MINUTES = 30;

	private final Path jar;
	private boolean jarWritten;

	/** {@code root}: the folder the Vineflower jar is written to. */
	Decompiler(Path root) {
		this.jar = root.resolve("vineflower.jar");
	}

	/**
	 * Writes {@code container}'s classes to {@code out} as Java source. {@code work} must be a folder only this call
	 * uses; on failure the decompiler's log is left there and named in the exception.
	 */
	void decompile(ModContainer container, Path out, Path work) throws IOException, InterruptedException {
		Path vineflower = vineflowerJar();
		Path libraries = work.resolve("libraries.cfg");
		Path log = work.resolve("decompile.log");

		for (Path input : inputs(container, work.resolve("classes"))) {
			// A config file, because the library list would pass Windows' command-line length limit.
			Files.write(libraries, libraryOptions(input), StandardCharsets.UTF_8);

			int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
			List<String> command = List.of(javaExecutable(), "-Xmx3G", "-jar", vineflower.toString(),
				"--folder", "--skip-extra-files=1", "--thread-count=" + threads, "--log-level=warn",
				"--cfg=" + libraries, input.toString(), out.toString());

			run(command, log, input);
		}
	}

	private static void run(List<String> command, Path log, Path input) throws IOException, InterruptedException {
		Process process = new ProcessBuilder(command)
			.redirectErrorStream(true)
			.redirectOutput(Redirect.appendTo(log.toFile()))
			.start();

		if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
			process.destroyForcibly();
			throw new IOException("decompiling " + input.getFileName() + " took over " + TIMEOUT_MINUTES + " minutes");
		}

		if (process.exitValue() != 0) {
			throw new IOException("decompiler exited with code " + process.exitValue() + " (see " + log + ")");
		}
	}

	/**
	 * The bundled Vineflower jar, written on first use. Windows can't replace a jar another JVM is running from, so it's
	 * written once per session, under this object's lock, and not at all when the file on disk is already identical.
	 */
	private synchronized Path vineflowerJar() throws IOException {
		if (jarWritten) {
			return jar;
		}

		byte[] bundled;

		try (InputStream in = Decompiler.class.getResourceAsStream("/claudechat/vineflower.jar")) {
			if (in == null) {
				throw new IOException("vineflower.jar is missing from the mod");
			}

			bundled = in.readAllBytes();
		}

		boolean current = Files.isRegularFile(jar) && Arrays.equals(Files.readAllBytes(jar), bundled);

		if (!current) {
			Files.createDirectories(jar.getParent());
			Path temporary = Files.createTempFile(jar.getParent(), "vineflower", ".tmp");

			try {
				Files.write(temporary, bundled);
				Files.move(temporary, jar, StandardCopyOption.REPLACE_EXISTING);
			} finally {
				Files.deleteIfExists(temporary);
			}
		}

		jarWritten = true;
		return jar;
	}

	/** "-e=" options for the game's own classpath and Minecraft's jars, so the decompiler can resolve their types. */
	private static List<String> libraryOptions(Path input) {
		Set<String> libraries = new LinkedHashSet<>();

		for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
			if (entry.endsWith(".jar") && Files.isRegularFile(Path.of(entry))) {
				libraries.add(Path.of(entry).toAbsolutePath().toString());
			}
		}

		FabricLoader.getInstance().getModContainer("minecraft")
			.ifPresent(minecraft -> jarsOf(minecraft).forEach(jar -> libraries.add(jar.toAbsolutePath().toString())));
		libraries.remove(input.toAbsolutePath().toString());

		return libraries.stream().map(library -> "-e=" + library).toList();
	}

	/** A jar on disk when there is one; otherwise (nested jars, dev folders) the classes copied out to a folder. */
	private static List<Path> inputs(ModContainer container, Path classes) throws IOException {
		List<Path> jars = jarsOf(container);

		if (!jars.isEmpty()) {
			return jars;
		}

		Path folder = classes.resolve(KnowledgeFiles.folderName(container.getMetadata().getId()));

		for (Path rootPath : container.getRootPaths()) {
			KnowledgeFiles.copyTree(rootPath, folder, Decompiler::isClassFile);
		}

		return Files.isDirectory(folder) ? List.of(folder) : List.of();
	}

	static boolean isClassFile(Path file) {
		return file.toString().endsWith(".class");
	}

	private static List<Path> jarsOf(ModContainer mod) {
		ModOrigin origin = mod.getOrigin();

		if (origin.getKind() != ModOrigin.Kind.PATH) {
			return List.of();
		}

		List<Path> paths = origin.getPaths();
		boolean allJars = paths.stream().allMatch(path -> path.getFileName().toString().endsWith(".jar") && Files.isRegularFile(path));

		return allJars ? paths : List.of();
	}

	private static String javaExecutable() {
		return ProcessHandle.current().info().command().orElse("java");
	}
}
