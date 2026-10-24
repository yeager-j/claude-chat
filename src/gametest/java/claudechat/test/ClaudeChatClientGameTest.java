package claudechat.test;

import claudechat.Approvals;
import claudechat.ChatOutput;
import claudechat.ClaudeChatClient;
import claudechat.ClaudeConfig;
import claudechat.GameContext;
import claudechat.GameRecipe;
import claudechat.GameRecipes;
import claudechat.KnowledgeBase;
import claudechat.Materials;
import claudechat.McpServer;
import claudechat.PermissionTier;
import claudechat.Policy;
import claudechat.RecipeRenderer;
import claudechat.Tool;
import claudechat.mixin.ChatComponentAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import claudechat.Schematics;
import claudechat.StructureFinder;
import net.minecraft.resources.ResourceKey;
import java.util.Map;
import java.util.TreeMap;
import claudechat.Waypoints;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
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
	/** The pack step's zip pack, as the server names it. */
	private static final String OVERRIDE_PACK = "file/Better Dungeons.zip";
	/** The token the test's own tool calls use ({@link #usePolicy}). */
	private static McpServer.Grant grant;

	@Override
	public void runTest(ClientGameTestContext context) {
		// Recipe grids read the decompiled data, so wait for the first knowledge base build.
		context.waitFor(mc -> ClaudeChatClient.isKnowledgeReady(), 600 * SECONDS);
		LOGGER.info("optional steps: {}", System.getenv().keySet().stream().filter(k -> k.startsWith("CLAUDECHAT_")).sorted().toList());
		// The test's own tool calls get a token as a question would, with a policy the test chooses.
		usePolicy(new Policy(PermissionTier.READ_ONLY, false));

		// 0. The MCP server Claude talks to, over HTTP like claude does. With no world open (as on a server) it offers
		// only decompile_mod; the test mod itself is a data-only mod it can decompile.
		List<String> noWorldTools = mcpToolNames();
		LOGGER.info("MCP tools without a world: {}", noWorldTools);
		check(noWorldTools.contains("decompile_mod") && !noWorldTools.contains("find_structures"), "wrong tools without a world: " + noWorldTools);
		JsonObject unknown = mcpCall("tools/call", "{\"name\": \"decompile_mod\", \"arguments\": {\"mod\": \"no_such_mod\"}}");
		check(unknown.has("isError") && unknown.get("isError").getAsBoolean(), "unknown mod id didn't fail: " + unknown);
		// Availability is checked on every call, not only when listing; an unknown name is a JSON-RPC error.
		JsonObject closed = mcpCall("tools/call", "{\"name\": \"find_structures\", \"arguments\": {\"structure\": \"village\"}}");
		check(closed.has("isError") && closed.toString().contains("No single-player world"), "world tool ran without a world: " + closed);
		JsonObject noSuchTool = mcpResponse("tools/call", "{\"name\": \"no_such_tool\", \"arguments\": {}}");
		check(noSuchTool.has("error") && noSuchTool.getAsJsonObject("error").get("code").getAsInt() == -32602,
			"unknown tool isn't an invalid-params error: " + noSuchTool);
		JsonObject noMod = mcpCall("tools/call", "{\"name\": \"decompile_mod\", \"arguments\": {}}");
		check(noMod.has("isError") && noMod.toString().contains("mod is required"), "missing required argument accepted: " + noMod);
		long decompileStart = System.nanoTime();
		JsonObject decompiled = mcpCall("tools/call", "{\"name\": \"decompile_mod\", \"arguments\": {\"mod\": \"claudechat-test\"}}");
		LOGGER.info("decompile_mod claudechat-test ({} ms) -> {}", (System.nanoTime() - decompileStart) / 1_000_000, decompiled);
		check(!decompiled.has("isError") && ClaudeChatClient.knowledge().source("claudechat-test")
			.map(mod -> ClaudeChatClient.knowledge().status(mod).hasCode()).orElse(false),
			"decompile_mod didn't decompile the test mod: " + decompiled);

		boolean worldTest = "1".equals(System.getenv("CLAUDECHAT_WORLD_TEST"));
		boolean templateTest = "1".equals(System.getenv("CLAUDECHAT_TEMPLATE_TEST"));
		String waypointQuestion = System.getenv("CLAUDECHAT_WAYPOINT_QUESTION");
		waypointQuestion = waypointQuestion == null || waypointQuestion.isBlank() ? null : waypointQuestion;
		boolean permissionTest = "1".equals(System.getenv("CLAUDECHAT_PERMISSION_TEST"));
		var builder = context.worldBuilder();
		if (permissionTest) {
			// Cheats off, so the cheats setting decides whether Claude's /give works.
			builder.adjustSettings(settings -> settings.setAllowCommands(false));
		}
		// Template and waypoint questions lead Claude to look for structures, so give it a world that has them.
		if (worldTest || templateTest || waypointQuestion != null) {
			// The default test world is superflat with structures off; structure finding needs a normal world.
			builder.adjustSettings(settings -> {
				settings.setWorldType(settings.getNormalPresetList().getFirst());
				settings.setGenerateStructures(true);
				settings.setSeed("claudechat");
			});
		}
		try (TestSingleplayerContext singleplayer = builder.create()) {
			singleplayer.getConnection().waitForChunksRender();
			// With Xaero loaded the client hangs while closing the world, before Fabric prints the failure, so log it here.
			try {
				List<String> worldTools = mcpToolNames();
				check(worldTools.contains("decompile_mod") && worldTools.contains("find_structures"), "wrong tools in a world: " + worldTools);

				// 1. The formatting preview, through the real command.
				chat(context, "/claude preview");
				context.waitTicks(10);
				openChat(context);
				context.takeScreenshot("claudechat-preview");
				context.getInput().pressKey(KEY_ESCAPE);

				// 1b. Recipes come from the world's RecipeManager, every kind drawn from the same model; the trim recipe,
				// which has no fixed result, is shown in chat for the screenshot.
				checkRecipes(context);
				openChat(context);
				context.takeScreenshot("claudechat-recipes");
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
					KnowledgeBase.Status status = ClaudeChatClient.knowledge().status(source);
					LOGGER.info("knowledge: {} tier={} state={} reason={} codeJars={}", source.id(),
						status.tier(), status.state(), status.reason(), source.codeContainers().size());
				}
				chat(context, "/claude knowledge");
				context.waitTicks(10);
				openChat(context);
				context.takeScreenshot("claudechat-knowledge");
				context.getInput().pressKey(KEY_ESCAPE);

				// 4. Optionally, world data packs: a folder pack adding a loot table, and a zip pack overriding a vanilla loot
				// table and the piston recipe. Recipe grids, material lists and the data roots follow the server's packs.
				if ("1".equals(System.getenv("CLAUDECHAT_PACK_TEST"))) {
					Path datapacks = singleplayer.getServer().computeOnServer(server -> server.getWorldPath(LevelResource.DATAPACK_DIR));
					writeTestPacks(datapacks);
					singleplayer.getServer().runCommand("reload");
					context.waitFor(mc -> pistonCount() == 2, 60 * SECONDS);
					context.waitFor(mc -> ClaudeChatClient.isKnowledgeReady(), 60 * SECONDS);
					checkPackOverrides(context);

					if ("1".equals(System.getenv("CLAUDECHAT_LIVE_TEST"))) {
						chat(context, "@claude what's in a secret vault chest? and what can dungeon chests have in this world?");
						context.waitTicks(5);
						context.waitFor(mc -> !ClaudeChatClient.isBusy(), 300 * SECONDS);
						context.waitTicks(10);
						openChat(context);
						context.takeScreenshot("claudechat-datapack-answer");
						context.getInput().pressKey(KEY_ESCAPE);
					}

					// A disabled pack is gone from both, so later steps see vanilla pistons again.
					singleplayer.getServer().runCommand("datapack disable \"" + OVERRIDE_PACK + "\"");
					context.waitFor(mc -> pistonCount() == 1, 60 * SECONDS);
					check(ClaudeChatClient.knowledge().dataRoots().stream().noneMatch(root -> root.toString().contains("Dungeons")),
						"the disabled pack is still a data root: " + ClaudeChatClient.knowledge().dataRoots());
				}

				// 4b. Optionally, structure templates: search by blocks, trace to structures through pools, draw layers.
				if (templateTest) {
					// The index is built in the background when the world opens; the first search waits for it if needed.
					logTool(context, "search_structure_templates", "{\"blocks\": [\"trial_spawner\"], \"limit\": 5}");
					JsonObject trial = logTool(context, "search_structure_templates", "{\"blocks\": [\"trial_spawner\"], \"query\": \"trial_chambers\", \"limit\": 3}");
					check(trial.get("templates_indexed").getAsInt() > 500, "too few templates indexed");
					check(trial.getAsJsonArray("results").get(0).getAsJsonObject().getAsJsonArray("used_by_structures")
						.toString().contains("\"trial_chambers\""), "trial spawner template not traced to trial chambers");
					JsonObject city = logTool(context, "search_structure_templates", "{\"blocks\": [\"sculk shrieker\", \"reinforced_deepslate\", \"soul lantern\"], \"limit\": 3}");
					check(city.getAsJsonArray("results").toString().contains("\"ancient_city/"),
						"sculk shrieker search didn't find an ancient city piece");
					logTool(context, "search_structure_templates", "{\"blocks\": [\"mossy_stone_bricks\", \"spawner\"], \"limit\": 5}");
					logTool(context, "search_structure_templates", "{\"blocks\": [\"barrel\", \"brewing_stand\"], \"limit\": 5}");
					JsonObject house = logTool(context, "inspect_structure_template",
						"{\"template\": \"minecraft:village/plains/houses/plains_small_house_1\", \"layers\": true}");
					check(house.getAsJsonArray("used_by_structures").toString().contains("\"village_plains\""), "house not traced to plains villages");
					check(house.getAsJsonArray("layers").size() > 3, "house layers missing");
					check("vanilla".equals(house.get("from").getAsString()), "house isn't from the vanilla pack: " + house.get("from"));
					logTool(context, "inspect_structure_template", "{\"template\": \"minecraft:igloo/bottom\"}");
					check(toolError(context, "inspect_structure_template", "{\"template\": \"plains_small_house\"}").contains("No structure template"),
						"a partial template id should fail");

					// Saving with a structure block shows up in the next search, and again after saving over it.
					String towerSearch = "{\"blocks\": [\"gold_block\"], \"query\": \"claudechat:\"}";
					saveTestTower(singleplayer, 1);
					JsonObject saved = logTool(context, "search_structure_templates", towerSearch).getAsJsonArray("results").get(0).getAsJsonObject();
					check(saved.get("template").getAsString().equals("claudechat:test_tower") && saved.get("solid_blocks").getAsInt() == 1
						&& saved.get("from").getAsString().startsWith("this world"), "saved template not indexed: " + saved);
					saveTestTower(singleplayer, 3);
					JsonObject resaved = logTool(context, "search_structure_templates", towerSearch).getAsJsonArray("results").get(0).getAsJsonObject();
					check(resaved.get("solid_blocks").getAsInt() == 3, "index kept the old save: " + resaved);
					JsonObject tower = logTool(context, "inspect_structure_template", "{\"template\": \"claudechat:test_tower\", \"layers\": true}");
					check(tower.get("solid_blocks").getAsInt() == 3 && tower.get("from").getAsString().startsWith("this world"),
						"inspect doesn't match the save: " + tower);

					// Limits on imported templates: after a reload the index is rebuilt with a wide template, too big for the
					// parallel pass but fine alone, and one whose data would need a gigabyte, which is refused.
					writeOversizedTemplates(singleplayer.getServer().computeOnServer(server -> server.getWorldPath(LevelResource.GENERATED_DIR)));
					reloadResources(context, singleplayer);
					JsonObject wide = logTool(context, "search_structure_templates", "{\"blocks\": [\"emerald_block\"], \"query\": \"claudechat:\"}");
					check(wide.get("templates_indexed").getAsInt() > 500, "too few templates indexed after adding oversized ones");
					check(wide.getAsJsonArray("results").get(0).getAsJsonObject().get("template").getAsString().equals("claudechat:wide_slab"),
						"the wide template wasn't indexed one at a time: " + wide);
					String bomb = toolError(context, "inspect_structure_template", "{\"template\": \"claudechat:bomb\"}");
					check(bomb.contains("too large") && bomb.contains("256 MiB"), "the oversized template isn't refused by its limit: " + bomb);
					String question = System.getenv("CLAUDECHAT_TEMPLATE_QUESTION");
					if (question != null && !question.isBlank()) {
						chat(context, "@claude " + question);
						context.waitTicks(5);
						context.waitFor(mc -> !ClaudeChatClient.isBusy(), 300 * SECONDS);
						context.waitTicks(10);
						openChat(context);
						context.takeScreenshot("claudechat-template-answer");
						context.getInput().pressKey(KEY_ESCAPE);
					}
				}

				// 4c. Optionally, schematics: copies the files in CLAUDECHAT_SCHEMATIC_TEST (a folder) into the test's schematics
				// folder, reads their material lists, and checks a chest holding all but one redstone dust against the first.
				String schematicSource = System.getenv("CLAUDECHAT_SCHEMATIC_TEST");
				if (schematicSource != null && !schematicSource.isBlank()) {
					copySchematics(Path.of(schematicSource), Schematics.folder().dir());
					JsonObject list = logTool(context, "list_schematics", "{}");
					var files = list.getAsJsonArray("schematics");
					check(!files.isEmpty(), "no schematics listed");
					// With the unit tests' fixtures (see ExportSchematicFixtures): matching, upgrades and unreadable files.
					if (files.toString().contains("\"farms/Witch Farm.litematic\"")) {
						check(logTool(context, "list_schematics", "{\"query\": \"farms/witch\"}").getAsJsonArray("schematics").size() == 2,
							"a query with / should list both farms");
						check(toolError(context, "inspect_schematic", "{\"schematic\": \"witch\"}").contains("several"), "\"witch\" should be ambiguous");
						JsonObject farm = logTool(context, "inspect_schematic", "{\"schematic\": \"farms\\\\witch farm\"}");
						check(farm.getAsJsonArray("regions").size() == 2, "the two-region schematic lost a region: " + farm);
						JsonObject spawner = logTool(context, "inspect_schematic", "{\"schematic\": \"Old Spawner\"}");
						check(spawner.getAsJsonArray("block_entities").toString().contains("\"spawns\":\"skeleton\""),
							"the 1.12 spawner wasn't upgraded: " + spawner);
						check(files.toString().matches(".*\"old/Broken.litematic\",\"error\":\"unreadable.*"), "the broken file isn't marked unreadable");
						check(files.toString().contains("Bomb.schem is too large to read"), "listing doesn't refuse the decompression bomb by its limit");
						String bomb = toolError(context, "inspect_schematic", "{\"schematic\": \"Bomb\"}");
						check(bomb.contains("too large") && bomb.contains("256 MiB"), "inspecting the decompression bomb isn't refused by its limit: " + bomb);
					}
					String file = files.get(0).getAsJsonObject().get("file").getAsString();
					JsonObject schematic = logTool(context, "inspect_schematic", "{\"schematic\": \"" + file + "\", \"layers\": true}");
					JsonObject materials = schematic.getAsJsonObject("materials");
					check(!materials.isEmpty(), "schematic has no materials");

					// A chest (or two) named "Witch Farm" with every material except one redstone dust, or one of the first item.
					String shortItem = materials.has("redstone") ? "redstone" : materials.keySet().iterator().next();
					List<String> stacks = new ArrayList<>();
					for (var entry : materials.entrySet()) {
						int count = entry.getValue().getAsInt() - (entry.getKey().equals(shortItem) ? 1 : 0);
						int max = BuiltInRegistries.ITEM.getValue(Identifier.parse(entry.getKey())).getDefaultMaxStackSize();
						for (; count > 0; count -= max) {
							stacks.add("{id:\"" + entry.getKey() + "\",count:" + Math.min(max, count) + "}");
						}
					}
					var pos = singleplayer.getServer().computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().blockPosition());
					for (int chest = 0; chest * 27 < stacks.size(); chest++) {
						StringBuilder items = new StringBuilder();
						for (int slot = 0; slot < 27 && chest * 27 + slot < stacks.size(); slot++) {
							String stack = stacks.get(chest * 27 + slot);
							items.append(slot == 0 ? "" : ",").append(stack, 0, stack.length() - 1).append(",Slot:").append(slot).append("b}");
						}
						singleplayer.getServer().runCommand("setblock " + (pos.getX() + 2 + chest * 2) + " " + pos.getY() + " " + (pos.getZ() + 2)
							+ " minecraft:chest{CustomName:\"Witch Farm\",Items:[" + items + "]}");
					}
					context.waitTicks(10);
					JsonObject compared = logTool(context, "inspect_schematic", "{\"schematic\": \"" + file + "\", \"compare_with\": \"witch farm\"}")
						.getAsJsonObject("comparison");
					var stillMissing = compared.getAsJsonObject("still_missing_after_crafting_from_contents");
					check(stillMissing.size() == 1 && stillMissing.has(shortItem) && stillMissing.get(shortItem).getAsInt() == 1,
						"comparison should miss exactly one " + shortItem + ": " + compared);
					check(compared.getAsJsonObject("useful_contents").has(shortItem), "comparison doesn't show what the chests hold");

					String question = System.getenv("CLAUDECHAT_SCHEMATIC_QUESTION");
					if (question != null && !question.isBlank()) {
						chat(context, "@claude " + question);
						context.waitTicks(5);
						context.waitFor(mc -> !ClaudeChatClient.isBusy(), 300 * SECONDS);
						context.waitTicks(10);
						openChat(context);
						context.takeScreenshot("claudechat-schematic-answer");
						context.getInput().pressKey(KEY_ESCAPE);
					}
				}

				// 4d. Optionally, Xaero waypoint buttons (load Xaero's Minimap with -PextraMods): run each button's click command
				// the way a chat click does, confirm Xaero's Add Waypoint screen, and check the waypoint is saved in the right
				// dimension. The name with a colon checks Xaero's escaping; the Nether's "dim%-1" checks the '-' handling.
				if ("1".equals(System.getenv("CLAUDECHAT_WAYPOINT_TEST"))) {
					if (!Waypoints.available()) {
						throw new AssertionError("CLAUDECHAT_WAYPOINT_TEST needs Xaero's Minimap: pass -PextraMods");
					}
					addWaypoint(context, "Claude End City|1240 ~ -3380|the_end", "dim%1", ":1240:~:-3380:", "end");
					addWaypoint(context, "Claude: Fortress|-210 70 400|nether", "dim%-1", ":-210:70:400:", "nether");
					addWaypoint(context, "Claude Here|5 64 -5", "dim%0", ":5:64:-5:", "current");
					if (waypointQuestion != null) {
						chat(context, "@claude " + waypointQuestion);
						context.waitTicks(5);
						context.waitFor(mc -> !ClaudeChatClient.isBusy(), 300 * SECONDS);
						context.waitTicks(10);
						openChat(context);
						context.takeScreenshot("claudechat-waypoint-answer");
						context.getInput().pressKey(KEY_ESCAPE);
					}
				}

				// 4e. Optionally, permissions: run_command in each tier, with cheats off and on, and the chat approval
				// buttons clicked the way chat does (sendUnattendedCommand, which Fabric hands to client commands).
				if (permissionTest) {
					testPermissions(context, singleplayer);
				}

				// 4f. Optionally, the look tool: a scene in front of the player with a stone wall and a glass window. The
				// diamond blocks behind the window must be seen through glass; the gold block and the pig behind the
				// wall must not be seen at all.
				if ("1".equals(System.getenv("CLAUDECHAT_LOOK_TEST"))) {
					testLook(context, singleplayer);
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
					server.runCommand("execute in minecraft:the_nether run forceload add 0 0");
					context.waitTicks(20);
					server.runCommand("execute in minecraft:the_nether run setblock 3 70 3 minecraft:chest{CustomName:\"Nether Stash\",Items:[{Slot:0b,id:\"minecraft:blaze_rod\",count:7}]}");
					server.runCommand("execute in minecraft:the_nether run forceload remove 0 0");
					// save-all is a dedicated-server command; this is what autosave does.
					server.runOnServer(s -> s.saveEverything(true, true, true));
					context.waitTicks(40);

					// The world tools, called directly (what Claude gets over MCP), logged for checking.
					logTool(context, "search_containers", "{\"query\": \"minecraft:diamond\"}");
					logTool(context, "search_containers", "{\"query\": \"netherite\"}");
					logTool(context, "search_containers", "{\"query\": \"emerald\"}");
					logTool(context, "search_entities", "{\"query\": \"rex\"}");
					logTool(context, "get_player", "{}");
					// A chest in another dimension: found with no distance limit, and when its dimension is asked for by
					// either name; a distance limit keeps the search in the player's dimension.
					for (String args : List.of("{\"query\": \"blaze_rod\"}", "{\"query\": \"blaze_rod\", \"dimension\": \"the_nether\"}",
						"{\"query\": \"blaze_rod\", \"dimension\": \"minecraft:the_nether\"}")) {
						JsonObject nether = logTool(context, "search_containers", args);
						check(nether.getAsJsonArray("results").toString().contains("\"dimension\":\"the_nether\""), "Nether chest not found by " + args);
					}
					JsonObject nearby = logTool(context, "search_containers", "{\"query\": \"blaze_rod\", \"max_distance\": 100}");
					check(nearby.getAsJsonArray("results").isEmpty(), "max_distance searched another dimension: " + nearby);
					// Blocks: the zombie spawner and charge-1 anchor are live nearby, the skeleton spawner and full anchor only saved.
					logTool(context, "find_blocks", "{\"block\": \"spawner\"}");
					logTool(context, "find_blocks", "{\"block\": \"spawner\", \"data\": \"skeleton\"}");
					logTool(context, "find_blocks", "{\"block\": \"respawn anchor\"}");
					logTool(context, "find_blocks", "{\"block\": \"minecraft:respawn_anchor\", \"properties\": {\"charges\": \"4\"}}");
					logTool(context, "find_blocks", "{\"block\": \"#minecraft:beds\", \"dimension\": \"the_nether\"}");
					// Arguments are parsed, not defaulted: a wrong type or an empty required query is an error.
					check(toolError(context, "find_blocks", "{\"block\": \"spawner\", \"limit\": \"five\"}").contains("limit must be a whole number"),
						"a malformed limit was accepted");
					check(toolError(context, "find_blocks", "{\"block\": \"spawner\", \"max_distance\": \"500 blocks\"}").contains("max_distance"),
						"a malformed max_distance was accepted");
					check(toolError(context, "find_structures", "{\"structure\": \" \"}").contains("structure must not be empty"),
						"an empty structure query was accepted");

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
					check(fortress.getAsJsonObject("biomes").has("warped_forest"), "biome filter let through " + fortress);
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
					JsonObject mostlyWarped = logTool(context, "find_structures",
						"{\"structure\": \"fortress\", \"biome\": \"warped_forest\", \"min_biome_share\": 75, \"limit\": 5}");
					for (JsonElement result : mostlyWarped.getAsJsonArray("results")) {
						String share = result.getAsJsonObject().get("share_in_requested_biome").getAsString();
						check(Integer.parseInt(share.replace("%", "")) >= 75, "min_biome_share let through " + result);
					}

					// Biomes: from the Overworld, a warped forest is searched in the Nether. The nearest deep dark must be no
					// farther than the one /locate biome finds on the same grid (it stops at the first hit in spiral order).
					JsonObject warpedBiome = logTool(context, "find_biomes", "{\"biome\": \"warped_forest\"}");
					check(warpedBiome.get("dimension").getAsString().equals("the_nether"), "warped forest not searched in the Nether");
					logTool(context, "find_biomes", "{\"biome\": \"#minecraft:is_ocean\", \"limit\": 5}");
					JsonObject deepDark = logTool(context, "find_biomes", "{\"biome\": \"deep_dark\", \"limit\": 3}");
					Double locateDistance = singleplayer.getServer().computeOnServer(s -> {
						var player = s.getPlayerList().getPlayers().getFirst();
						var pair = s.overworld().findClosestBiome3d(b -> b.is(Biomes.DEEP_DARK), player.blockPosition(), 6400, 32, 64);
						return pair == null ? null : Math.sqrt(Math.pow(pair.getFirst().getX() - player.getX(), 2) + Math.pow(pair.getFirst().getZ() - player.getZ(), 2));
					});
					var deepDarkResults = deepDark.getAsJsonArray("results");
					LOGGER.info("deep dark: /locate {} blocks, find_biomes {}", locateDistance, deepDarkResults.isEmpty() ? "none" : deepDarkResults.get(0));
					check(locateDistance == null || (!deepDarkResults.isEmpty()
						&& deepDarkResults.get(0).getAsJsonObject().get("distance_blocks").getAsDouble() <= locateDistance + 2),
						"find_biomes' nearest deep dark is farther than /locate's");
					JsonObject villages = logTool(context, "find_structures", "{\"structure\": \"village\", \"limit\": 3}");

					// Pieces, built in memory, must match real generation for three kinds of structure: hand-coded
					// pieces (fortress), templates (end city) and jigsaw pools (village). End cities with a ship are
					// searched in the End from the Overworld.
					JsonObject shipCities = logTool(context, "find_structures", "{\"structure\": \"end_city\", \"with_pieces\": [\"ship\"], \"limit\": 2}");
					check(!shipCities.getAsJsonArray("results").isEmpty(), "no end city with a ship");
					for (JsonElement city : shipCities.getAsJsonArray("results")) {
						check(city.getAsJsonObject().getAsJsonObject("pieces").has("end_city/ship"), "with_pieces let through " + city);
						checkPieces(singleplayer, Level.END, city.getAsJsonObject());
					}
					checkPieces(singleplayer, Level.NETHER, fortress);
					checkPieces(singleplayer, Level.OVERWORLD, villages.getAsJsonArray("results").get(0).getAsJsonObject());

					// Visit the nearest village for a few seconds, come back, and check the visited filter flips.
					var nearest = villages.getAsJsonArray("results").get(0).getAsJsonObject();
					var home = singleplayer.getServer().computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().position());
					server.runCommand("spreadplayers " + nearest.get("x").getAsInt() + " " + nearest.get("z").getAsInt() + " 0 1 false @p");
					context.waitTicks(10 * SECONDS);
					// Generate and save land far away with nobody near, then stand in it. Nothing in the chunk changes,
					// so the game doesn't write its new time; the visits file must have it.
					server.runCommand("forceload add 3000 3000");
					context.waitTicks(40);
					server.runCommand("forceload remove 3000 3000");
					context.waitTicks(40);
					server.runOnServer(s -> s.saveEverything(true, true, true));
					server.runCommand("spreadplayers 3000 3000 0 1 false @p");
					context.waitTicks(5 * SECONDS);
					server.runCommand("tp @p " + home.x() + " " + home.y() + " " + home.z());
					context.waitTicks(10 * SECONDS);
					server.runOnServer(s -> s.saveEverything(true, true, true));
					context.waitTicks(40);
					JsonObject unvisited = logTool(context, "find_structures", "{\"structure\": \"village\", \"visited\": \"unvisited\", \"limit\": 3}");
					JsonObject visited = logTool(context, "find_structures", "{\"structure\": \"village\", \"visited\": \"visited\", \"limit\": 3}");
					String villageAt = "\"x\":" + nearest.get("x").getAsInt() + ",\"z\":" + nearest.get("z").getAsInt();
					check(visited.getAsJsonArray("results").toString().contains(villageAt), "visited village not listed as visited");
					check(!unvisited.getAsJsonArray("results").toString().contains(villageAt), "visited village listed as unvisited");
					Path visits = singleplayer.getServer().computeOnServer(s -> s.getWorldPath(LevelResource.ROOT)).resolve("claudechat-visits.txt");
					List<String> lines = readLines(visits);
					List<String> farChunk = lines.stream().filter(l -> l.startsWith("minecraft:overworld 187 187 ")).toList();
					LOGGER.info("visits file: {} lines, far chunk {}", lines.size(), farChunk);
					// 5 seconds there is 100 ticks; leave margin for the teleports.
					check(!farChunk.isEmpty() && Long.parseLong(farChunk.getFirst().split(" ")[3]) >= 60, "time in the far chunk wasn't recorded");

					// Asking Claude uses the signed-in account, so only in live runs.
					if ("1".equals(System.getenv("CLAUDECHAT_LIVE_TEST"))) {
						chat(context, "@claude where's the nearest village I haven't explored yet? where did I put my netherite? and find me a nether fortress in a warped forest");
						context.waitTicks(5);
						context.waitFor(mc -> !ClaudeChatClient.isBusy(), 300 * SECONDS);
						context.waitTicks(10);
						openChat(context);
						context.takeScreenshot("claudechat-world-answer");
						context.getInput().pressKey(KEY_ESCAPE);
					}
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
			} catch (Throwable t) {
				LOGGER.error("test step failed", t);
				throw t;
			}
		}

		// 8. Optionally, questions answered by a scripted stand-in for claude, in worlds of their own.
		if ("1".equals(System.getenv("CLAUDECHAT_RUN_TEST"))) {
			try {
				testRuns(context);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			} catch (RuntimeException | Error e) {
				LOGGER.error("run test failed", e);
				throw e;
			}
		}
	}

	/**
	 * run_command and approve as a question with each policy would call them: every tier with cheats off and on, the
	 * note and level cheats give, and the chat approval buttons clicked the way chat does.
	 */
	private static void testPermissions(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		var server = singleplayer.getServer();
		try {
			check(mcpToolNames().contains("run_command") && mcpToolNames().contains("approve"), "run_command or approve not offered");

			// Read only, cheats on: commands that change nothing run, the rest are refused; cheats don't change that.
			usePolicy(new Policy(PermissionTier.READ_ONLY, true));
			for (String command : List.of("time query time", "/execute if entity @s", "gamerule keep_inventory",
				"scoreboard objectives list", "execute as @s at @s if block ~ ~-1 ~ minecraft:air")) {
				JsonObject r = runCommand(context, command);
				check(!r.has("error"), "read-only command refused: " + command + " -> " + r);
			}
			for (String command : List.of("gamerule keep_inventory true", "execute as @s run kill @s", "give @s minecraft:diamond",
				"execute store result score @s x if entity @s", "time set night")) {
				JsonObject r = runCommand(context, command);
				check(r.has("error") && r.get("error").getAsString().contains("Read only"), "changing command not refused: " + command + " -> " + r);
			}
			// Execute modifiers that write, ahead of a "run" whose command only reads.
			List<String> writingModifiers = List.of("execute summon minecraft:pig run seed",
				"execute store result entity @s Air short 1 run data get entity @s Air",
				"execute store result score @s x run time query time", "execute if function ns:x", "execute if function ns:x run seed");
			for (String command : writingModifiers) {
				JsonObject r = runCommand(context, command);
				check(r.has("error") && r.get("error").getAsString().contains("Read only"), "writing execute not refused: " + command + " -> " + r);
			}
			// The cheats note says what's true: /time needs command block level, which the player lacks here; /list doesn't.
			check(runCommand(context, "time query time").has("note"), "time query ran with cheats but has no note");
			check(!runCommand(context, "list").has("note"), "list has the cheats note though the player could run it");
			usePolicy(new Policy(PermissionTier.READ_ONLY, false));
			JsonObject ownLevel = runCommand(context, "time query time");
			check(ownLevel.has("error") && ownLevel.get("error").getAsString().contains("/claude cheats on"),
				"time query ran without cheats in a world without cheats: " + ownLevel);

			// Cheats off: the player's own permissions, which in this world can't run cheat commands at all.
			usePolicy(new Policy(PermissionTier.FULL, false));
			JsonObject noCheats = runCommand(context, "give @s minecraft:diamond");
			check(noCheats.has("error") && noCheats.get("error").getAsString().contains("/claude cheats on"), "give ran without cheats: " + noCheats);
			check(!hasDiamond(server), "diamond given without cheats");
			// Cheats on: the same command at command block level, still as the player.
			usePolicy(new Policy(PermissionTier.FULL, true));
			JsonObject cheated = runCommand(context, "give @s minecraft:diamond");
			check(cheated.has("success") && cheated.get("success").getAsBoolean() && cheated.has("note"), "give with cheats failed: " + cheated);
			check(hasDiamond(server), "no diamond after give with cheats");

			// Ask for approval: read-only commands don't ask; others wait for a click.
			usePolicy(new Policy(PermissionTier.ASK, true));
			String before = context.computeOnClient(mc -> newestButton(mc, "Allow"));
			check(!runCommand(context, "time query time").has("error")
				&& Objects.equals(before, context.computeOnClient(mc -> newestButton(mc, "Allow"))), "a read-only command asked");
			for (String command : writingModifiers) {
				JsonObject r = withApproval(context, command, false, null);
				check(r.has("error") && r.get("error").getAsString().contains("denied"), "writing execute didn't ask: " + command + " -> " + r);
			}
			var below = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().blockPosition().below());
			JsonObject denied = withApproval(context, "setblock ~ ~-1 ~ minecraft:gold_block", false, "claudechat-approval-setblock");
			check(denied.has("error") && denied.get("error").getAsString().contains("denied"), "denied setblock ran: " + denied);
			check(!server.computeOnServer(s -> s.overworld().getBlockState(below).is(Blocks.GOLD_BLOCK)), "block placed after Deny");
			JsonObject allowed = withApproval(context, "fill ~-2 ~-1 ~-2 ~2 ~-1 ~2 minecraft:gold_block", true, "claudechat-approval-fill");
			check(allowed.has("success") && allowed.get("success").getAsBoolean(), "allowed fill failed: " + allowed);
			check(server.computeOnServer(s -> s.overworld().getBlockState(below).is(Blocks.GOLD_BLOCK)), "no gold block after Allow");
			// A used button does nothing: "That button has expired."
			click(context, context.computeOnClient(mc -> newestButton(mc, "Allow")));

			// Claude Code's own permission prompts, through the MCP tool as claude calls it. The Allow returns exactly
			// the input shown, including a field the request lists as "also".
			CompletableFuture<JsonObject> prompt = CompletableFuture.supplyAsync(() -> mcpCall("tools/call",
				"{\"name\": \"approve\", \"arguments\": {\"tool_name\": \"Write\", \"input\": {\"file_path\": \"config/x.txt\","
					+ " \"content\": \"hi\\nthere\", \"mode\": \"append\"}}}"));
			answer(context, true, "claudechat-approval-write");
			context.waitFor(mc -> prompt.isDone(), 10 * SECONDS);
			JsonObject decision = JsonParser.parseString(resultText(prompt.join())).getAsJsonObject();
			LOGGER.info("approve Write -> {}", decision);
			check(decision.get("behavior").getAsString().equals("allow") && decision.getAsJsonObject("updatedInput").equals(
				JsonParser.parseString("{\"file_path\": \"config/x.txt\", \"content\": \"hi\\nthere\", \"mode\": \"append\"}")),
				"approve tool didn't allow with the input shown: " + decision);
			check(context.computeOnClient(mc -> chatHas(mc, "(also mode=\"append\")")), "the extra input field wasn't shown");
			// Outside Ask for approval there's nothing to approve.
			usePolicy(new Policy(PermissionTier.READ_ONLY, true));
			JsonObject refused = JsonParser.parseString(resultText(mcpCall("tools/call", "{\"name\": \"approve\", \"arguments\":"
				+ " {\"tool_name\": \"Bash\", \"input\": {\"command\": \"echo hi\"}}}"))).getAsJsonObject();
			check(refused.get("behavior").getAsString().equals("deny"), "approve allowed outside Ask for approval: " + refused);

			// Live: Claude asks, the test clicks Allow, and the change happens.
			if ("1".equals(System.getenv("CLAUDECHAT_LIVE_TEST"))) {
				Path file = FabricLoader.getInstance().getGameDir().resolve("config/claude-permission-test.txt");
				try {
					Files.deleteIfExists(file);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
				chat(context, "/claude permissions ask");
				String last = context.computeOnClient(mc -> newestButton(mc, "Allow"));
				chat(context, "@claude create the file config/claude-permission-test.txt in the game folder containing the word hello,"
					+ " then set the time to midnight with a command");
				for (int i = 0; i < 4 && !(Files.exists(file) && !ClaudeChatClient.isBusy()); i++) {
					String seen = last;
					context.waitFor(mc -> !Objects.equals(newestButton(mc, "Allow"), seen) || !ClaudeChatClient.isBusy(), 180 * SECONDS);
					last = context.computeOnClient(mc -> newestButton(mc, "Allow"));
					if (Objects.equals(last, seen)) {
						break;
					}
					context.waitTicks(10);
					openChat(context);
					context.takeScreenshot("claudechat-live-approval-" + i);
					context.getInput().pressKey(KEY_ESCAPE);
					click(context, last);
				}
				context.waitFor(mc -> !ClaudeChatClient.isBusy(), 180 * SECONDS);
				context.waitTicks(10);
				openChat(context);
				context.takeScreenshot("claudechat-live-permissions-answer");
				context.getInput().pressKey(KEY_ESCAPE);
				check(Files.exists(file), "Claude didn't create the file after Allow");
				chat(context, "/claude permissions read_only");
			}
		} finally {
			usePolicy(new Policy(PermissionTier.READ_ONLY, false));
			// The live crafter question expects the crafter in the first hotbar slot.
			server.runCommand("clear @a minecraft:diamond");
		}
	}

	/**
	 * Questions answered by {@link FakeClaude}, a scripted stand-in for claude that claudePath points to: an answer
	 * after progress notes, an error, a lost session, a tool call whose tier changes mid-question, a timeout, and a
	 * disconnect mid-question whose late answer must never reach the next world.
	 */
	private static void testRuns(ClientGameTestContext context) throws IOException {
		Path folder = FabricLoader.getInstance().getGameDir().resolve("claudechat-fake");
		ClaudeConfig config = ClaudeChatClient.config();
		Optional<Path> savedPath = context.computeOnClient(mc -> config.claudePath);
		int savedTimeout = context.computeOnClient(mc -> config.timeoutSeconds);
		Path wrapper = FakeClaude.writeWrapper(folder);
		Files.deleteIfExists(folder.resolve("calls.log"));
		context.runOnClient(mc -> {
			config.claudePath = Optional.of(wrapper);
			config.timeoutSeconds = 30;
		});
		try {
			List<Long> abandoned;
			try (TestSingleplayerContext world = context.worldBuilder().adjustSettings(s -> s.setAllowCommands(true)).create()) {
				world.getConnection().waitForChunksRender();
				var server = world.getServer();

				// An answer after a progress note, which shows in the status line while the fake waits.
				scenario(folder, "progress Fake answer about crafters");
				chat(context, "@claude what does the fake know?");
				context.waitFor(mc -> chatHas(mc, "Checking the fake data"), 30 * SECONDS);
				go(folder);
				awaitAnswer(context);
				check(context.computeOnClient(mc -> chatHas(mc, "Fake answer about crafters")), "the fake's answer isn't in chat");
				check(!context.computeOnClient(mc -> chatHas(mc, "Checking the fake data")), "the status line stayed after the answer");

				// An error result shows Claude Code's own message.
				scenario(folder, "error Fake failure: the model is overloaded");
				chat(context, "@claude fail, please");
				awaitAnswer(context);
				check(context.computeOnClient(mc -> chatHas(mc, "Fake failure: the model is overloaded")), "the error message isn't in chat");

				// A follow-up whose session is gone is asked again in a new conversation.
				scenario(folder, "missing-session Answer in a fresh conversation");
				chat(context, "@claude and now?");
				awaitAnswer(context);
				check(context.computeOnClient(mc -> chatHas(mc, "Answer in a fresh conversation")), "no answer after the lost session");
				List<String> calls = FakeClaude.calls(folder);
				check(calls.size() >= 2 && calls.get(calls.size() - 2).contains("--resume fake-session-error")
					&& !calls.getLast().contains("--resume"), "the lost session wasn't retried fresh: " + calls);

				// The tier a question starts with is the one its run_command calls get, whatever changes meanwhile; its
				// token dies with it.
				chat(context, "/claude permissions read_only");
				scenario(folder, "run_command setblock 3 -55 3 minecraft:gold_block");
				chat(context, "@claude put a gold block down");
				awaitStarted(context, folder);
				chat(context, "/claude permissions full");
				click(context, context.computeOnClient(mc -> newestButton(mc, "Turn on Full access")));
				check(context.computeOnClient(mc -> config.permissions) == PermissionTier.FULL, "Full access wasn't turned on");
				go(folder);
				awaitAnswer(context);
				check(context.computeOnClient(mc -> chatHas(mc, "run_command said: Error: The player's permissions are Read only")),
					"run_command didn't use the tier the question started with");
				check(!server.computeOnServer(s -> s.overworld().getBlockState(new BlockPos(3, -55, 3)).is(Blocks.GOLD_BLOCK)),
					"the block was placed with the tier set mid-question");
				check(!FakeClaude.calls(folder).getLast().contains("bypassPermissions"), "the question was started with Full access flags");
				List<String> mcp = readLines(folder.resolve("mcp.txt"));
				check(httpStatus(mcp.get(0), mcp.get(1)) == 401, "the finished question's token still works");
				try (var left = Files.list(ClaudeConfig.dir().resolve("runs"))) {
					List<Path> files = left.toList();
					check(files.isEmpty(), "question files left behind: " + files);
				}
				chat(context, "/claude permissions read_only");

				// A question that never ends is stopped at the timeout, with the process it started.
				context.runOnClient(mc -> config.timeoutSeconds = 3);
				scenario(folder, "hang");
				chat(context, "@claude never answer");
				List<Long> hung = awaitStarted(context, folder);
				check(hung.size() == 2, "the fake didn't start its child: " + hung);
				awaitAnswer(context);
				check(context.computeOnClient(mc -> chatHas(mc, "Claude took longer than 3s and was stopped.")), "no timeout message");
				awaitDead(context, hung);
				context.runOnClient(mc -> config.timeoutSeconds = 30);

				// Leaving the world mid-question cancels it.
				scenario(folder, "late-answer Late answer from the old world");
				chat(context, "@claude answer later");
				abandoned = awaitStarted(context, folder);
			}

			awaitDead(context, abandoned);
			check(!ClaudeChatClient.isBusy(), "still busy after leaving the world");
			go(folder);
			try (TestSingleplayerContext world = context.worldBuilder().create()) {
				world.getConnection().waitForChunksRender();
				// It starts with a recipe grid, built on the client thread from icons: an icon's text is its sprite
				// ("[item/…@items]"), a name instead of one would read "[Smithing Template]". The prefix gets a line of its own.
				scenario(folder, "answer [[recipe:minecraft:sentry_armor_trim_smithing_template_smithing_trim]]\nFresh answer in the new world");
				chat(context, "@claude anything new?");
				awaitAnswer(context);
				check(context.computeOnClient(mc -> chatHas(mc, "Fresh answer in the new world")), "no answer in the new world");
				check(context.computeOnClient(mc -> chatHas(mc, "item/sentry_armor_trim_smithing_template")
					&& !chatHas(mc, "[Smithing Template]")), "the answer's trim grid has no icons");
				check(context.computeOnClient(mc -> chatMessages(mc).stream()
					.anyMatch(message -> message.content().getString().equals(ChatOutput.prefix().getString()))),
					"the [Claude] prefix isn't on a line of its own before the grid");
				check(!context.computeOnClient(mc -> chatHas(mc, "Late answer from the old world")), "the old world's answer arrived");
				check(!FakeClaude.calls(folder).getLast().contains("--resume"), "the new world continued the old conversation");
				context.waitTicks(10);
				openChat(context);
				context.takeScreenshot("claudechat-runs");
				context.getInput().pressKey(KEY_ESCAPE);
			}
		} finally {
			context.runOnClient(mc -> {
				config.claudePath = savedPath;
				config.timeoutSeconds = savedTimeout;
				config.permissions = PermissionTier.READ_ONLY;
				config.save();
			});
		}
	}

	/** The fake's next script; clears what the last one left for the test. */
	private static void scenario(Path folder, String scenario) throws IOException {
		Files.deleteIfExists(folder.resolve("go"));
		Files.deleteIfExists(folder.resolve("started.txt"));
		Files.writeString(folder.resolve("scenario.txt"), scenario);
	}

	private static void go(Path folder) throws IOException {
		Files.writeString(folder.resolve("go"), "");
	}

	/** Waits for the fake to start; returns its pid and its child's. */
	private static List<Long> awaitStarted(ClientGameTestContext context, Path folder) {
		context.waitFor(mc -> !FakeClaude.startedPids(folder).isEmpty(), 30 * SECONDS);
		return FakeClaude.startedPids(folder);
	}

	private static void awaitAnswer(ClientGameTestContext context) {
		context.waitFor(mc -> !ClaudeChatClient.isBusy(), 30 * SECONDS);
		context.waitTicks(2);
	}

	private static void awaitDead(ClientGameTestContext context, List<Long> pids) {
		context.waitFor(mc -> pids.stream().noneMatch(pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)),
			10 * SECONDS);
	}

	/** The status of an MCP ping with this Authorization header. */
	private static int httpStatus(String url, String authorization) {
		try {
			return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url))
				.header("Authorization", authorization)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"ping\"}"))
				.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	/** Runs the command through run_command, waits for the request in chat, screenshots it and clicks Allow or Deny. */
	private static JsonObject withApproval(ClientGameTestContext context, String command, boolean allow, String screenshot) {
		CompletableFuture<JsonObject> result = CompletableFuture.supplyAsync(() -> callRunCommand(command));
		answer(context, allow, screenshot);
		context.waitFor(mc -> result.isDone(), 30 * SECONDS);
		JsonObject r = result.join();
		LOGGER.info("run_command {} ({}) -> {}", command, allow ? "allowed" : "denied", r);
		return r;
	}

	/** Waits for a new approval request in chat, screenshots it (unless {@code screenshot} is null) and clicks. */
	private static void answer(ClientGameTestContext context, boolean allow, String screenshot) {
		String before = context.computeOnClient(mc -> newestButton(mc, "Allow"));
		context.waitFor(mc -> !Objects.equals(newestButton(mc, "Allow"), before), 10 * SECONDS);
		context.waitTicks(5);
		if (screenshot != null) {
			openChat(context);
			context.takeScreenshot(screenshot);
			context.getInput().pressKey(KEY_ESCAPE);
		}
		click(context, context.computeOnClient(mc -> newestButton(mc, allow ? "Allow" : "Deny")));
	}

	/** The command behind the newest "[label]" button in chat, or null if there's none. Client thread. */
	private static String newestButton(Minecraft mc, String label) {
		for (GuiMessage message : chatMessages(mc)) {
			Optional<String> command = message.content().visit((style, text) ->
				text.equals("[" + label + "]") && style.getClickEvent() instanceof ClickEvent.RunCommand(String run)
					? Optional.of(run) : Optional.empty(), Style.EMPTY);
			if (command.isPresent()) {
				return command.get();
			}
		}
		return null;
	}

	/** Does any chat line contain {@code text}? Client thread. */
	private static boolean chatHas(Minecraft mc, String text) {
		return chatMessages(mc).stream().anyMatch(message -> message.content().getString().contains(text));
	}

	/** Every chat line, newest first. */
	private static List<GuiMessage> chatMessages(Minecraft mc) {
		return ((ChatComponentAccessor) mc.gui.hud.getChat()).claudechat$allMessages();
	}

	/** Clicks a chat button's command as chat does. Without Fabric's client command hook this would go to the server. */
	private static void click(ClientGameTestContext context, String command) {
		check(command != null, "no button to click");
		context.runOnClient(mc -> mc.player.connection.sendUnattendedCommand(Commands.trimOptionalPrefix(command), null));
	}

	/** The MCP access the test's direct tool calls use, as a question with {@code policy} would have it. */
	private static void usePolicy(Policy policy) {
		if (grant != null) {
			grant.close();
		}
		grant = ClaudeChatClient.mcp().grant(new Tool.Context(policy, new Approvals(note -> { })));
	}

	private static JsonObject runCommand(ClientGameTestContext context, String command) {
		CompletableFuture<JsonObject> result = CompletableFuture.supplyAsync(() -> callRunCommand(command));
		context.waitFor(mc -> result.isDone(), 30 * SECONDS);
		JsonObject r = result.join();
		LOGGER.info("run_command {} -> {}", command, r);
		return r;
	}

	/** run_command's result over MCP, or {"error": the error text Claude gets} when the call fails. */
	private static JsonObject callRunCommand(String command) {
		JsonObject args = new JsonObject();
		args.addProperty("command", command);
		JsonObject result = mcpCall("tools/call", "{\"name\": \"run_command\", \"arguments\": " + args + "}");
		String text = resultText(result);
		if (isError(result)) {
			JsonObject error = new JsonObject();
			error.addProperty("error", text);
			return error;
		}
		return JsonParser.parseString(text).getAsJsonObject();
	}

	private static boolean isError(JsonObject result) {
		return result.has("isError") && result.get("isError").getAsBoolean();
	}

	/** The text content of an MCP tool result: the data as JSON, or the error. */
	private static String resultText(JsonObject result) {
		return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
	}

	private static boolean hasDiamond(net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext server) {
		return server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().getInventory().contains(stack -> stack.is(Items.DIAMOND)));
	}

	/** Runs a world tool off the test thread (it waits on the server thread, which the test thread drives) and logs it. */
	/**
	 * The recipe model shared by grids and material lists, from the world's recipes: each kind is read into its own
	 * shape, and a trim recipe (no fixed result) draws as a row, which goes to chat for the screenshot.
	 */
	private static void checkRecipes(ClientGameTestContext context) {
		GameRecipes.Snapshot recipes = ClaudeChatClient.knowledge().recipes().snapshot();
		check(recipes.find(Identifier.withDefaultNamespace("crafter")).orElse(null) instanceof GameRecipe.Shaped,
			"crafter isn't a shaped recipe");
		check(recipes.find(Identifier.withDefaultNamespace("iron_ingot_from_smelting_raw_iron")).orElse(null) instanceof GameRecipe.Cooking,
			"raw iron smelting isn't a cooking recipe");
		check(recipes.find(Identifier.withDefaultNamespace("piston")).flatMap(GameRecipe::resultItem).map(item -> item.value() == Items.PISTON)
			.orElse(false), "no piston recipe");

		RecipeRenderer.Lines trim = context.computeOnClient(mc ->
			RecipeRenderer.render(recipes, Identifier.withDefaultNamespace("sentry_armor_trim_smithing_template_smithing_trim")));
		check(trim.grid(), "the trim recipe didn't draw: " + trim.lines());
		context.runOnClient(mc -> trim.lines().forEach(mc.gui.hud.getChat()::addClientSystemMessage));
		context.waitTicks(2);
	}

	/** How many pistons the world's piston recipe makes, as recipe grids and material lists see it; 0 without one. */
	private static int pistonCount() {
		return ClaudeChatClient.knowledge().recipes().snapshot().find(Identifier.withDefaultNamespace("piston"))
			.flatMap(GameRecipe::makes)
			.map(made -> made instanceof GameRecipe.Output.Stack stack ? stack.count() : 0)
			.orElse(0);
	}

	/** The zip pack's piston recipe replaces vanilla's for grids and material lists alike, and its folder comes first. */
	private static void checkPackOverrides(ClientGameTestContext context) {
		KnowledgeBase knowledge = ClaudeChatClient.knowledge();
		Identifier pistonId = Identifier.withDefaultNamespace("piston");

		Materials.Breakdown pistons = new Materials(knowledge).breakDown(Map.of(Materials.Material.item(pistonId), 2), Map.of());
		LOGGER.info("pistons with the pack: {}", pistons);
		check(pistons.crafts().size() == 1 && pistons.crafts().getFirst().recipe().id().equals(pistonId)
				&& pistons.crafts().getFirst().crafts() == 1,
			"the breakdown doesn't use the pack's piston recipe: " + pistons);
		check(pistons.remaining().get(Materials.Material.item(Identifier.withDefaultNamespace("dirt"))) == 1,
			"the pack's dirt isn't in the breakdown: " + pistons);

		GameRecipes.Snapshot recipes = knowledge.recipes().snapshot();
		RecipeRenderer.Lines grid = context.computeOnClient(mc -> RecipeRenderer.render(recipes, pistonId));
		String text = grid.lines().stream().map(Component::getString).reduce("", String::concat);
		check(grid.grid() && text.contains("2×"), "the piston grid doesn't show the pack's recipe: " + text);

		List<Path> roots = knowledge.dataRoots();
		LOGGER.info("data roots with the packs: {}", roots);
		check(roots.getFirst().toString().contains("datapacks") && roots.getLast().toString().contains("minecraft-"),
			"data roots aren't in the server's pack order: " + roots);
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	/**
	 * Builds scenes facing north from the player's position, calls look and checks what it saw and didn't see. Relative
	 * to the player, so it works in the superflat and the normal test world. The scenes cover what look must hide: what's
	 * behind opaque blocks, the back of a sign, a container's name, what's in the dark, past Blindness, or past the fog
	 * under water; and the third-person crosshair.
	 */
	private static void testLook(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		var p = singleplayer.getServer().computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().blockPosition());
		int x = p.getX();
		int y = p.getY();
		int z = p.getZ();
		testLookOcclusion(context, singleplayer, x, y, z);

		String question = System.getenv("CLAUDECHAT_LOOK_QUESTION");
		if (question != null && !question.isBlank()) {
			chat(context, "@claude " + question);
			context.waitTicks(5);
			context.waitFor(mc -> !ClaudeChatClient.isBusy(), 300 * SECONDS);
			context.waitTicks(10);
			openChat(context);
			context.takeScreenshot("claudechat-look-answer");
			context.getInput().pressKey(KEY_ESCAPE);
		}

		testLookThirdPerson(context);
		testLookDarkness(context, singleplayer, x, y, z);
		testLookBlindness(context, singleplayer);
		testLookUnderWater(context, singleplayer, x, y, z);
	}

	/**
	 * A stone wall with a glass window: the diamond blocks behind the window must be seen through glass; the gold block
	 * and the pig behind the wall must not be seen at all. In front of the wall, a sign whose back text faces away and a
	 * chest whose name isn't shown in the world.
	 */
	private static void testLookOcclusion(ClientGameTestContext context, TestSingleplayerContext singleplayer, int x, int y, int z) {
		runLookScene(context, singleplayer, List.of(
			"fill " + (x - 3) + " " + y + " " + (z - 4) + " " + (x + 3) + " " + (y + 4) + " " + (z - 4) + " stone",
			"fill " + (x + 1) + " " + (y + 1) + " " + (z - 4) + " " + (x + 2) + " " + (y + 2) + " " + (z - 4) + " glass",
			"fill " + (x + 1) + " " + (y + 1) + " " + (z - 6) + " " + (x + 2) + " " + (y + 2) + " " + (z - 6) + " diamond_block",
			"setblock " + (x - 2) + " " + (y + 2) + " " + (z - 6) + " gold_block",
			"setblock " + (x - 2) + " " + y + " " + (z - 2) + " oak_sign[rotation=0]{front_text:{messages:[\"Hello\",\"Claude\",\"\",\"\"]},"
				+ "back_text:{messages:[\"Behind\",\"\",\"\",\"\"]}}",
			"setblock " + x + " " + y + " " + (z - 3) + " chest[facing=south]{CustomName:\"Secret\"}",
			"setblock " + (x + 2) + " " + y + " " + (z - 3) + " torch",
			"summon pig " + (x - 1 + 0.5) + " " + y + " " + (z - 2 + 0.5) + " {NoAI:1b,CustomName:\"Visible\"}",
			"summon pig " + (x + 0.5) + " " + y + " " + (z - 7 + 0.5) + " {NoAI:1b,CustomName:\"Hidden\"}"), x, y, z);
		context.takeScreenshot("claudechat-look");

		JsonObject look = callLook(context, "claudechat-look-tool");
		String blocks = look.getAsJsonArray("blocks").toString();
		String entities = look.getAsJsonArray("entities").toString();
		String details = String.valueOf(look.get("block_details"));
		check("stone".equals(stringOrNull(look.getAsJsonObject("crosshair"), "block")),
			"crosshair isn't on the stone wall: " + look.get("crosshair"));
		check(blocks.matches(".*\"diamond_block\"[^}]*\"seen_through\":\"glass.*"), "diamond blocks not seen through the glass: " + blocks);
		check(blocks.matches(".*\"block\":\"glass\",\"screen_share\":\"[0-9u].*"), "the glass has no screen share: " + blocks);
		check(!blocks.contains("gold_block"), "saw the gold block behind the wall");
		check(blocks.contains("\"torch\""), "missed the torch");
		check(blocks.contains("\"chest\"") && !look.toString().contains("Secret"), "chest missing, or its hidden name shown: " + blocks);
		check(details.contains("Hello / Claude") && !details.contains("Behind"), "wrong sign text: " + details);
		check(entities.contains("Visible") && !entities.contains("Hidden"), "wrong pigs: " + entities);
		check(entities.contains("\"in_image\"") && blocks.matches(".*\"diamond_block\"[^}]*\"in_image\".*"), "image positions missing");
	}

	/** In third person there's no crosshair: look must say so, and the player's own body is in view. */
	private static void testLookThirdPerson(ClientGameTestContext context) {
		context.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK));
		context.waitTicks(5);
		JsonObject look = callLook(context, "claudechat-look-third-person");
		context.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
		context.waitTicks(5);

		check("third person".equals(stringOrNull(look.getAsJsonObject("camera"), "perspective")), "not third person: " + look.get("camera"));
		check(look.getAsJsonObject("crosshair").has("note"), "no third-person note on the crosshair: " + look.get("crosshair"));
		check(!look.getAsJsonObject("screenshot").has("crosshair"), "third-person screenshot claims a crosshair");
		check(look.getAsJsonArray("entities").toString().contains("\"type\":\"player\""), "own body not seen in third person");
	}

	/**
	 * A sealed stone box with a tinted glass front, which keeps out sky light: the emerald block and the pig inside are
	 * too dark to see until a glowstone lights them.
	 */
	private static void testLookDarkness(ClientGameTestContext context, TestSingleplayerContext singleplayer, int x, int y, int z) {
		runLookScene(context, singleplayer, List.of(
			"fill " + (x - 2) + " " + (y - 1) + " " + (z - 8) + " " + (x + 2) + " " + (y + 3) + " " + (z - 4) + " stone",
			"fill " + (x - 1) + " " + y + " " + (z - 7) + " " + (x + 1) + " " + (y + 2) + " " + (z - 5) + " air",
			"fill " + (x - 1) + " " + y + " " + (z - 4) + " " + (x + 1) + " " + (y + 2) + " " + (z - 4) + " tinted_glass",
			"setblock " + x + " " + (y + 1) + " " + (z - 7) + " emerald_block",
			"summon pig " + (x + 0.5) + " " + y + " " + (z - 5 + 0.5) + " {NoAI:1b,CustomName:\"Lurker\"}"), x, y, z);

		JsonObject dark = callLook(context, "claudechat-look-dark");
		String darkBlocks = dark.getAsJsonArray("blocks").toString();
		check(darkBlocks.contains("\"tinted_glass\"") && !darkBlocks.contains("emerald_block"), "saw into the dark box: " + darkBlocks);
		check(dark.has("too_dark"), "no too_dark share: " + dark);
		check(!dark.getAsJsonArray("entities").toString().contains("Lurker"), "saw the pig in the dark");

		singleplayer.getServer().runCommand("setblock " + (x - 1) + " " + (y + 2) + " " + (z - 7) + " glowstone");
		context.waitTicks(20);
		singleplayer.getConnection().waitForChunksRender();
		JsonObject lit = callLook(context, "claudechat-look-lit");
		check(lit.getAsJsonArray("blocks").toString().matches(".*\"emerald_block\"[^}]*\"seen_through\":\"tinted_glass.*"),
			"emerald block not seen once lit: " + lit.get("blocks"));
		check(lit.getAsJsonArray("entities").toString().contains("Lurker"), "pig not seen once lit: " + lit.get("entities"));
	}

	/** Blindness fogs everything past 5 blocks: the box's front (3.5 blocks away) shows, the emerald block (6.5) doesn't. */
	private static void testLookBlindness(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		singleplayer.getServer().runCommand("effect give @p minecraft:blindness 60 0 true");
		context.waitTicks(10);
		JsonObject look = callLook(context, "claudechat-look-blind");
		singleplayer.getServer().runCommand("effect clear @p minecraft:blindness");
		context.waitTicks(10);

		String blocks = look.getAsJsonArray("blocks").toString();
		JsonObject camera = look.getAsJsonObject("camera");
		check(blocks.contains("\"tinted_glass\"") && !blocks.contains("emerald_block"), "Blindness didn't limit the view: " + blocks);
		check(camera.get("ray_range").getAsInt() <= 5 && "Blindness or Darkness effect".equals(stringOrNull(camera, "ray_range_limited_by")),
			"ray range not limited by Blindness: " + camera);
	}

	/** With the camera under water, the water's fog limits how far rays go. */
	private static void testLookUnderWater(ClientGameTestContext context, TestSingleplayerContext singleplayer, int x, int y, int z) {
		singleplayer.getServer().runCommand("fill " + (x - 1) + " " + y + " " + (z - 1) + " " + (x + 1) + " " + (y + 2) + " " + (z + 1) + " water");
		context.waitTicks(10);
		JsonObject look = callLook(context, "claudechat-look-water");
		singleplayer.getServer().runCommand("fill " + (x - 10) + " " + (y - 1) + " " + (z - 10) + " " + (x + 10) + " " + (y + 3) + " " + (z + 10)
			+ " air replace water");
		context.waitTicks(10);

		JsonObject camera = look.getAsJsonObject("camera");
		check("water".equals(stringOrNull(camera, "camera_in")) && "water".equals(stringOrNull(camera, "ray_range_limited_by"))
			&& camera.get("ray_range").getAsInt() <= 96, "water fog didn't limit the view: " + camera);
	}

	/** Clears the space in front of the player, builds a scene, and faces the player north at it. */
	private static void runLookScene(ClientGameTestContext context, TestSingleplayerContext singleplayer, List<String> scene, int x, int y, int z) {
		List<String> commands = new ArrayList<>(List.of(
			"fill " + (x - 6) + " " + y + " " + (z - 9) + " " + (x + 6) + " " + (y + 8) + " " + (z + 2) + " air",
			"fill " + (x - 6) + " " + (y - 1) + " " + (z - 9) + " " + (x + 6) + " " + (y - 1) + " " + (z + 2) + " grass_block",
			"kill @e[type=!player]"));
		commands.addAll(scene);
		commands.add("tp @p " + (x + 0.5) + " " + y + " " + (z + 0.5) + " 180 0");
		commands.forEach(singleplayer.getServer()::runCommand);
		context.waitTicks(20);
		singleplayer.getConnection().waitForChunksRender();
	}

	/**
	 * Calls look over MCP rather than through logTool, to keep its screenshot (the image content after the data),
	 * saved next to the test's own as {@code <name>.png}. Returns the data.
	 */
	private static JsonObject callLook(ClientGameTestContext context, String name) {
		long start = System.nanoTime();
		CompletableFuture<JsonObject> result = CompletableFuture.supplyAsync(() -> mcpCall("tools/call", "{\"name\": \"look\", \"arguments\": {}}"));
		context.waitFor(mc -> result.isDone(), 60 * SECONDS);
		check(!isError(result.join()), "look failed: " + result.join());
		JsonObject look = JsonParser.parseString(resultText(result.join())).getAsJsonObject();
		LOGGER.info("tool look {} ({} ms) -> {}", name, (System.nanoTime() - start) / 1_000_000, look);
		var content = result.join().getAsJsonArray("content");
		check(content.size() == 2 && "image/png".equals(content.get(1).getAsJsonObject().get("mimeType").getAsString()),
			"no screenshot: " + look.get("screenshot"));
		byte[] png = Base64.getDecoder().decode(content.get(1).getAsJsonObject().get("data").getAsString());
		check(png.length > 1000, "screenshot too small: " + png.length + " bytes");
		try {
			Files.write(FabricLoader.getInstance().getGameDir().resolve("screenshots/" + name + ".png"), png);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		return look;
	}

	private static String stringOrNull(JsonObject object, String key) {
		return object != null && object.has(key) ? object.get(key).getAsString() : null;
	}

	/** The message a world tool call fails with; fails the test if the call succeeds. */
	private static String toolError(ClientGameTestContext context, String name, String args) {
		try {
			logTool(context, name, args);
		} catch (RuntimeException e) {
			Throwable cause = e;
			while (cause.getCause() != null) {
				cause = cause.getCause();
			}
			LOGGER.info("tool {} {} -> error: {}", name, args, cause.getMessage());
			return String.valueOf(cause.getMessage());
		}
		throw new AssertionError(name + " " + args + " should have failed");
	}

	/**
	 * Saves claudechat:test_tower the way a structure block does: a 1×3×1 column above the player with {@code height}
	 * gold blocks from the bottom, air above them.
	 */
	private static void saveTestTower(TestSingleplayerContext singleplayer, int height) {
		boolean saved = singleplayer.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			BlockPos base = server.getPlayerList().getPlayers().getFirst().blockPosition().offset(3, 20, 3);
			for (int y = 0; y < 3; y++) {
				level.setBlockAndUpdate(base.above(y), y < height ? Blocks.GOLD_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState());
			}
			Identifier id = Identifier.parse("claudechat:test_tower");
			server.getStructureTemplateManager().getOrCreate(id).fillFromWorld(level, base, new Vec3i(1, 3, 1), false, List.of(Blocks.STRUCTURE_VOID));
			return server.getStructureTemplateManager().save(id);
		});
		check(saved, "couldn't save the test tower");
	}

	/**
	 * Writes two templates into the world's generated folder: claudechat:wide_slab, 2049×1×2048 with one emerald block
	 * (more positions than the index reads in parallel), and claudechat:bomb, whose only tag claims a 1 GiB byte array
	 * (the game's own reader would allocate it before finding the data missing).
	 */
	private static void writeOversizedTemplates(Path generated) {
		try {
			Path structures = Files.createDirectories(generated.resolve("claudechat/structure"));
			CompoundTag wide = new CompoundTag();
			NbtUtils.addCurrentDataVersion(wide);
			ListTag size = new ListTag();
			List.of(2049, 1, 2048).forEach(side -> size.add(IntTag.valueOf(side)));
			wide.put("size", size);
			ListTag palette = new ListTag();
			palette.add(NbtUtils.writeBlockState(Blocks.EMERALD_BLOCK.defaultBlockState()));
			wide.put("palette", palette);
			CompoundTag block = new CompoundTag();
			ListTag origin = new ListTag();
			List.of(0, 0, 0).forEach(coordinate -> origin.add(IntTag.valueOf(coordinate)));
			block.put("pos", origin);
			block.putInt("state", 0);
			ListTag blocks = new ListTag();
			blocks.add(block);
			wide.put("blocks", blocks);
			wide.put("entities", new ListTag());
			NbtIo.writeCompressed(wide, structures.resolve("wide_slab.nbt"));

			try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(structures.resolve("bomb.nbt"))))) {
				out.writeByte(Tag.TAG_COMPOUND);
				out.writeUTF("");
				out.writeByte(Tag.TAG_BYTE_ARRAY);
				out.writeUTF("data");
				out.writeInt(1 << 30);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Runs /reload and waits until the server has its new resource manager. */
	private static void reloadResources(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		Object before = singleplayer.getServer().computeOnServer(MinecraftServer::getResourceManager);
		singleplayer.getServer().runCommand("reload");

		for (int tick = 0; tick < 60 * SECONDS; tick++) {
			if (singleplayer.getServer().computeOnServer(MinecraftServer::getResourceManager) != before) {
				return;
			}

			context.waitTick();
		}

		throw new AssertionError("/reload didn't finish");
	}

	/**
	 * Calls a tool over MCP as Claude does, off the test thread (world tools wait on the server thread, which the test
	 * thread drives), and logs it. Throws with the error text Claude would get if the call fails.
	 */
	private static JsonObject logTool(ClientGameTestContext context, String name, String args) {
		long start = System.nanoTime();
		CompletableFuture<JsonObject> result = CompletableFuture.supplyAsync(() ->
			mcpCall("tools/call", "{\"name\": \"" + name + "\", \"arguments\": " + args + "}"));
		context.waitFor(mc -> result.isDone(), 120 * SECONDS);
		String text = resultText(result.join());
		if (isError(result.join())) {
			throw new IllegalStateException(text);
		}
		JsonElement json = JsonParser.parseString(text);
		LOGGER.info("tool {} {} ({} ms) -> {}", name, args, (System.nanoTime() - start) / 1_000_000, json);
		return json.isJsonObject() ? json.getAsJsonObject() : new JsonObject();
	}

	/**
	 * Clicks a [[waypoint:...]] button without the mouse: runs its command as Screen.clickCommandAction does, presses
	 * Confirm in Xaero's screen, then waits for a new waypoint line containing {@code coords} in Xaero's {@code folder}.
	 * The test client's xaero folder survives between runs, so lines from earlier runs are counted first.
	 */
	private static void addWaypoint(ClientGameTestContext context, String tag, String folder, String coords, String label) {
		Path minimap = FabricLoader.getInstance().getGameDir().resolve("xaero/minimap");
		int before = savedWaypoints(minimap, folder, coords).size();
		// A tag without a dimension gets the player's, as when they ask; Minecraft.getInstance() is off limits on the test thread.
		ClickEvent click = context.computeOnClient(mc -> Waypoints.button(tag, GameContext.dimension(mc))).getStyle().getClickEvent();
		check(click instanceof ClickEvent.RunCommand, "no click command for " + tag);
		String command = ((ClickEvent.RunCommand) click).command();
		LOGGER.info("waypoint {} -> {}", tag, command);
		context.runOnClient(mc -> mc.player.connection.sendUnattendedCommand(Commands.trimOptionalPrefix(command), null));
		context.waitFor(mc -> isXaeroAddScreen(mc.gui.screen()), 5 * SECONDS);
		context.waitTicks(5);
		context.takeScreenshot("claudechat-waypoint-" + label);
		context.runOnClient(mc -> {
			String confirm = I18n.get("gui.xaero_confirm");
			AbstractWidget button = mc.gui.screen().children().stream()
				.filter(w -> w instanceof AbstractWidget widget && widget.getMessage().getString().equals(confirm))
				.map(AbstractWidget.class::cast)
				.findFirst().orElseThrow(() -> new AssertionError("no Confirm button in Xaero's screen"));
			button.onClick(new MouseButtonEvent(0, 0, new MouseButtonInfo(0, 0)), false);
		});
		context.waitFor(mc -> !isXaeroAddScreen(mc.gui.screen()), 5 * SECONDS);
		context.waitFor(mc -> savedWaypoints(minimap, folder, coords).size() > before, 10 * SECONDS);
		LOGGER.info("waypoint saved in {}: {}", folder, savedWaypoints(minimap, folder, coords).getLast());
	}

	private static boolean isXaeroAddScreen(Screen screen) {
		return screen != null && screen.getClass().getName().equals("xaero.common.gui.GuiAddWaypoint");
	}

	private static List<String> savedWaypoints(Path minimap, String folder, String coords) {
		List<String> found = new ArrayList<>();
		if (!Files.isDirectory(minimap)) {
			return found;
		}
		try (var files = Files.walk(minimap)) {
			for (Path file : files.filter(f -> f.getParent().getFileName().toString().equals(folder) && f.toString().endsWith(".txt")).toList()) {
				for (String line : Files.readAllLines(file)) {
					if (line.startsWith("waypoint:") && line.contains(coords)) {
						found.add(line);
					}
				}
			}
		} catch (IOException e) {
			// Xaero may be writing the file; the caller polls again.
		}
		return found;
	}

	/** One JSON-RPC request to the mod's MCP server, with the URL and token claude gets; returns its result. */
	private static JsonObject mcpCall(String method, String params) {
		return mcpResponse(method, params).getAsJsonObject("result");
	}

	/** One JSON-RPC request to the mod's MCP server; returns the whole response, result or error. */
	private static JsonObject mcpResponse(String method, String params) {
		JsonObject server = JsonParser.parseString(grant.claudeConfig()).getAsJsonObject()
			.getAsJsonObject("mcpServers").getAsJsonObject(McpServer.NAME);
		String body = "{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"" + method + "\", \"params\": " + params + "}";
		try {
			HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(server.get("url").getAsString()))
				.header("Authorization", server.getAsJsonObject("headers").get("Authorization").getAsString())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build(), HttpResponse.BodyHandlers.ofString());
			return JsonParser.parseString(response.body()).getAsJsonObject();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	private static List<String> mcpToolNames() {
		List<String> names = new ArrayList<>();
		mcpCall("tools/list", "{}").getAsJsonArray("tools").forEach(t -> names.add(t.getAsJsonObject().get("name").getAsString()));
		return names;
	}

	/** A find_structures result's pieces must be what generating its start chunk for real produces. */
	private static void checkPieces(TestSingleplayerContext singleplayer, ResourceKey<Level> dimension, JsonObject result) {
		String id = result.get("structure").getAsString();
		int chunkX = result.get("x").getAsInt() >> 4;
		int chunkZ = result.get("z").getAsInt() >> 4;
		Map<String, Integer> generated = singleplayer.getServer().computeOnServer(s -> {
			var structure = s.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValue(Identifier.parse(id));
			return StructureFinder.pieces(s.getLevel(dimension).getChunk(chunkX, chunkZ, ChunkStatus.STRUCTURE_STARTS).getStartForStructure(structure));
		});
		Map<String, Integer> reported = new TreeMap<>();
		result.getAsJsonObject("pieces").entrySet().forEach(e -> reported.put(e.getKey(), e.getValue().getAsInt()));
		LOGGER.info("pieces of {} at {} {}: {}", id, result.get("x"), result.get("z"), reported);
		check(reported.equals(generated), id + " pieces differ from generation: tool " + reported + ", generated " + generated);
	}

	private static List<String> readLines(Path file) {
		try {
			return Files.readAllLines(file);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
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
		// Two pistons from cobblestone, redstone and dirt, so both the count and an ingredient show the override.
		String piston = """
			{"type": "minecraft:crafting_shapeless", "ingredients": ["minecraft:cobblestone", "minecraft:redstone", "minecraft:dirt"],
			 "result": {"id": "minecraft:piston", "count": 2}}
			""";
		try {
			Path folder = datapacks.resolve("Secret Vaults");
			Files.createDirectories(folder.resolve("data/claudetest/loot_table/chests"));
			Files.writeString(folder.resolve("pack.mcmeta"), mcmeta.formatted("Secret vaults", format.major(), format.minor(), format.major(), format.minor()));
			Files.writeString(folder.resolve("data/claudetest/loot_table/chests/secret_vault.json"), vault);

			try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(datapacks.resolve(OVERRIDE_PACK.substring("file/".length()))))) {
				zip.putNextEntry(new ZipEntry("pack.mcmeta"));
				zip.write(mcmeta.formatted("Better dungeons", format.major(), format.minor(), format.major(), format.minor()).getBytes(StandardCharsets.UTF_8));
				zip.putNextEntry(new ZipEntry("data/minecraft/loot_table/chests/simple_dungeon.json"));
				zip.write(dungeon.getBytes(StandardCharsets.UTF_8));
				zip.putNextEntry(new ZipEntry("data/minecraft/recipe/piston.json"));
				zip.write(piston.getBytes(StandardCharsets.UTF_8));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Copies the folder's files, subfolders included. */
	private static void copySchematics(Path from, Path to) {
		try (var files = Files.walk(from)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				Path target = to.resolve(from.relativize(file).toString());
				Files.createDirectories(target.getParent());
				Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
