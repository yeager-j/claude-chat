package claudechat;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.ModOrigin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A local, searchable copy of the game in .minecraft/config/claudechat/knowledge, rebuilt only when a version changes.
 * Claude Code runs with that folder as its working directory.
 * <ul>
 * <li>Every source (Minecraft and each mod) gets its data pack JSON (recipes, loot tables, tags, worldgen) and English
 *     names. That's cheap, and lets recipe grids and searches cover modded content.</li>
 * <li>Minecraft is always decompiled. Mods start data-only: which ones are worth asking about can't be inferred, so a
 *     mod is decompiled when a question names it, when Claude asks for it, or when the player runs
 *     /claude knowledge include. /claude knowledge exclude removes a mod's code and stops it being decompiled.</li>
 * </ul>
 */
public class KnowledgeBase {
	public enum Tier { CODE, DATA }

	public enum State { MISSING, QUEUED, BUILDING, DATA_READY, READY, FAILED }

	/**
	 * Minecraft itself, or one top-level mod. {@code codeContainers} are what gets decompiled: the mod plus any nested
	 * jars that aren't libraries (some mods keep their real code in a nested "common" jar). {@code dataContainers}
	 * also include nested libraries. {@code dir} is relative to the knowledge root.
	 */
	public record Source(String id, String label, String dir, Tier autoTier, String reason,
		List<ModContainer> codeContainers, List<ModContainer> dataContainers, List<String> mentionNames) {
	}

	private static final String CODE_MARKER = ".complete";
	private static final String DATA_MARKER = ".data-complete";
	private static final Pattern LANG_FILE = Pattern.compile("assets/([^/]+)/lang/en_us\\.json");
	private static final Set<String> GENERIC_WORDS = Set.of("fabric", "mod", "mods", "api", "lib", "library", "the", "and", "for");

	private final ClaudeConfig config;
	private final Path root = ClaudeConfig.dir().resolve("knowledge");
	private final List<Source> sources;
	private final Map<String, State> states = new ConcurrentHashMap<>();
	private final AtomicInteger generation = new AtomicInteger();
	private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Claude Chat knowledge builder");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});

	public KnowledgeBase(ClaudeConfig config) {
		this.config = config;
		this.sources = findSources();
		for (Source source : sources) {
			Path dir = root.resolve(source.dir());
			states.put(source.id(), Files.exists(dir.resolve(CODE_MARKER)) ? State.READY
				: Files.exists(dir.resolve(DATA_MARKER)) ? State.DATA_READY : State.MISSING);
		}
	}

	public Path root() {
		return root;
	}

	public List<Source> sources() {
		return sources;
	}

	public Optional<Source> source(String id) {
		return sources.stream().filter(source -> source.id().equals(id)).findFirst();
	}

	public State state(Source source) {
		return states.getOrDefault(source.id(), State.MISSING);
	}

	/** Bumped whenever a build finishes, so caches of the data (e.g. the recipe index) know to refresh. */
	public int generation() {
		return generation.get();
	}

	/** The classification after the player's include/exclude overrides. */
	public Tier tier(Source source) {
		if (config.knowledgeInclude.contains(source.id())) {
			return Tier.CODE;
		}
		if (config.knowledgeExclude.contains(source.id())) {
			return Tier.DATA;
		}
		return source.autoTier();
	}

	public String reason(Source source) {
		if (config.knowledgeInclude.contains(source.id())) {
			return "included by you";
		}
		if (config.knowledgeExclude.contains(source.id())) {
			return "excluded by you";
		}
		return source.reason();
	}

	/** Has code, isn't decompiled yet, and the player hasn't excluded it. */
	public boolean canDecompile(Source source) {
		return !hasCode(source) && !source.codeContainers().isEmpty() && !config.knowledgeExclude.contains(source.id());
	}

	public boolean hasCode(Source source) {
		return state(source) == State.READY;
	}

	public boolean hasData(Source source) {
		return state(source) == State.READY || state(source) == State.DATA_READY;
	}

	/** True while anything is queued or building. */
	public boolean isBusy() {
		return pendingPacks.get() > 0 || states.values().stream().anyMatch(state -> state == State.QUEUED || state == State.BUILDING);
	}

