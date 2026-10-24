package claudechat.test;

import claudechat.Provenance;
import claudechat.StructureFinder;
import claudechat.ToolFormat;
import claudechat.Visit;
import claudechat.WorldIndex;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static claudechat.test.Game.LOGGER;
import static claudechat.test.Game.SECONDS;
import static claudechat.test.Game.check;

/**
 * The world tools against facts this step places: containers near the player (live) and far away (only in the save),
 * a container in the Nether, an ender chest, a named wolf, spawners and respawn anchors live and saved; one position
 * saved, predicted and generated; structure footprints and pieces against real generation; biomes against /locate;
 * and the visited filter, with the time the game doesn't save kept by the world index.
 */
final class WorldStep {
	/** Far enough from spawn that only the save has it once its forceload is removed. */
	private static final int FAR = 2000;
	private static final int UNVISITED_LAND = 3000;

	private WorldStep() {
	}

	static void run(Game game) {
		BlockPos player = game.playerPosition();
		placeFacts(game, player);
		containers(game, player);
		entitiesAndPlayer(game);
		blocks(game, player);
		arguments(game);
		location(game);
		JsonObject fortress = fortresses(game);
		biomes(game);
		JsonObject village = pieces(game, fortress);
		visits(game, village);

		// Asking Claude uses the signed-in account, so only in live runs.
		if (game.options.live()) {
			game.askClaude("where's the nearest village I haven't explored yet? where did I put my netherite? and find me a nether"
				+ " fortress in a warped forest", "claudechat-world-answer").requireAnswer();
		}
	}

	/**
	 * The facts, near {@code player} (live) and far away (saved only). A normal world has dungeons and other chests of
	 * its own, so the checks find these by position or name.
	 */
	private static void placeFacts(Game game, BlockPos player) {
		game.command("setblock " + position(player.east(3)) + " minecraft:chest{CustomName:\"Loot\",Items:[{Slot:0b,id:\"minecraft:diamond\",count:64},{Slot:1b,id:\"minecraft:iron_ingot\",count:12}]}");
		game.command("setblock " + position(player.west(3)) + " minecraft:barrel{Items:[{Slot:4b,id:\"minecraft:shulker_box\",count:1,components:{\"minecraft:container\":[{slot:0,item:{id:\"minecraft:emerald\",count:32}}]}}]}");
		game.command("setblock " + position(player.south(4)) + " minecraft:chest{LootTable:\"minecraft:chests/simple_dungeon\"}");
		game.command("execute at @p run summon minecraft:wolf ~2 ~ ~2 {CustomName:\"Rex\"}");
		game.command("give @p minecraft:diamond_sword[enchantments={\"minecraft:sharpness\":5,\"minecraft:mending\":1}]");
		game.command("item replace entity @p enderchest.0 with minecraft:emerald 5");
		game.command("forceload add " + FAR + " " + FAR);
		game.context.waitTicks(20);
		game.command("setblock " + FAR + " 100 " + FAR + " minecraft:barrel{Items:[{Slot:0b,id:\"minecraft:netherite_ingot\",count:3}]}");
		game.command("setblock " + FAR + " 100 " + (FAR + 2) + " minecraft:spawner{SpawnData:{entity:{id:\"minecraft:skeleton\"}}}");
		game.command("setblock " + (FAR + 2) + " 100 " + FAR + " minecraft:respawn_anchor[charges=4]");
		game.command("setblock " + position(player.offset(5, 0, 5)) + " minecraft:spawner{SpawnData:{entity:{id:\"minecraft:zombie\"}}}");
		game.command("setblock " + position(player.offset(-5, 0, 5)) + " minecraft:respawn_anchor[charges=1]");
		game.command("forceload remove " + FAR + " " + FAR);
		game.command("execute in minecraft:the_nether run forceload add 0 0");
		game.context.waitTicks(20);
		game.command("execute in minecraft:the_nether run setblock 3 70 3 minecraft:chest{CustomName:\"Nether Stash\",Items:[{Slot:0b,id:\"minecraft:blaze_rod\",count:7}]}");
		game.command("execute in minecraft:the_nether run forceload remove 0 0");
		game.save();
		game.context.waitTicks(40);
	}

