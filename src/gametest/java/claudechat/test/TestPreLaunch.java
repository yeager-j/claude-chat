package claudechat.test;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Sets up the game test's config file before Claude Chat reads it, through the same file a player edits: every question
 * runs {@link FakeClaude}, with a timeout to suit the run ({@link TestOptions#questionTimeoutSeconds}), in Read only with
 * cheats off and no include or exclude list left by an earlier run. It also parses the {@code CLAUDECHAT_*} variables,
 * so a typo fails before the game starts.
 * <p>
 * The test mod's decompiled code is deleted too, so the mod step finds a data-only mod to decompile on demand.
 */
public final class TestPreLaunch implements PreLaunchEntrypoint {
	/** Set by Fabric for client game test runs; any other run that loads this mod keeps its config. */
	private static final String GAME_TEST_PROPERTY = "fabric.client.gametest";

	@Override
	public void onPreLaunch() {
		if (System.getProperty(GAME_TEST_PROPERTY) == null) {
			return;
		}

		TestOptions options = TestOptions.parse(System.getenv());
		Path gameDir = FabricLoader.getInstance().getGameDir();
		Path configDir = FabricLoader.getInstance().getConfigDir();
		try {
			Path fake = FakeClaude.folder(gameDir);
			deleteTree(fake);
			Path wrapper = FakeClaude.writeWrapper(fake);
			writeConfig(configDir.resolve("claudechat.properties"), wrapper, options.questionTimeoutSeconds());
			// KnowledgeBase's layout: mods/<mod id>/<version>.
			deleteTree(configDir.resolve("claudechat/knowledge/mods/" + ModStep.MOD_ID));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void writeConfig(Path file, Path claude, int timeoutSeconds) throws IOException {
		Properties config = new Properties();
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				config.load(reader);
			}
		}

		config.setProperty("claudePath", claude.toString());
		config.setProperty("timeoutSeconds", String.valueOf(timeoutSeconds));
		config.setProperty("permissions", "read_only");
		config.setProperty("cheats", "false");
		config.setProperty("knowledgeInclude", "");
		config.setProperty("knowledgeExclude", "");
		Files.createDirectories(file.getParent());
		try (Writer writer = Files.newBufferedWriter(file)) {
			config.store(writer, "Claude Chat settings, set up by the game test");
		}
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}

		try (Stream<Path> paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		}
	}
}