	/** Queue whatever each source still needs: code+data for CODE sources, data for the rest. Minecraft goes first. */
	public void buildMissing(Consumer<String> notify) {
		List<Source> order = new ArrayList<>(sources);
		// Data-only builds take a second each, so do them before the slower decompiles.
		order.sort(Comparator.comparing((Source source) -> !source.id().equals("minecraft"))
			.thenComparing(source -> tier(source) == Tier.CODE));
		for (Source source : order) {
			boolean wantCode = tier(source) == Tier.CODE;
			State state = state(source);
			boolean needed = wantCode ? state != State.READY : state != State.READY && state != State.DATA_READY;
			if (needed && state != State.QUEUED && state != State.BUILDING) {
				states.put(source.id(), State.QUEUED);
				executor.execute(() -> buildIfNeeded(source, wantCode, notify));
			}
		}
	}

	/** Throw away everything for the current game and mods, and build it again. */
	public void rebuildAll(Consumer<String> notify) {
		for (Source source : sources) {
			State state = state(source);
			if (state != State.QUEUED && state != State.BUILDING) {
				deleteMarkers(source);
				states.put(source.id(), State.MISSING);
			}
		}
		buildMissing(notify);
	}

	/** Decompile a mod from now on (and right away). */
	public void include(Source source, Consumer<String> notify) {
		config.knowledgeExclude.remove(source.id());
		config.knowledgeInclude.add(source.id());
		config.save();
		buildMissing(notify);
	}

	/** Stop decompiling a mod and delete its decompiled code; its data stays. */
	public void exclude(Source source) {
		// Recorded even though mods default to data-only, so neither a mention nor Claude's request decompiles it again.
		config.knowledgeInclude.remove(source.id());
		config.knowledgeExclude.add(source.id());
		config.save();
		if (state(source) == State.READY) {
			try {
				Path dir = root.resolve(source.dir());
				deleteRecursively(dir.resolve("src"));
				Files.deleteIfExists(dir.resolve(CODE_MARKER));
				Files.writeString(dir.resolve(DATA_MARKER), Instant.now().toString());
				states.put(source.id(), State.DATA_READY);
			} catch (IOException e) {
				ClaudeChatClient.LOGGER.warn("Could not remove decompiled code for {}", source.label(), e);
			}
		}
	}

	/**
	 * Data-only mods the question (or held item, etc.) refers to, which haven't been decompiled yet and that the player
	 * hasn't excluded. Matched on mod id and name as whole words, e.g. "litematica", "vein miner", "universal-graves".
	 */
	public List<Source> mentionedWithoutCode(String text) {
		String lower = " " + text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ") + " ";
		String compact = lower.replace(" ", "");
		List<Source> found = new ArrayList<>();
		for (Source source : sources) {
			if (!canDecompile(source)) {
				continue;
			}
			for (String name : source.mentionNames()) {
				boolean word = lower.contains(" " + name + " ");
				boolean joined = name.length() >= 6 && compact.contains(name.replace(" ", ""));
				if (word || joined) {
					found.add(source);
					break;
				}
			}
		}
		return found;
	}

	/** Decompile one source right now, on the calling thread (waits if the background builder is already on it). */
	public void buildCodeNow(Source source, Consumer<String> notify) {
		buildIfNeeded(source, true, notify);
	}

	/** One build per source at a time; whoever gets the lock second finds the work done and skips it. */
	private void buildIfNeeded(Source source, boolean withCode, Consumer<String> notify) {
		synchronized (source) {
			if (withCode ? hasCode(source) : hasData(source)) {
				return;
			}
			build(source, withCode, notify);
		}
	}

	/**
	 * The data/ folders that exist, in override order: the world's data packs (last enabled first), then mods, then
	 * Minecraft. A file found earlier in this list is the one the game actually uses.
	 */
	public List<Path> dataRoots() {
		List<Path> roots = new ArrayList<>();
		for (WorldPack pack : worldPacks) {
			if (readyPacks.contains(pack.dir())) {
				roots.add(root.resolve(pack.dir()).resolve("data"));
			}
		}
		List<Source> ordered = new ArrayList<>(sources);
		ordered.sort(Comparator.comparing(source -> source.id().equals("minecraft")));
		for (Source source : ordered) {
			if (hasData(source)) {
				roots.add(root.resolve(source.dir()).resolve("data"));
			}
		}
		return roots.stream().filter(Files::isDirectory).toList();
	}

	/** A data pack from the current world's datapacks folder, as a folder or a zip. */
	public record WorldPack(String name, Path location, String dir) {
	}

