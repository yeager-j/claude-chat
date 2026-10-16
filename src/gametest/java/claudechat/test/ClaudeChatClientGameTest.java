package claudechat.test;

import claudechat.ClaudeChatClient;
import claudechat.KnowledgeBase;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Plays through the mod in a real client and saves screenshots to build/run/clientGameTest/screenshots:
 * the formatting preview, tab completion, and (if CLAUDECHAT_LIVE_TEST=1) a real question answered by Claude.
 */
public class ClaudeChatClientGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("claudechat-test");
	private static final int SECONDS = 20;
	// 26.x uses SDL scancodes for input, not GLFW key codes.
	private static final int KEY_ENTER = InputConstants.KEY_RETURN;
	private static final int KEY_ESCAPE = InputConstants.KEY_ESCAPE;

	@Override
	public void runTest(ClientGameTestContext context) {
		// Recipe grids read the decompiled data, so wait for the first knowledge base build.
		context.waitFor(mc -> ClaudeChatClient.isKnowledgeReady(), 600 * SECONDS);

		boolean worldTest = "1".equals(System.getenv("CLAUDECHAT_WORLD_TEST"));
		var builder = context.worldBuilder();
		if (worldTest) {
			// The default test world is superflat with structures off; structure finding needs a normal world.
			builder.adjustSettings(settings -> {
				settings.setWorldType(settings.getNormalPresetList().getFirst());
				settings.setGenerateStructures(true);
				settings.setSeed("claudechat");
			});
		}
		try (TestSingleplayerContext singleplayer = builder.create()) {
			singleplayer.getConnection().waitForChunksRender();

			// 1. The formatting preview, through the real command.
			chat(context, "/claude preview");
			context.waitTicks(10);
			openChat(context);
			context.takeScreenshot("claudechat-preview");
			context.getInput().pressKey(KEY_ESCAPE);

			// 2. Tab completion for /claude effort.
			openChat(context);
			context.getInput().typeChars("/claude effort ");
			context.waitTicks(10);
			context.takeScreenshot("claudechat-autocomplete");
			// The first Escape only closes the suggestion list; the second closes chat.
			context.getInput().pressKey(KEY_ESCAPE);
			context.getInput().pressKey(KEY_ESCAPE);
			context.waitTicks(2);

			// 3. How installed mods were classified (put mod jars in build/run/clientGameTest/mods to test real ones).
			for (KnowledgeBase.Source source : ClaudeChatClient.knowledge().sources()) {
				LOGGER.info("knowledge: {} tier={} state={} reason={} codeJars={}", source.id(),
					ClaudeChatClient.knowledge().tier(source), ClaudeChatClient.knowledge().state(source),
					ClaudeChatClient.knowledge().reason(source), source.codeContainers().size());
			}
			chat(context, "/claude knowledge");
			context.waitTicks(10);
			openChat(context);
			context.takeScreenshot("claudechat-knowledge");
			context.getInput().pressKey(KEY_ESCAPE);

			// 4. Optionally, world data packs: a folder pack adding a loot table, and a zip pack overriding a vanilla one.
			if ("1".equals(System.getenv("CLAUDECHAT_PACK_TEST"))) {
				Path datapacks = singleplayer.getServer().computeOnServer(server -> server.getWorldPath(LevelResource.DATAPACK_DIR));
				writeTestPacks(datapacks);
				singleplayer.getServer().runCommand("reload");
				context.waitTicks(20);
				context.waitFor(mc -> ClaudeChatClient.isKnowledgeReady(), 60 * SECONDS);
				chat(context, "@claude what's in a secret vault chest? and what can dungeon chests have in this world?");
				context.waitTicks(5);
				context.waitFor(mc -> !ClaudeChatClient.isBusy(), 300 * SECONDS);
				context.waitTicks(10);
				openChat(context);
				context.takeScreenshot("claudechat-datapack-answer");
				context.getInput().pressKey(KEY_ESCAPE);
			}

			// 5. Optionally, the world index: things placed near the player (live) and far away (only in the save file).
			if (worldTest) {
				var server = singleplayer.getServer();
				server.runCommand("execute at @p run setblock ~3 ~ ~ minecraft:chest{CustomName:\"Loot\",Items:[{Slot:0b,id:\"minecraft:diamond\",count:64},{Slot:1b,id:\"minecraft:iron_ingot\",count:12}]}");
				server.runCommand("execute at @p run setblock ~-3 ~ ~ minecraft:barrel{Items:[{Slot:4b,id:\"minecraft:shulker_box\",count:1,components:{\"minecraft:container\":[{slot:0,item:{id:\"minecraft:emerald\",count:32}}]}}]}");
				server.runCommand("execute at @p run setblock ~ ~ ~4 minecraft:chest{LootTable:\"minecraft:chests/simple_dungeon\"}");
				server.runCommand("execute at @p run summon minecraft:wolf ~2 ~ ~2 {CustomName:\"Rex\"}");
				server.runCommand("give @p minecraft:diamond_sword[enchantments={\"minecraft:sharpness\":5,\"minecraft:mending\":1}]");
				server.runCommand("item replace entity @p enderchest.0 with minecraft:emerald 5");
				server.runCommand("forceload add 2000 2000");
				context.waitTicks(20);
				server.runCommand("setblock 2000 100 2000 minecraft:barrel{Items:[{Slot:0b,id:\"minecraft:netherite_ingot\",count:3}]}");
				server.runCommand("setblock 2000 100 2002 minecraft:spawner{SpawnData:{entity:{id:\"minecraft:skeleton\"}}}");
				server.runCommand("setblock 2002 100 2000 minecraft:respawn_anchor[charges=4]");
				server.runCommand("execute at @p run setblock ~5 ~ ~5 minecraft:spawner{SpawnData:{entity:{id:\"minecraft:zombie\"}}}");
				server.runCommand("execute at @p run setblock ~-5 ~ ~5 minecraft:respawn_anchor[charges=1]");
				server.runCommand("forceload remove 2000 2000");
				// save-all is a dedicated-server command; this is what autosave does.
				server.runOnServer(s -> s.saveEverything(true, true, true));
				context.waitTicks(40);

				// The world tools, called directly (what Claude gets over MCP), logged for checking.
				logTool(context, "search_containers", "{\"query\": \"minecraft:diamond\"}");
				logTool(context, "search_containers", "{\"query\": \"netherite\"}");
				logTool(context, "search_containers", "{\"query\": \"emerald\"}");
				logTool(context, "search_entities", "{\"query\": \"rex\"}");
				logTool(context, "get_player", "{}");
				// Blocks: the zombie spawner and charge-1 anchor are live nearby, the skeleton spawner and full anchor only saved.
				logTool(context, "find_blocks", "{\"block\": \"spawner\"}");
				logTool(context, "find_blocks", "{\"block\": \"spawner\", \"data\": \"skeleton\"}");
				logTool(context, "find_blocks", "{\"block\": \"respawn anchor\"}");
				logTool(context, "find_blocks", "{\"block\": \"minecraft:respawn_anchor\", \"properties\": {\"charges\": \"4\"}}");
				logTool(context, "find_blocks", "{\"block\": \"#minecraft:beds\", \"dimension\": \"the_nether\"}");

				// One position three ways: saved, then predicted from the seed, then generated; the prediction's biome must hold.
				logTool(context, "inspect_location", "{\"x\": 2000, \"y\": 100, \"z\": 2002}");
				logTool(context, "inspect_location", "{\"x\": 20000, \"y\": 64, \"z\": 20000, \"dimension\": \"the_nether\"}");
				JsonObject predicted = logTool(context, "inspect_location", "{\"x\": 20000, \"z\": 20000}");
				server.runCommand("forceload add 20000 20000");
				context.waitTicks(40);
				JsonObject generated = logTool(context, "inspect_location", "{\"x\": 20000, \"z\": 20000}");
				server.runCommand("forceload remove 20000 20000");
				check(predicted.get("biome").equals(generated.get("biome")), "predicted biome " + predicted.get("biome") + " but generated " + generated.get("biome"));

				// Fortresses by biome, searched in the Nether from the Overworld. The footprint is built in memory from
				// the seed; generating the start chunk for real must give the same bounding box.
				logTool(context, "find_structures", "{\"structure\": \"fortress\", \"limit\": 3}");
				JsonObject warped = logTool(context, "find_structures", "{\"structure\": \"fortress\", \"biome\": \"warped_forest\", \"limit\": 3}");
				var fortresses = warped.getAsJsonArray("results");
				check(!fortresses.isEmpty(), "no fortress reaching into a warped forest");
				JsonObject fortress = fortresses.get(0).getAsJsonObject();
				check(fortress.getAsJsonObject("biomes").has("minecraft:warped_forest"), "biome filter let through " + fortress);
				int fortressX = fortress.get("x").getAsInt();
				int fortressZ = fortress.get("z").getAsInt();
				String actualBounds = singleplayer.getServer().computeOnServer(s -> {
					var start = s.getLevel(Level.NETHER).getChunk(fortressX >> 4, fortressZ >> 4, ChunkStatus.STRUCTURE_STARTS)
						.getStartForStructure(s.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.FORTRESS).value());
					BoundingBox box = start.getBoundingBox();
					return box.minX() + " " + box.minY() + " " + box.minZ() + " to " + box.maxX() + " " + box.maxY() + " " + box.maxZ();
				});
				LOGGER.info("fortress bounds: tool {}, generated {}", fortress.get("bounds").getAsString(), actualBounds);
				check(fortress.get("bounds").getAsString().equals(actualBounds), "fortress bounds differ from generation");
				JsonObject villages = logTool(context, "find_structures", "{\"structure\": \"village\", \"limit\": 3}");

				// Visit the nearest village for over a minute, come back, and check the visited filter flips.
				var nearest = villages.getAsJsonArray("results").get(0).getAsJsonObject();
				var home = singleplayer.getServer().computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().position());
				server.runCommand("spreadplayers " + nearest.get("x").getAsInt() + " " + nearest.get("z").getAsInt() + " 0 1 false @p");
				// InhabitedTime only advances on ticks the chunk runs mob spawning, so give it margin over the 1-minute threshold.
				context.waitTicks(100 * SECONDS);
				server.runCommand("tp @p " + home.x() + " " + home.y() + " " + home.z());
				context.waitTicks(5 * SECONDS);
				server.runOnServer(s -> s.saveEverything(true, true, true));
				context.waitTicks(40);
				logTool(context, "find_structures", "{\"structure\": \"village\", \"visited\": \"unvisited\", \"limit\": 3}");
				logTool(context, "find_structures", "{\"structure\": \"village\", \"visited\": \"visited\", \"limit\": 3}");

				chat(context, "@claude where's the nearest village I haven't explored yet? where did I put my netherite? and find me a nether fortress in a warped forest");
				context.waitTicks(5);
				context.waitFor(mc -> !ClaudeChatClient.isBusy(), 300 * SECONDS);
				context.waitTicks(10);
				openChat(context);
				context.takeScreenshot("claudechat-world-answer");
				context.getInput().pressKey(KEY_ESCAPE);
			}

			// 6. Optionally, a question about a data-only mod, which gets decompiled on demand first.
			String modQuestion = System.getenv("CLAUDECHAT_MOD_QUESTION");
			if (modQuestion != null && !modQuestion.isBlank()) {
				chat(context, "@claude " + modQuestion);
				context.waitTicks(5 * SECONDS);
				context.takeScreenshot("claudechat-mod-status");
				context.waitFor(mc -> !ClaudeChatClient.isBusy(), 300 * SECONDS);
				context.waitTicks(10);
				openChat(context);
				context.takeScreenshot("claudechat-mod-answer");
				context.getInput().pressKey(KEY_ESCAPE);
			}

			// 7. Optionally, a real question. This calls the local claude CLI and uses the signed-in account.
			if ("1".equals(System.getenv("CLAUDECHAT_LIVE_TEST"))) {
				singleplayer.getServer().runCommand("give @a minecraft:crafter");
				context.waitTicks(10);
				chat(context, "@claude how do I craft this? show the recipe");
				context.waitTicks(5);
				context.waitFor(mc -> !ClaudeChatClient.isBusy(), 180 * SECONDS);
				context.waitTicks(10);
				openChat(context);
				context.takeScreenshot("claudechat-live-answer");
				context.getInput().pressKey(KEY_ESCAPE);

				// A question that needs research, so Claude streams progress notes between tool calls.
				chat(context, "/claude reset");
				chat(context, "@claude how does the crafter decide which slot a hopper fills?");
				// The live status line: right away, then once a progress note has likely arrived.
				context.waitTicks(2 * SECONDS);
				context.takeScreenshot("claudechat-status-2s");
				context.waitTicks(10 * SECONDS);
				if (ClaudeChatClient.isBusy()) {
					context.takeScreenshot("claudechat-status-12s");
				}
				context.waitFor(mc -> !ClaudeChatClient.isBusy(), 180 * SECONDS);
				context.waitTicks(10);
				openChat(context);
				context.takeScreenshot("claudechat-live-research");
				context.getInput().pressKey(KEY_ESCAPE);
			}
		}
	}

	/** Runs a world tool off the test thread (it waits on the server thread, which the test thread drives) and logs it. */
	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	private static JsonObject logTool(ClientGameTestContext context, String name, String args) {
		CompletableFuture<JsonElement> result = CompletableFuture.supplyAsync(() -> {
			try {
				return ClaudeChatClient.worldTools().call(name, JsonParser.parseString(args).getAsJsonObject());
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		context.waitFor(mc -> result.isDone(), 120 * SECONDS);
		JsonElement json = result.join();
		LOGGER.info("tool {} {} -> {}", name, args, json);
		return json.isJsonObject() ? json.getAsJsonObject() : new JsonObject();
	}

	private static void writeTestPacks(Path datapacks) {
		PackFormat format = SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA);
		String mcmeta = """
			{"pack": {"description": "%s", "min_format": [%d, %d], "max_format": [%d, %d]}}
			""";
		String vault = """
			{"type": "minecraft:chest", "pools": [{"rolls": 2, "entries": [
			  {"type": "minecraft:item", "name": "minecraft:diamond", "weight": 1,
			   "functions": [{"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": 1, "max": 3}}]},
			  {"type": "minecraft:item", "name": "minecraft:golden_apple", "weight": 3}]}]}
			""";
		String dungeon = """
			{"type": "minecraft:chest", "pools": [{"rolls": 1, "entries": [
			  {"type": "minecraft:item", "name": "minecraft:netherite_ingot"}]}]}
			""";
		try {
			Path folder = datapacks.resolve("Secret Vaults");
			Files.createDirectories(folder.resolve("data/claudetest/loot_table/chests"));
			Files.writeString(folder.resolve("pack.mcmeta"), mcmeta.formatted("Secret vaults", format.major(), format.minor(), format.major(), format.minor()));
			Files.writeString(folder.resolve("data/claudetest/loot_table/chests/secret_vault.json"), vault);

			try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(datapacks.resolve("Better Dungeons.zip")))) {
				zip.putNextEntry(new ZipEntry("pack.mcmeta"));
				zip.write(mcmeta.formatted("Better dungeons", format.major(), format.minor(), format.major(), format.minor()).getBytes(StandardCharsets.UTF_8));
				zip.putNextEntry(new ZipEntry("data/minecraft/loot_table/chests/simple_dungeon.json"));
				zip.write(dungeon.getBytes(StandardCharsets.UTF_8));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void openChat(ClientGameTestContext context) {
		context.getInput().pressKey(options -> options.keyChat);
		context.waitTicks(2);
	}

	private static void chat(ClientGameTestContext context, String message) {
		openChat(context);
		context.getInput().typeChars(message);
		context.getInput().pressKey(KEY_ENTER);
		context.waitTicks(2);
	}
}
