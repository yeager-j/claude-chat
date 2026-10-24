package claudechat.test;

import claudechat.ClaudeChatClient;
import claudechat.ClaudeConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModOrigin;
import org.spongepowered.asm.mixin.MixinEnvironment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static claudechat.test.Game.LOGGER;
import static claudechat.test.Game.check;

/**
 * Plays through the mod in a real client and saves screenshots to build/run/clientGameTest/screenshots. First it
 * checks what every step relies on: the loaded Claude Chat is this build, every mixin applies, the MCP server's tools
 * without a world open, and Minecraft's decompiled code. Then it runs the steps {@link Step} declares and the
 * {@code CLAUDECHAT_*} variables select ({@link TestOptions}), each in a fresh world of its own.
 */
public class ClaudeChatClientGameTest implements FabricClientGameTest {
	/** Decompiling Minecraft from scratch takes minutes. */
	private static final int DECOMPILE_TIMEOUT_SECONDS = 900;

	@Override
	public void runTest(ClientGameTestContext context) {
		TestOptions options = TestOptions.parse(System.getenv());
		LOGGER.info("steps: {}", options.describe());
		checkDevBuild();
		// Applies every mixin of every loaded config now, so a target that moved fails here rather than when first used.
		MixinEnvironment.getCurrentEnvironment().audit();

		Game game = new Game(context, options);
		checkWithoutWorld(game);
		for (Step step : Step.values()) {
			if (options.steps().contains(step)) {
				run(game, step);
			}
		}
	}

	private static void run(Game game, Step step) {
		LOGGER.info("step {}", step);
		game.openWorld(step.world);
		// With Xaero loaded the client hangs while closing the world, before Fabric prints the failure, so log it here.
		try {
			step.body.run(game);
		} catch (RuntimeException | Error e) {
			LOGGER.error("step {} failed", step, e);
			throw e;
		} catch (Exception e) {
			LOGGER.error("step {} failed", step, e);
			throw new IllegalStateException(e);
		} finally {
			game.leaveWorld();
		}
	}

	/**
	 * The loaded claudechat is this build's classes, not an installed release jar (one in -PextraMods silently replaced
	 * the code under test once). The game folder is build/run/clientGameTest, so this build is two folders up.
	 */
	private static void checkDevBuild() {
		Path gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
		Path build = gameDir.getParent().getParent();
		check(build.getFileName().toString().equals("build"), "the game folder isn't build/run/clientGameTest: " + gameDir);
		ModOrigin origin = FabricLoader.getInstance().getModContainer(ClaudeChatClient.MOD_ID).orElseThrow().getOrigin();
		List<Path> paths = origin.getKind() == ModOrigin.Kind.PATH ? origin.getPaths() : List.of();
		check(!paths.isEmpty() && paths.stream().allMatch(path -> path.toAbsolutePath().normalize().startsWith(build)),
			"claudechat isn't loaded from this build (" + build + "): " + origin);
	}

	/**
	 * The MCP server with no world open, as on a multiplayer server: only decompile_mod and approve; availability and
	 * arguments are checked on every call, and an unknown tool is a JSON-RPC error. Then waits, through decompile_mod,
	 * for Minecraft's code, which the knowledge base builds in the background.
	 */
	private static void checkWithoutWorld(Game game) {
		List<String> tools = game.toolNames();
		LOGGER.info("MCP tools without a world: {}", tools);
		check(tools.contains("decompile_mod") && tools.contains("approve"), "tools missing without a world: " + tools);
		check(Stream.of("find_structures", "look", "run_command").noneMatch(tools::contains), "world tools offered without a world: " + tools);

		check(game.toolError("decompile_mod", "{\"mod\": \"no_such_mod\"}").contains("No mod with id \"no_such_mod\""),
			"an unknown mod id didn't fail by name");
		check(game.toolError("find_structures", "{\"structure\": \"village\"}").contains("No single-player world"),
			"a world tool ran without a world");
		check(game.toolError("decompile_mod", "{}").contains("mod is required"), "a missing required argument was accepted");
		McpClient.JsonRpcError unknown = null;
		try {
			game.call("no_such_tool", "{}");
		} catch (McpClient.JsonRpcError e) {
			unknown = e;
		}
		check(unknown != null && unknown.code == -32602, "an unknown tool isn't an invalid-params error: " + unknown);

		JsonObject minecraft = game.offThread(() -> game.mcp().callTool("decompile_mod", parse("{\"mod\": \"minecraft\"}")),
			DECOMPILE_TIMEOUT_SECONDS).object();
		LOGGER.info("decompile_mod minecraft -> {}", minecraft);
		// The tool's paths are relative to Claude's working directory, the knowledge base's root.
		Path source = ClaudeConfig.dir().resolve("knowledge").resolve(minecraft.get("source").getAsString());
		check(List.of("decompiled", "already decompiled").contains(minecraft.get("status").getAsString()) && hasJavaFiles(source),
			"Minecraft's code isn't where decompile_mod says: " + minecraft);
	}

	private static boolean hasJavaFiles(Path folder) {
		if (!Files.isDirectory(folder)) {
			return false;
		}

		try (Stream<Path> files = Files.walk(folder)) {
			return files.anyMatch(file -> file.toString().endsWith(".java"));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static JsonObject parse(String json) {
		return JsonParser.parseString(json).getAsJsonObject();
	}
}