	private volatile List<WorldPack> worldPacks = List.of();
	private volatile String worldName = "";
	private final Set<String> readyPacks = ConcurrentHashMap.newKeySet();
	private final AtomicInteger pendingPacks = new AtomicInteger();

	/**
	 * The single-player world's enabled data packs changed (world opened, or /reload). {@code selectedIds} is the
	 * server's enabled pack list in load order; only "file/..." packs come from the world's datapacks folder.
	 * Each pack is extracted once per version (keyed by a fingerprint of its files).
	 */
	public void setWorldPacks(String world, Path datapackDir, List<String> selectedIds) {
		List<WorldPack> packs = new ArrayList<>();
		for (String id : selectedIds) {
			if (!id.startsWith("file/")) {
				continue;
			}
			String name = id.substring("file/".length());
			Path location = datapackDir.resolve(name).normalize();
			if (!location.startsWith(datapackDir) || !Files.exists(location)) {
				continue;
			}
			String fingerprint = Integer.toHexString((name + fingerprint(location)).hashCode());
			packs.add(new WorldPack(name, location, "datapacks/" + safe(name.replaceFirst("\\.zip$", "")) + "-" + fingerprint));
		}
		java.util.Collections.reverse(packs); // highest priority (loaded last) first
		worldName = world;
		worldPacks = List.copyOf(packs);
		generation.incrementAndGet();

		for (WorldPack pack : packs) {
			if (Files.exists(root.resolve(pack.dir()).resolve(DATA_MARKER))) {
				readyPacks.add(pack.dir());
			} else {
				pendingPacks.incrementAndGet();
				executor.execute(() -> {
					try {
						extractPack(pack);
					} finally {
						pendingPacks.decrementAndGet();
					}
				});
			}
		}
	}

	public void clearWorldPacks() {
		worldPacks = List.of();
		worldName = "";
		generation.incrementAndGet();
	}

	private void extractPack(WorldPack pack) {
		Path dir = root.resolve(pack.dir());
		Path partial = dir.resolveSibling(dir.getFileName() + ".partial");
		try {
			deleteRecursively(partial);
			Files.createDirectories(partial);
			if (Files.isDirectory(pack.location())) {
				copyData(pack.location().resolve("data"), partial.resolve("data"));
			} else {
				try (java.nio.file.FileSystem zip = java.nio.file.FileSystems.newFileSystem(pack.location())) {
					copyData(zip.getPath("/data"), partial.resolve("data"));
				}
			}
			Files.writeString(partial.resolve(DATA_MARKER), Instant.now().toString());
			deleteRecursively(dir);
			Files.createDirectories(dir.getParent());
			Files.move(partial, dir, StandardCopyOption.ATOMIC_MOVE);
			readyPacks.add(pack.dir());
			generation.incrementAndGet();
			ClaudeChatClient.LOGGER.info("Claude knowledge base: extracted world data pack {}", pack.name());
		} catch (IOException | RuntimeException e) {
			ClaudeChatClient.LOGGER.warn("Could not read world data pack {}", pack.name(), e);
		}
	}