	/**
	 * Containers by what they hold, live and saved, including inside a shulker box, in the ender chest, by loot table,
	 * and in another dimension: found with no distance limit, and when its dimension is asked for by either name, while
	 * a distance limit keeps the search in the player's dimension.
	 */
	private static void containers(Game game, BlockPos player) {
		JsonObject loot = only(game.tool("search_containers", "{\"query\": \"minecraft:diamond\"}"), "name", "Loot");
		check(Json.is(loot, "data", Provenance.LIVE.label()) && contents(loot).contains(item(64, "diamond", "Diamond")),
			"the live chest's diamonds: " + loot);

		JsonObject netherite = at(game.tool("search_containers", "{\"query\": \"netherite\"}"), new BlockPos(FAR, 100, FAR));
		check(Json.is(netherite, "what", "barrel") && Json.is(netherite, "data", Provenance.SAVED.label())
			&& contents(netherite).contains(item(3, "netherite_ingot", "Netherite Ingot")), "the saved barrel's netherite: " + netherite);

		JsonObject emeralds = game.tool("search_containers", "{\"query\": \"emerald\"}");
		JsonObject barrel = at(emeralds, player.west(3));
		JsonObject shulker = Json.objects(barrel.getAsJsonArray("contents")).getFirst();
		check(Json.is(shulker, "item", item(1, "shulker_box", "Shulker Box"))
			&& Json.strings(shulker.getAsJsonArray("contains")).contains(item(32, "emerald", "Emerald")), "32 emeralds in a shulker in a barrel: " + barrel);
		JsonObject enderChest = Json.objects(emeralds.getAsJsonArray("results")).stream()
			.filter(result -> result.get("what").getAsString().startsWith("ender chest of ")).findFirst()
			.orElseThrow(() -> new AssertionError("the ender chest emerald wasn't found: " + emeralds));
		check(contents(enderChest).contains(item(5, "emerald", "Emerald")), "the ender chest's emeralds: " + enderChest);

		JsonObject dungeon = at(game.tool("search_containers", "{\"query\": \"simple_dungeon\"}"), player.south(4));
		check(Json.is(dungeon, "unopened_loot_table", "chests/simple_dungeon"), "the unopened loot chest: " + dungeon);

		for (String args : List.of("{\"query\": \"blaze_rod\"}", "{\"query\": \"blaze_rod\", \"dimension\": \"the_nether\"}",
			"{\"query\": \"blaze_rod\", \"dimension\": \"minecraft:the_nether\"}")) {
			JsonObject stash = only(game.tool("search_containers", args), "name", "Nether Stash");
			check(Json.is(stash, "dimension", "the_nether") && contents(stash).contains(item(7, "blaze_rod", "Blaze Rod")),
				"Nether chest not found by " + args + ": " + stash);
		}
		JsonObject nearby = game.tool("search_containers", "{\"query\": \"blaze_rod\", \"max_distance\": 100}");
		check(nearby.getAsJsonArray("results").isEmpty(), "max_distance searched another dimension: " + nearby);
	}

	private static void entitiesAndPlayer(Game game) {
		JsonObject rex = only(game.tool("search_entities", "{\"query\": \"rex\"}"), "name", "Rex");
		check(rex.get("what").getAsString().endsWith("wolf"), "Rex isn't a wolf: " + rex);

		JsonObject host = Json.objects(game.toolArray("get_player", "{}")).getFirst();
		check(Json.strings(host.getAsJsonArray("inventory")).stream().anyMatch(stack -> stack.startsWith(item(1, "diamond_sword", "Diamond Sword") + " {")
			&& stack.contains("Sharpness V") && stack.contains("Mending")), "the enchanted sword isn't in the inventory: " + host.get("inventory"));
		check(Json.strings(host.getAsJsonArray("ender_chest")).contains(item(5, "emerald", "Emerald")), "the ender chest: " + host.get("ender_chest"));
	}

	/**
	 * The zombie spawner and charge-1 anchor are live nearby; the skeleton spawner and full anchor only saved. Dungeons
	 * near spawn have spawners too, so the limit leaves room for them.
	 */
	private static void blocks(Game game, BlockPos player) {
		JsonObject spawners = game.tool("find_blocks", "{\"block\": \"spawner\", \"limit\": 50}");
		JsonObject zombie = at(spawners, player.offset(5, 0, 5));
		JsonObject skeleton = at(spawners, new BlockPos(FAR, 100, FAR + 2));
		check(Json.is(zombie, "spawns", "zombie") && Json.is(zombie, "data", Provenance.LIVE.label())
			&& Json.is(skeleton, "spawns", "skeleton") && Json.is(skeleton, "data", Provenance.SAVED.label()), "live and saved spawners: " + spawners);

		JsonObject skeletons = game.tool("find_blocks", "{\"block\": \"spawner\", \"data\": \"skeleton\", \"limit\": 50}");
		check(Json.objects(skeletons.getAsJsonArray("results")).stream().allMatch(found -> Json.is(found, "spawns", "skeleton"))
			&& Json.is(at(skeletons, new BlockPos(FAR, 100, FAR + 2)), "spawns", "skeleton"), "the data filter: " + skeletons);

		JsonArray anchors = game.tool("find_blocks", "{\"block\": \"respawn anchor\"}").getAsJsonArray("results");
		check(anchors.size() == 2 && Json.objects(anchors).stream().map(WorldStep::charges).sorted().toList().equals(List.of("1", "4")),
			"both anchors, by their charges: " + anchors);
		JsonArray full = game.tool("find_blocks", "{\"block\": \"minecraft:respawn_anchor\", \"properties\": {\"charges\": \"4\"}}").getAsJsonArray("results");
		check(full.size() == 1 && charges(full.get(0).getAsJsonObject()).equals("4") && Json.is(full.get(0).getAsJsonObject(), "data", Provenance.SAVED.label())
			&& full.get(0).getAsJsonObject().get("x").getAsInt() == FAR + 2, "the charges=4 anchor: " + full);

		JsonArray beds = game.tool("find_blocks", "{\"block\": \"#minecraft:beds\", \"dimension\": \"the_nether\"}").getAsJsonArray("results");
		check(Json.objects(beds).stream().allMatch(bed -> Json.is(bed, "dimension", "the_nether")), "a dimension filter let through " + beds);
	}

	/** Arguments are parsed, not defaulted: a wrong type or an empty required query is an error. */
	private static void arguments(Game game) {
		check(game.toolError("find_blocks", "{\"block\": \"spawner\", \"limit\": \"five\"}").contains("limit must be a whole number"),
			"a malformed limit was accepted");
		check(game.toolError("find_blocks", "{\"block\": \"spawner\", \"max_distance\": \"500 blocks\"}").contains("max_distance"),
			"a malformed max_distance was accepted");
		check(game.toolError("find_structures", "{\"structure\": \" \"}").contains("structure must not be empty"),
			"an empty structure query was accepted");
	}