	private static void copyData(Path data, Path target) throws IOException {
		if (!Files.isDirectory(data)) {
			return;
		}
		try (Stream<Path> files = Files.walk(data)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				Path out = target.resolve(data.relativize(file).toString()).normalize();
				if (out.startsWith(target)) {
					Files.createDirectories(out.getParent());
					Files.copy(file, out, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	/** Changes whenever a pack's files change: size and time for a zip; file count and newest time for a folder. */
	private static String fingerprint(Path location) {
		try {
			if (!Files.isDirectory(location)) {
				return Files.size(location) + ":" + Files.getLastModifiedTime(location).toMillis();
			}
			try (Stream<Path> files = Files.walk(location)) {
				long[] stats = {0, 0};
				files.filter(Files::isRegularFile).forEach(file -> {
					stats[0]++;
					try {
						stats[1] = Math.max(stats[1], Files.getLastModifiedTime(file).toMillis());
					} catch (IOException ignored) {
					}
				});
				return stats[0] + ":" + stats[1];
			}
		} catch (IOException e) {
			return "unknown";
		}
	}

	/** A file under data/, e.g. "minecraft/recipe/crafter.json", from whichever source has it. */
	public Optional<Path> findDataFile(String relative) {
		for (Path data : dataRoots()) {
			Path file = data.resolve(relative).normalize();
			if (file.startsWith(data) && Files.isRegularFile(file)) {
				return Optional.of(file);
			}
		}
		return Optional.empty();
	}

	/** The part of the system prompt that tells Claude what reference files exist and how to use them. */
	public String describeForPrompt() {
		StringBuilder sb = new StringBuilder();
		sb.append("Reference files for the player's exact game, in the current directory (read-only; search with Grep/Glob, then Read).\n");
		sb.append("src = decompiled Java, data = data pack JSON (recipe/, loot_table/, tags/, worldgen/, ...), lang = English names.\n");
		for (Source source : sources) {
			sb.append("- ").append(source.label()).append(" [").append(source.id()).append("]: ");
			if (hasCode(source)) {
				sb.append(source.dir()).append("/{src,data,lang}");
			} else if (hasData(source) && canDecompile(source)) {
				sb.append(source.dir()).append("/{data,lang}; code not decompiled yet, request it with [[decompile:")
					.append(source.id()).append("]]");
			} else if (hasData(source)) {
				sb.append(source.dir()).append("/{data,lang}; no code available (").append(reason(source)).append(")");
			} else {
				sb.append("not available yet (still being prepared)");
			}
			sb.append('\n');
		}
		List<WorldPack> packs = worldPacks;
		if (!packs.isEmpty()) {
			sb.append("Data packs enabled in this world (\"").append(worldName).append("\"), highest priority first. A file here")
				.append(" replaces the file at the same path in mods and Minecraft (e.g. data/minecraft/loot_table/...), so check these first:\n");
			for (WorldPack pack : packs) {
				sb.append("- ").append(pack.name()).append(": ")
					.append(readyPacks.contains(pack.dir()) ? pack.dir() + "/data" : "still being read").append('\n');
			}
		}
		sb.append("""
			How to use them:
			- Recipes, drops, tags, structures, worldgen: read the JSON under data/ first.
			- Mechanics (spawning, AI, redstone timing, formulas, what a mod changes): grep the relevant src/ and read the code. Don't guess.
			- Mods often change vanilla behavior through mixins: grep a mod's src/ for "@Mixin" to see which game classes it modifies.
			- Map display names to ids with lang/*.json (e.g. "block.minecraft.crafter": "Crafter").
			- Ignore any other folders here; they are for other game or mod versions.
			""");
		return sb.toString();
	}

	private void build(Source source, boolean withCode, Consumer<String> notify) {
		states.put(source.id(), State.BUILDING);
		long start = System.nanoTime();
		Path dir = root.resolve(source.dir());
		Path partial = dir.resolveSibling(dir.getFileName() + ".partial");
		try {
			ClaudeChatClient.LOGGER.info("Building Claude knowledge base for {} ({})", source.label(), withCode ? "code + data" : "data");
			deleteRecursively(partial);
			Files.createDirectories(partial);

			for (ModContainer container : source.dataContainers()) {
				extractData(container, partial);
			}
			if (withCode) {
				Path src = partial.resolve("src");
				Files.createDirectories(src);
				for (ModContainer container : source.codeContainers()) {
					decompile(container, src, partial.resolve("classes-tmp"));
				}
				deleteRecursively(partial.resolve("classes-tmp"));
				pruneEmptyDirectories(src);
			}
			Files.writeString(partial.resolve(withCode ? CODE_MARKER : DATA_MARKER), Instant.now().toString());

			deleteRecursively(dir);
			Files.createDirectories(dir.getParent());
			Files.move(partial, dir, StandardCopyOption.ATOMIC_MOVE);
			states.put(source.id(), withCode ? State.READY : State.DATA_READY);
			generation.incrementAndGet();

			long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
			ClaudeChatClient.LOGGER.info("Claude knowledge base for {} ready in {}s", source.label(), seconds);
			if (withCode) {
				notify.accept("Knowledge base ready: " + source.label() + " (" + seconds + "s).");
			}
		} catch (Exception e) {
			states.put(source.id(), State.FAILED);
			ClaudeChatClient.LOGGER.warn("Could not build Claude knowledge base for {}", source.label(), e);
			notify.accept("Couldn't build the knowledge base for " + source.label() + ": " + e.getMessage());
		}
	}

	/** Runs Vineflower in its own JVM so a big decompile can't run the game out of memory. */
	private void decompile(ModContainer container, Path out, Path tmp) throws IOException, InterruptedException {
		Path vineflower = extractVineflower();
		for (Path input : decompileInputs(container, tmp)) {
			// Libraries on the classpath let the decompiler resolve types; a config file avoids Windows' command-line length limit.
			Set<String> libraries = new LinkedHashSet<>();
			for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
				if (entry.endsWith(".jar") && Files.isRegularFile(Path.of(entry))) {
					libraries.add(Path.of(entry).toAbsolutePath().toString());
				}
			}
			FabricLoader.getInstance().getModContainer("minecraft")
				.ifPresent(mc -> jarsOf(mc).forEach(jar -> libraries.add(jar.toAbsolutePath().toString())));
			libraries.remove(input.toAbsolutePath().toString());
			Path cfg = root.resolve("vineflower-libraries.cfg");
			Files.write(cfg, libraries.stream().map(lib -> "-e=" + lib).toList(), StandardCharsets.UTF_8);

			int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
			List<String> command = new ArrayList<>(List.of(javaExecutable(), "-Xmx3G", "-jar", vineflower.toString(),
				"--folder", "--skip-extra-files=1", "--thread-count=" + threads, "--log-level=warn",
				"--cfg=" + cfg, input.toString(), out.toString()));

			Path log = root.resolve("build.log");
			Files.writeString(log, "\n=== " + Instant.now() + " " + input + "\n", StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			Process process = new ProcessBuilder(command)
				.redirectErrorStream(true)
				.redirectOutput(Redirect.appendTo(log.toFile()))
				.start();
			if (!process.waitFor(30, TimeUnit.MINUTES)) {
				process.destroyForcibly();
				throw new IOException("decompiling " + input.getFileName() + " took over 30 minutes");
			}
			if (process.exitValue() != 0) {
				throw new IOException("decompiler exited with code " + process.exitValue() + " (see " + log + ")");
			}
		}
	}

	/** A jar on disk when there is one; otherwise (nested jars, dev folders) the classes copied out to a folder. */
	private static List<Path> decompileInputs(ModContainer container, Path tmp) throws IOException {
		List<Path> jars = jarsOf(container);
		if (!jars.isEmpty()) {
			return jars;
		}
		Path folder = tmp.resolve(safe(container.getMetadata().getId()));
		for (Path rootPath : container.getRootPaths()) {
			try (Stream<Path> files = Files.walk(rootPath)) {
				for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
					Path target = folder.resolve(rootPath.relativize(file).toString()).normalize();
					if (target.startsWith(folder)) {
						Files.createDirectories(target.getParent());
						Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
					}
				}
			}
		}
		return Files.isDirectory(folder) ? List.of(folder) : List.of();
	}

	/** Copies data/ and English translations out of a mod, through the loader's view of its files (works for nested jars). */
	private static void extractData(ModContainer container, Path out) throws IOException {
		Path dataDir = out.resolve("data").normalize();
		Path langDir = out.resolve("lang").normalize();
		for (Path rootPath : container.getRootPaths()) {
			Path data = rootPath.resolve("data");
			if (Files.isDirectory(data)) {
				try (Stream<Path> files = Files.walk(data)) {
					for (Path file : files.filter(Files::isRegularFile).toList()) {
						Path target = dataDir.resolve(data.relativize(file).toString()).normalize();
						if (target.startsWith(dataDir)) {
							Files.createDirectories(target.getParent());
							Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
						}
					}
				}
			}
			Path assets = rootPath.resolve("assets");
			if (Files.isDirectory(assets)) {
				try (Stream<Path> namespaces = Files.list(assets)) {
					for (Path ns : namespaces.toList()) {
						Path lang = ns.resolve("lang").resolve("en_us.json");
						String name = ns.getFileName().toString().replace("/", "");
						if (Files.isRegularFile(lang) && LANG_FILE.matcher("assets/" + name + "/lang/en_us.json").matches()) {
							Path target = langDir.resolve(name + ".json").normalize();
							if (target.startsWith(langDir)) {
								Files.createDirectories(langDir);
								Files.copy(lang, target, StandardCopyOption.REPLACE_EXISTING);
							}
						}
					}
				}
			}
		}
	}

	private Path extractVineflower() throws IOException {
		Path target = root.resolve("vineflower.jar");
		Files.createDirectories(root);
		try (InputStream in = KnowledgeBase.class.getResourceAsStream("/claudechat/vineflower.jar")) {
			if (in == null) {
				throw new IOException("vineflower.jar is missing from the mod");
			}
			Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
		}
		return target;
	}

	private static String javaExecutable() {
		return ProcessHandle.current().info().command().orElse("java");
	}

	private static List<Source> findSources() {
		List<Source> found = new ArrayList<>();
		FabricLoader.getInstance().getModContainer("minecraft").ifPresent(mc -> {
			String version = mc.getMetadata().getVersion().getFriendlyString();
			found.add(new Source("minecraft", "Minecraft " + version, "minecraft-" + safe(version), Tier.CODE, "the game",
				List.of(mc), List.of(mc), List.of()));
		});

		// Mods are never decompiled up front: whether one is worth asking about can't be inferred from its metadata.
		// Each starts data-only and is decompiled when a question needs it or the player includes it.
		for (ModContainer mod : GameContext.userMods()) {
			ModMetadata meta = mod.getMetadata();
			List<ModContainer> code = new ArrayList<>();
			List<ModContainer> data = new ArrayList<>();
			code.add(mod);
			data.add(mod);
			collectNested(mod.getContainedMods(), code, data);

			boolean hasClasses = countClasses(code) > 0;
			String reason = hasClasses ? "decompiled when needed" : "no code (data and resources only)";
			String version = meta.getVersion().getFriendlyString();
			found.add(new Source(meta.getId(), meta.getName() + " " + version, "mods/" + safe(meta.getId() + "-" + version),
				Tier.DATA, reason, hasClasses ? List.copyOf(code) : List.of(), List.copyOf(data), mentionNames(meta)));
		}
		return found;
	}

	/** Nested jars: libraries are skipped for code (polymer, placeholder-api...), but everything counts for data. */
	private static void collectNested(Collection<ModContainer> nested, List<ModContainer> code, List<ModContainer> data) {
		for (ModContainer child : nested) {
			data.add(child);
			if (!isLibrary(child.getMetadata()) && !child.getMetadata().getId().startsWith("fabric-")) {
				code.add(child);
			}
			collectNested(child.getContainedMods(), code, data);
		}
	}

	/** Mod Menu's "library" badge, which most library mods set. */
	private static boolean isLibrary(ModMetadata meta) {
		CustomValue modmenu = meta.getCustomValue("modmenu");
		if (modmenu == null || modmenu.getType() != CustomValue.CvType.OBJECT) {
			return false;
		}
		CustomValue badges = modmenu.getAsObject().get("badges");
		if (badges == null || badges.getType() != CustomValue.CvType.ARRAY) {
			return false;
		}
		for (CustomValue badge : badges.getAsArray()) {
			if (badge.getType() == CustomValue.CvType.STRING && badge.getAsString().equals("library")) {
				return true;
			}
		}
		return false;
	}

	private static int countClasses(List<ModContainer> containers) {
		int count = 0;
		for (ModContainer container : containers) {
			for (Path rootPath : container.getRootPaths()) {
				try (Stream<Path> files = Files.walk(rootPath)) {
					count += (int) files.filter(f -> f.toString().endsWith(".class")).count();
				} catch (IOException e) {
					ClaudeChatClient.LOGGER.debug("Could not scan {}", rootPath, e);
				}
			}
		}
		return count;
	}

	/** Ways a player might refer to a mod: its id and name, with punctuation as spaces. */
	private static List<String> mentionNames(ModMetadata meta) {
		Set<String> names = new LinkedHashSet<>();
		for (String raw : List.of(meta.getId(), meta.getName())) {
			String name = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
			if (name.length() >= 4 && !GENERIC_WORDS.contains(name)) {
				names.add(name);
			}
		}
		return List.copyOf(names);
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

	private void deleteMarkers(Source source) {
		try {
			Path dir = root.resolve(source.dir());
			Files.deleteIfExists(dir.resolve(CODE_MARKER));
			Files.deleteIfExists(dir.resolve(DATA_MARKER));
		} catch (IOException e) {
			ClaudeChatClient.LOGGER.warn("Could not reset {}", source.dir(), e);
		}
	}

	private static String safe(String name) {
		return name.replaceAll("[^A-Za-z0-9._+-]", "_");
	}

	private static void pruneEmptyDirectories(Path dir) throws IOException {
		try (Stream<Path> paths = Files.walk(dir)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				if (!path.equals(dir) && Files.isDirectory(path)) {
					try (Stream<Path> children = Files.list(path)) {
						if (children.findAny().isEmpty()) {
							Files.delete(path);
						}
					}
				}
			}
		}
	}

	private static void deleteRecursively(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(dir)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		}
	}
}