	/** A saved position, a predicted one in the Nether, and one predicted then generated: the prediction's biome holds. */
	private static void location(Game game) {
		JsonObject saved = game.tool("inspect_location", "{\"x\": " + FAR + ", \"y\": 100, \"z\": " + (FAR + 2) + "}");
		check(Json.is(saved, "source", Provenance.SAVED.label()) && Json.is(saved, "block", "spawner") && Json.is(saved, "spawns", "skeleton"),
			"the saved spawner's position: " + saved);
		JsonObject nether = game.tool("inspect_location", "{\"x\": 20000, \"y\": 64, \"z\": 20000, \"dimension\": \"the_nether\"}");
		check(Json.is(nether, "source", Provenance.PREDICTED.label()) && Json.is(nether, "dimension", "the_nether"), "the Nether prediction: " + nether);

		JsonObject predicted = game.tool("inspect_location", "{\"x\": 20000, \"z\": 20000}");
		game.command("forceload add 20000 20000");
		game.context.waitTicks(40);
		JsonObject generated = game.tool("inspect_location", "{\"x\": 20000, \"z\": 20000}");
		game.command("forceload remove 20000 20000");
		check(Json.is(predicted, "source", Provenance.PREDICTED.label()) && !Json.is(generated, "source", Provenance.PREDICTED.label()),
			"prediction, then generation: " + predicted.get("source") + ", " + generated.get("source"));
		check(predicted.get("biome").equals(generated.get("biome")), "predicted biome " + predicted.get("biome") + " but generated " + generated.get("biome"));
	}

	/**
	 * Fortresses by biome, searched in the Nether from the Overworld. The footprint is built in memory from the seed;
	 * generating the start chunk for real must give the same bounding box. Returns the nearest in a warped forest.
	 */
	private static JsonObject fortresses(Game game) {
		check(!game.tool("find_structures", "{\"structure\": \"fortress\", \"limit\": 3}").getAsJsonArray("results").isEmpty(), "no fortress");
		List<JsonObject> warped = Json.objects(game.tool("find_structures", "{\"structure\": \"fortress\", \"biome\": \"warped_forest\", \"limit\": 3}")
			.getAsJsonArray("results"));
		check(!warped.isEmpty(), "no fortress reaching into a warped forest");
		JsonObject fortress = warped.getFirst();
		check(fortress.getAsJsonObject("biomes").has("warped_forest"), "biome filter let through " + fortress);

		int fortressX = fortress.get("x").getAsInt();
		int fortressZ = fortress.get("z").getAsInt();
		int[] generated = game.server().computeOnServer(s -> {
			var start = s.getLevel(Level.NETHER).getChunk(fortressX >> 4, fortressZ >> 4, ChunkStatus.STRUCTURE_STARTS)
				.getStartForStructure(s.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.FORTRESS).value());
			BoundingBox box = start.getBoundingBox();
			return new int[] {box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()};
		});
		int[] reported = bounds(fortress.get("bounds"));
		LOGGER.info("fortress bounds: tool {}, generated {}", Arrays.toString(reported), Arrays.toString(generated));
		check(Arrays.equals(reported, generated), "fortress bounds differ from generation");

		JsonArray mostlyWarped = game.tool("find_structures", "{\"structure\": \"fortress\", \"biome\": \"warped_forest\", \"min_biome_share\": 75, \"limit\": 5}")
			.getAsJsonArray("results");
		for (JsonObject result : Json.objects(mostlyWarped)) {
			check(Json.percent(result.get("share_in_requested_biome")) >= 75, "min_biome_share let through " + result);
		}

		return fortress;
	}

	/**
	 * Biomes: from the Overworld, a warped forest is searched in the Nether. The nearest deep dark must be no farther
	 * than the one /locate biome finds on the same grid (it stops at the first hit in spiral order).
	 */
	private static void biomes(Game game) {
		JsonObject warpedBiome = game.tool("find_biomes", "{\"biome\": \"warped_forest\"}");
		check(Json.is(warpedBiome, "dimension", "the_nether") && !warpedBiome.getAsJsonArray("results").isEmpty(), "warped forest not searched in the Nether");
		JsonArray oceans = game.tool("find_biomes", "{\"biome\": \"#minecraft:is_ocean\", \"limit\": 5}").getAsJsonArray("results");
		check(!oceans.isEmpty() && oceans.size() <= 5, "ocean biomes by tag: " + oceans);

		JsonArray deepDark = game.tool("find_biomes", "{\"biome\": \"deep_dark\", \"limit\": 3}").getAsJsonArray("results");
		Double locateDistance = game.server().computeOnServer(s -> {
			var player = s.getPlayerList().getPlayers().getFirst();
			var pair = s.overworld().findClosestBiome3d(b -> b.is(Biomes.DEEP_DARK), player.blockPosition(), 6400, 32, 64);
			return pair == null ? null : Math.sqrt(Math.pow(pair.getFirst().getX() - player.getX(), 2) + Math.pow(pair.getFirst().getZ() - player.getZ(), 2));
		});
		LOGGER.info("deep dark: /locate {} blocks, find_biomes {}", locateDistance, deepDark.isEmpty() ? "none" : deepDark.get(0));
		check(locateDistance == null || (!deepDark.isEmpty()
			&& deepDark.get(0).getAsJsonObject().get("distance_blocks").getAsDouble() <= locateDistance + 2),
			"find_biomes' nearest deep dark is farther than /locate's");
	}

	/**
	 * Pieces, built in memory, must match real generation for three kinds of structure: hand-coded pieces (fortress),
	 * templates (end city) and jigsaw pools (village). End cities with a ship are searched in the End from the
	 * Overworld. Returns the nearest village.
	 */
	private static JsonObject pieces(Game game, JsonObject fortress) {
		JsonArray shipCities = game.tool("find_structures", "{\"structure\": \"end_city\", \"with_pieces\": [\"ship\"], \"limit\": 2}")
			.getAsJsonArray("results");
		check(!shipCities.isEmpty(), "no end city with a ship");
		for (JsonObject city : Json.objects(shipCities)) {
			check(city.getAsJsonObject("pieces").has("end_city/ship"), "with_pieces let through " + city);
			checkPieces(game, Level.END, city);
		}

		checkPieces(game, Level.NETHER, fortress);
		JsonObject village = Json.objects(game.tool("find_structures", "{\"structure\": \"village\", \"limit\": 3}").getAsJsonArray("results")).getFirst();
		checkPieces(game, Level.OVERWORLD, village);
		return village;
	}

	/**
	 * Visits the village for a few seconds and comes back: the visited filter flips. Land generated and saved with nobody
	 * near, then stood in: nothing in the chunk changes, so the game doesn't save its new time, and the world index must
	 * still know it after a restart, from what it recorded.
	 */
	private static void visits(Game game, JsonObject village) {
		int villageX = village.get("x").getAsInt();
		int villageZ = village.get("z").getAsInt();
		Vec3 home = game.server().computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().position());
		game.command("spreadplayers " + villageX + " " + villageZ + " 0 1 false @p");
		game.context.waitTicks(10 * SECONDS);
		game.command("forceload add " + UNVISITED_LAND + " " + UNVISITED_LAND);
		game.context.waitTicks(40);
		game.command("forceload remove " + UNVISITED_LAND + " " + UNVISITED_LAND);
		game.context.waitTicks(40);
		game.save();
		game.command("spreadplayers " + UNVISITED_LAND + " " + UNVISITED_LAND + " 0 1 false @p");
		game.context.waitTicks(5 * SECONDS);
		game.command("tp @p " + home.x() + " " + home.y() + " " + home.z());
		game.context.waitTicks(10 * SECONDS);
		game.save();
		game.context.waitTicks(40);

		JsonArray unvisited = game.tool("find_structures", "{\"structure\": \"village\", \"visited\": \"unvisited\", \"limit\": 3}").getAsJsonArray("results");
		JsonArray visited = game.tool("find_structures", "{\"structure\": \"village\", \"visited\": \"visited\", \"limit\": 3}").getAsJsonArray("results");
		check(Json.objects(visited).stream().anyMatch(result -> isAt(result, villageX, villageZ)), "visited village not listed as visited");
		check(Json.objects(unvisited).stream().noneMatch(result -> isAt(result, villageX, villageZ)), "visited village listed as unvisited");

		// A fresh index reads what the save has plus what the last one recorded, as after a restart.
		WorldIndex fresh = new WorldIndex();
		game.server().runOnServer(fresh::start);
		try {
			Visit visit = game.offThread(() -> {
				try {
					return fresh.snapshot(WorldIndex.Detail.VISITS).visit(Level.OVERWORLD, UNVISITED_LAND >> 4, UNVISITED_LAND >> 4);
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			}, 60);
			LOGGER.info("visit of the far chunk: {}", visit);
			// 5 seconds there is 100 ticks; leave margin for the teleports.
			check(visit.generated() && visit.ticks() >= 60, "time in the far chunk wasn't kept: " + visit);
		} finally {
			fresh.stop();
		}
	}

	private static boolean isAt(JsonObject result, int x, int z) {
		return result.get("x").getAsInt() == x && result.get("z").getAsInt() == z;
	}

	/** A find_structures result's pieces must be what generating its start chunk for real produces. */
	private static void checkPieces(Game game, ResourceKey<Level> dimension, JsonObject result) {
		String id = result.get("structure").getAsString();
		int chunkX = result.get("x").getAsInt() >> 4;
		int chunkZ = result.get("z").getAsInt() >> 4;
		Map<String, Integer> generated = game.server().computeOnServer(s -> {
			var structure = s.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValue(Identifier.parse(id));
			return StructureFinder.pieces(s.getLevel(dimension).getChunk(chunkX, chunkZ, ChunkStatus.STRUCTURE_STARTS).getStartForStructure(structure));
		});
		Map<String, Integer> reported = new TreeMap<>();
		result.getAsJsonObject("pieces").entrySet().forEach(e -> reported.put(e.getKey(), e.getValue().getAsInt()));
		LOGGER.info("pieces of {} at {} {}: {}", id, result.get("x"), result.get("z"), reported);
		check(reported.equals(generated), id + " pieces differ from generation: tool " + reported + ", generated " + generated);
	}

	/** The one result at {@code pos}. */
	private static JsonObject at(JsonObject search, BlockPos pos) {
		List<JsonObject> found = Json.objects(search.getAsJsonArray("results")).stream()
			.filter(result -> result.get("x").getAsInt() == pos.getX() && result.get("y").getAsInt() == pos.getY()
				&& result.get("z").getAsInt() == pos.getZ()).toList();
		check(found.size() == 1, "expected one result at " + pos.toShortString() + ": " + search);
		return found.getFirst();
	}

	/** "x y z", for a command. */
	private static String position(BlockPos pos) {
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	/** The one result whose {@code key} is {@code value}. */
	private static JsonObject only(JsonObject search, String key, String value) {
		List<JsonObject> found = Json.where(search.getAsJsonArray("results"), key, value);
		check(found.size() == 1, "expected one result with " + key + " " + value + ": " + search);
		return found.getFirst();
	}

	/** A holder's stacks that hold nothing themselves, as the tool writes them. */
	private static List<String> contents(JsonObject holder) {
		return holder.getAsJsonArray("contents").asList().stream().filter(JsonElement::isJsonPrimitive).map(JsonElement::getAsString).toList();
	}

	/** A stack as the tools write it, from the shared formatter. */
	private static String item(int count, String id, String name) {
		return ToolFormat.item(count, "minecraft:" + id, name, List.of());
	}

	private static String charges(JsonObject block) {
		return block.getAsJsonObject("properties").get("charges").getAsString();
	}

	/** "minX minY minZ to maxX maxY maxZ" as six numbers. */
	private static int[] bounds(JsonElement bounds) {
		String[] corners = bounds.getAsString().split(" to ");
		check(corners.length == 2, "bounds aren't two corners: " + bounds);
		return Arrays.stream((corners[0] + " " + corners[1]).split(" ")).mapToInt(Integer::parseInt).toArray();
	}
}
