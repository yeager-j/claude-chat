package claudechat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The tools Claude gets for the player's single-player world, served over MCP by {@link McpServer}. They search the
 * live world (plus saved chunks) and do the distance math, so Claude can just report coordinates and directions.
 */
public final class WorldTools {
	private final WorldIndex index;

	public WorldTools(WorldIndex index) {
		this.index = index;
	}

	public JsonArray definitions() {
		JsonArray tools = new JsonArray();
		tools.add(tool("search_containers",
			"Find where an item is in the player's world: chests, barrels, shulker boxes, minecarts, item frames etc., plus "
				+ "the player's own inventory and ender chest. Searches item ids, item names, enchantments and container names, "
				+ "including items inside shulker boxes and bundles. Nearest first, with exact distance and compass direction.",
			"""
			{"type": "object", "properties": {
			  "query": {"type": "string", "description": "Item id or name, e.g. \\"minecraft:diamond\\", \\"diamond\\", \\"mending\\", or a container's custom name."},
			  "dimension": {"type": "string", "description": "overworld, the_nether, the_end, or a modded dimension id. Default: all."},
			  "max_distance": {"type": "integer", "description": "Only within this many blocks of the player."},
			  "limit": {"type": "integer", "description": "Max results (default 10)."}
			}, "required": ["query"]}
			"""));
		tools.add(tool("search_entities",
			"Find entities in the player's world that are named, tamed, or hold items: pets, named mobs, armor stands, "
				+ "item frames, chest minecarts and boats, dropped items. Nearest first.",
			"""
			{"type": "object", "properties": {
			  "query": {"type": "string", "description": "Entity type (\\"wolf\\"), custom name (\\"Rex\\") or held item. Empty: all."},
			  "dimension": {"type": "string"},
			  "max_distance": {"type": "integer"},
			  "limit": {"type": "integer", "description": "Max results (default 10)."}
			}}
			"""));
		tools.add(tool("get_player",
			"The player's position, dimension, inventory and ender chest.",
			"""
			{"type": "object", "properties": {}}
			"""));
		tools.add(tool("find_structures",
			"Find structures near the player, nearest first: like /locate, but returns several and can skip ones the player "
				+ "has already visited. \"Visited\" means players spent over a minute within 128 blocks of the structure "
				+ "(Minecraft's per-chunk InhabitedTime); never-generated chunks are always unvisited. x/z is the /locate "
				+ "position; each result also has the structure's bounding box and the biomes its pieces occupy (share of "
				+ "sampled cells), worked out from the seed without generating anything. The biome filter matches a structure "
				+ "if any of its pieces is in that biome, so a fortress that starts in a crimson forest and reaches into a "
				+ "warped forest counts as in a warped forest. Structure ids are listed under data/<namespace>/worldgen/structure "
				+ "in the reference files.",
			"""
			{"type": "object", "properties": {
			  "structure": {"type": "string", "description": "Structure id (\\"minecraft:village_plains\\"), tag (\\"#minecraft:village\\"), or a word matched against tags and ids (\\"village\\", \\"ruined_portal\\")."},
			  "biome": {"type": "string", "description": "Only structures with a piece in this biome: id (\\"minecraft:warped_forest\\"), tag (\\"#minecraft:is_ocean\\"), or a word (\\"warped\\")."},
			  "dimension": {"type": "string", "description": "Default: the player's dimension, or where the structure generates if not there (a fortress asked about from the Overworld is searched in the Nether, around the player's position / 8)."},
			  "visited": {"type": "string", "enum": ["any", "unvisited", "visited"], "description": "Default: any."},
			  "min_distance": {"type": "integer"},
			  "max_distance": {"type": "integer", "description": "Search radius in blocks (default 8000, max 30000)."},
			  "limit": {"type": "integer", "description": "Max results (default 5, max 20)."}
			}, "required": ["structure"]}
			"""));
		tools.add(tool("find_blocks",
			"Find the placed blocks nearest the player, by block id or tag, optionally narrowed by block state properties "
				+ "and by text in the block's data (its block entity). Examples: a skeleton spawner is block \"spawner\" with "
				+ "data \"skeleton\"; a fully charged respawn anchor is block \"respawn_anchor\" with properties {\"charges\": \"4\"}. "
				+ "Searches the player's dimension first, then the others. Covers loaded chunks live and every saved chunk; "
				+ "it can't tell who placed a block. Block ids match the block.<namespace>.<id> keys in the lang files; "
				+ "use search_containers for what's inside chests.",
			"""
			{"type": "object", "properties": {
			  "block": {"type": "string", "description": "Block id (\\"minecraft:respawn_anchor\\"), tag (\\"#minecraft:beds\\"), or a word matched against tags and ids (\\"bed\\", \\"anchor\\")."},
			  "properties": {"type": "object", "additionalProperties": {"type": "string"}, "description": "Block state properties that must match, e.g. {\\"charges\\": \\"4\\"} or {\\"facing\\": \\"north\\"}."},
			  "data": {"type": "string", "description": "Text the block entity's data must contain (case-insensitive), e.g. the mob in a spawner (\\"skeleton\\"), sign text or a custom name."},
			  "dimension": {"type": "string", "description": "Only this dimension (overworld, the_nether, the_end, or a modded id)."},
			  "max_distance": {"type": "integer", "description": "Only within this many blocks of the player."},
			  "limit": {"type": "integer", "description": "Max results (default 5, max 30)."}
			}, "required": ["block"]}
			"""));
		tools.add(tool("inspect_location",
			"What's at a position: the block, its state and data, the biome, and the column's top block. Loaded chunks are "
				+ "read live and other generated chunks from the last save. Chunks that were never generated are predicted from "
				+ "the seed without generating them: the biome is exact, but the block is only the base terrain (stone, water, "
				+ "lava or air, before grass and other surface blocks, caves from carvers, trees, ores and structures). "
				+ "\"source\" says which applies.",
			"""
			{"type": "object", "properties": {
			  "x": {"type": "integer"},
			  "y": {"type": "integer", "description": "Omit for the top block of the column (not allowed in the Nether, whose top is the roof)."},
			  "z": {"type": "integer"},
			  "dimension": {"type": "string", "description": "Default: the player's dimension."}
			}, "required": ["x", "z"]}
			"""));
		return tools;
	}

	public JsonElement call(String name, JsonObject args) throws Exception {
		return switch (name) {
			case "search_containers" -> searchHolders(args, holder -> !holder.kind().equals("entity") || !holder.items().isEmpty(), true);
			case "search_entities" -> searchHolders(args, holder -> holder.kind().equals("entity"), false);
			case "get_player" -> getPlayer();
			case "find_structures" -> findStructures(args);
			case "find_blocks" -> findBlocks(args);
			case "inspect_location" -> inspectLocation(args);
			default -> throw new IllegalArgumentException("Unknown tool: " + name);
		};
	}

	// --- containers and entities ----------------------------------------------------------------------------------

	private JsonElement searchHolders(JsonObject args, Predicate<WorldIndex.Holder> kind, boolean queryRequired) throws Exception {
		WorldIndex.Snapshot snapshot = index.snapshot();
		String query = string(args, "query", "").trim().toLowerCase(Locale.ROOT);
		if (queryRequired && query.isEmpty()) {
			throw new IllegalArgumentException("query is required");
		}
		String dimension = string(args, "dimension", "");
		int maxDistance = integer(args, "max_distance", Integer.MAX_VALUE);
		int limit = Math.max(1, Math.min(50, integer(args, "limit", 10)));
		WorldIndex.PlayerInfo player = snapshot.players().isEmpty() ? null : snapshot.players().getFirst();

		record Hit(WorldIndex.Holder holder, List<String> matches, int count, double distance) {
		}
		List<WorldIndex.Holder> candidates = new ArrayList<>(snapshot.holders());
		if (queryRequired) {
			// "Where are my emeralds?" should also find the ones in the player's pockets and ender chest.
			for (WorldIndex.PlayerInfo p : snapshot.players()) {
				int x = (int) Math.floor(p.x());
				int y = (int) Math.floor(p.y());
				int z = (int) Math.floor(p.z());
				candidates.add(new WorldIndex.Holder("player", p.dimension(), x, y, z, "inventory of " + p.name(), null, null, false, p.inventory(), true));
				candidates.add(new WorldIndex.Holder("player", p.dimension(), x, y, z, "ender chest of " + p.name()
					+ " (open any ender chest)", null, null, false, p.enderChest(), true));
			}
		}
		List<Hit> hits = new ArrayList<>();
		for (WorldIndex.Holder holder : candidates) {
			if (!kind.test(holder) || (!dimension.isEmpty() && !holder.dimension().equals(dimension))) {
				continue;
			}
			double distance = distance(player, holder.dimension(), holder.x(), holder.y(), holder.z());
			if (distance > maxDistance) {
				continue;
			}
			List<String> matches = new ArrayList<>();
			int[] count = {0};
			collectMatches(holder.items(), query, null, matches, count);
			boolean holderMatches = query.isEmpty() || contains(holder.name(), query)
				|| (!holder.kind().equals("player") && contains(holder.type(), query))
				|| (holder.lootTable() != null && contains(holder.lootTable(), query));
			if (matches.isEmpty() && !holderMatches) {
				continue;
			}
			hits.add(new Hit(holder, matches, count[0], distance));
		}
		hits.sort(Comparator.comparingDouble(Hit::distance));

		JsonObject out = new JsonObject();
		out.add("player", playerPosition(player));
		out.addProperty("total_found", hits.size());
		out.addProperty("total_matching_items", hits.stream().mapToInt(Hit::count).sum());
		JsonArray results = new JsonArray();
		for (Hit hit : hits.subList(0, Math.min(limit, hits.size()))) {
			WorldIndex.Holder h = hit.holder();
			JsonObject r = new JsonObject();
			r.addProperty("what", h.kind().equals("entity") ? "entity " + h.type() : h.type());
			if (h.name() != null) {
				r.addProperty("name", h.name());
			}
			if (h.tamed()) {
				r.addProperty("tamed", true);
			}
			r.addProperty("dimension", h.dimension());
			r.addProperty("x", h.x());
			r.addProperty("y", h.y());
			r.addProperty("z", h.z());
			addDirection(r, player, h.dimension(), h.x(), (double) h.y(), h.z());
			r.addProperty("data", h.live() ? "live" : "last save");
			if (h.lootTable() != null) {
				r.addProperty("unopened_loot_table", h.lootTable());
			}
			if (!hit.matches().isEmpty()) {
				JsonArray matches = new JsonArray();
				hit.matches().forEach(matches::add);
				r.add("matching", matches);
			}
			if (h.items().size() <= 12) {
				r.add("contents", itemsJson(h.items()));
			} else {
				r.addProperty("contents", h.items().size() + " stacks (use a narrower query to see specific ones)");
			}
			results.add(r);
		}
		out.add("results", results);
		if (hits.size() > limit) {
			out.addProperty("note", (hits.size() - limit) + " more not shown; raise limit or narrow the search.");
		}
		return out;
	}

	/** Matching stacks, including inside shulker boxes and bundles ("32 Emerald (minecraft:emerald) inside Shulker Box"). */
	private static void collectMatches(List<WorldIndex.Item> items, String query, String inside, List<String> out, int[] count) {
		for (WorldIndex.Item item : items) {
			boolean matches = !query.isEmpty() && (item.id().equals(query) || item.id().equals("minecraft:" + query)
				|| contains(item.name(), query) || contains(item.id(), query)
				|| item.enchantments().stream().anyMatch(e -> contains(e, query)));
			if (matches) {
				count[0] += item.count();
				out.add(item.count() + " " + item.name() + " (" + item.id() + ")"
					+ (item.enchantments().isEmpty() ? "" : " {" + String.join(", ", item.enchantments()) + "}")
					+ (inside != null ? " inside " + inside : ""));
			}
			if (!item.contents().isEmpty()) {
				collectMatches(item.contents(), query, item.name() + (inside != null ? " inside " + inside : ""), out, count);
			}
		}
	}

	private JsonElement getPlayer() throws Exception {
		WorldIndex.Snapshot snapshot = index.snapshot();
		JsonArray players = new JsonArray();
		for (WorldIndex.PlayerInfo p : snapshot.players()) {
			JsonObject o = playerPosition(p);
			o.addProperty("name", p.name());
			o.add("inventory", itemsJson(p.inventory()));
			o.add("ender_chest", itemsJson(p.enderChest()));
			players.add(o);
		}
		return players;
	}

	// --- structures -----------------------------------------------------------------------------------------------

	private JsonElement findStructures(JsonObject args) throws Exception {
		String query = string(args, "structure", "");
		String visited = string(args, "visited", "any");
		int minDistance = integer(args, "min_distance", 0);
		int maxDistance = Math.max(16, Math.min(30_000, integer(args, "max_distance", 8000)));
		int limit = Math.max(1, Math.min(20, integer(args, "limit", 5)));

		WorldIndex.Snapshot snapshot = index.snapshot();
		MinecraftServer server = index.server();
		if (server == null || snapshot.players().isEmpty()) {
			throw new IllegalStateException("No player in a single-player world.");
		}
		if (!server.getWorldGenSettings().options().generateStructures()) {
			throw new IllegalStateException("Structures are turned off in this world.");
		}
		List<Holder<Structure>> structures = StructureFinder.resolve(server.registryAccess(), query);
		if (structures.isEmpty()) {
			throw new IllegalArgumentException("No structure or structure tag matches \"" + query + "\". Structure ids are under data/<namespace>/worldgen/structure.");
		}
		String biomeQuery = string(args, "biome", "").trim();
		Set<String> biomes = new HashSet<>();
		if (!biomeQuery.isEmpty()) {
			RegistryQuery.resolve(server.registryAccess(), Registries.BIOME, biomeQuery).forEach(b -> biomes.add(RegistryQuery.id(b)));
			if (biomes.isEmpty()) {
				throw new IllegalArgumentException("No biome or biome tag matches \"" + biomeQuery + "\". Biome ids are under data/<namespace>/worldgen/biome.");
			}
		}
		WorldIndex.PlayerInfo player = snapshot.players().getFirst();
		String requestedDimension = string(args, "dimension", "");

		// Footprints are computed on the server thread (in the filter, or afterwards for the results) and cached.
		Map<StructureFinder.Found, StructureFinder.Footprint> footprints = new HashMap<>();
		record Search(String dimension, WorldIndex.PlayerInfo origin, List<StructureFinder.Found> found) {
		}
		Search search = server.submit(() -> {
			ServerLevel level = structureLevel(server, structures, requestedDimension, player.dimension());
			String dimension = WorldIndex.dimensionName(level.dimension());
			// From another dimension, search around the player's position translated there (x/8 into the Nether).
			WorldIndex.PlayerInfo origin = player;
			if (!dimension.equals(player.dimension())) {
				ServerLevel from = level;
				for (ServerLevel candidate : server.getAllLevels()) {
					if (WorldIndex.dimensionName(candidate.dimension()).equals(player.dimension())) {
						from = candidate;
					}
				}
				double scale = DimensionType.getTeleportationScale(from.dimensionType(), level.dimensionType());
				origin = new WorldIndex.PlayerInfo(player.name(), dimension, player.x() * scale, player.y(), player.z() * scale, List.of(), List.of());
			}
			Predicate<StructureFinder.Found> filter = f -> f.distance() >= minDistance && switch (visited) {
				case "unvisited" -> f.inhabitedTicks() < StructureFinder.VISITED_TICKS;
				case "visited" -> f.inhabitedTicks() >= StructureFinder.VISITED_TICKS;
				default -> true;
			} && (biomes.isEmpty() || inBiome(footprints.computeIfAbsent(f, k -> StructureFinder.footprint(level, k)), biomes));
			List<StructureFinder.Found> results = StructureFinder.find(level, structures, BlockPos.containing(origin.x(), origin.y(), origin.z()),
				maxDistance, limit, filter, (cx, cz) -> snapshot.inhabited(dimension, cx, cz));
			for (StructureFinder.Found f : results) {
				footprints.computeIfAbsent(f, k -> StructureFinder.footprint(level, k));
			}
			return new Search(dimension, origin, results);
		}).get(120, TimeUnit.SECONDS);
		List<StructureFinder.Found> found = search.found();
		WorldIndex.PlayerInfo origin = search.origin();

		JsonObject out = new JsonObject();
		out.add("player", playerPosition(player));
		out.addProperty("searched", structures.size() == 1 ? structures.getFirst().unwrapKey().map(k -> k.identifier().toString()).orElse(query)
			: structures.size() + " structure types matching \"" + query + "\"");
		out.addProperty("dimension", search.dimension());
		if (origin != player) {
			out.addProperty("note_on_distances", "Distances and directions are from the player's position translated to "
				+ search.dimension() + ": " + Math.round(origin.x()) + " ~ " + Math.round(origin.z()) + ".");
		}
		JsonArray results = new JsonArray();
		for (StructureFinder.Found f : found) {
			JsonObject r = new JsonObject();
			r.addProperty("structure", f.structure());
			r.addProperty("x", f.x());
			r.addProperty("z", f.z());
			addDirection(r, origin, search.dimension(), f.x(), null, f.z());
			r.addProperty("generated", f.generated());
			r.addProperty("time_players_spent_nearby_seconds", Math.max(0, f.inhabitedTicks()) / 20);
			r.addProperty("visited", f.inhabitedTicks() >= StructureFinder.VISITED_TICKS);
			StructureFinder.Footprint footprint = footprints.get(f);
			if (footprint != null) {
				BoundingBox box = footprint.bounds();
				r.addProperty("bounds", box.minX() + " " + box.minY() + " " + box.minZ() + " to " + box.maxX() + " " + box.maxY() + " " + box.maxZ());
				JsonObject shares = new JsonObject();
				footprint.biomeSamples().entrySet().stream()
					.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
					.forEach(e -> shares.addProperty(e.getKey(), Math.max(1, Math.round(100f * e.getValue() / footprint.totalSamples())) + "%"));
				r.add("biomes", shares);
			}
			results.add(r);
		}
		out.add("results", results);
		if (found.isEmpty()) {
			out.addProperty("note", "None found within " + maxDistance + " blocks with these filters.");
		}
		return out;
	}

	/**
	 * Server thread. The requested dimension; otherwise the player's if the structure can generate there, else the
	 * first dimension where it can (a fortress asked about from the Overworld is in the Nether).
	 */
	private static ServerLevel structureLevel(MinecraftServer server, List<Holder<Structure>> structures, String requested, String playerDimension) {
		ServerLevel playerLevel = null;
		ServerLevel otherWhereItGenerates = null;
		for (ServerLevel level : server.getAllLevels()) {
			String name = WorldIndex.dimensionName(level.dimension());
			if (name.equals(requested)) {
				return level;
			}
			boolean generatesHere = structures.stream()
				.anyMatch(s -> !level.getChunkSource().getGeneratorState().getPlacementsForStructure(s).isEmpty());
			if (name.equals(playerDimension)) {
				if (generatesHere) {
					return level;
				}
				playerLevel = level;
			} else if (generatesHere && otherWhereItGenerates == null) {
				otherWhereItGenerates = level;
			}
		}
		if (!requested.isEmpty()) {
			throw new IllegalArgumentException("No dimension \"" + requested + "\".");
		}
		return otherWhereItGenerates != null ? otherWhereItGenerates : playerLevel;
	}

	private static boolean inBiome(StructureFinder.Footprint footprint, Set<String> biomes) {
		return footprint != null && footprint.biomeSamples().keySet().stream().anyMatch(biomes::contains);
	}

	// --- blocks ---------------------------------------------------------------------------------------------------

	private JsonElement findBlocks(JsonObject args) throws Exception {
		String blockQuery = string(args, "block", "");
		String data = string(args, "data", "").trim().toLowerCase(Locale.ROOT);
		String dimension = string(args, "dimension", "");
		int maxDistance = integer(args, "max_distance", Integer.MAX_VALUE);
		int limit = Math.max(1, Math.min(30, integer(args, "limit", 5)));

		WorldIndex.Snapshot snapshot = index.snapshot();
		MinecraftServer server = index.server();
		if (server == null || snapshot.players().isEmpty()) {
			throw new IllegalStateException("No player in a single-player world.");
		}
		List<Block> blocks = BlockFinder.resolve(server.registryAccess(), blockQuery);
		if (blocks.isEmpty()) {
			throw new IllegalArgumentException("No block or block tag matches \"" + blockQuery + "\".");
		}
		Map<String, String> properties = properties(args);
		checkProperties(blocks, properties);
		BlockFinder.Query query = new BlockFinder.Query(new HashSet<>(blocks), properties, data);
		WorldIndex.PlayerInfo player = snapshot.players().getFirst();

		// The player's dimension first; other dimensions only fill the remaining slots.
		List<ServerLevel> levels = new ArrayList<>();
		for (ServerLevel level : server.getAllLevels()) {
			String name = WorldIndex.dimensionName(level.dimension());
			if (dimension.isEmpty() || name.equals(dimension)) {
				levels.add(name.equals(player.dimension()) ? 0 : levels.size(), level);
			}
		}
		if (levels.isEmpty()) {
			throw new IllegalArgumentException("No dimension \"" + dimension + "\".");
		}
		JsonArray results = new JsonArray();
		int chunksRead = 0;
		boolean truncated = false;
		for (ServerLevel level : levels) {
			if (results.size() >= limit) {
				break;
			}
			String name = WorldIndex.dimensionName(level.dimension());
			BlockFinder.Result result = BlockFinder.find(index, level, query, player.x(), player.y(), player.z(), maxDistance,
				limit - results.size());
			chunksRead += result.savedChunksRead();
			truncated |= result.truncated();
			for (BlockFinder.Found found : result.found()) {
				results.add(blockJson(found, name, player));
			}
		}

		JsonObject out = new JsonObject();
		out.add("player", playerPosition(player));
		out.addProperty("searched", blocks.size() == 1 ? BuiltInRegistries.BLOCK.getKey(blocks.getFirst()).toString()
			: blocks.size() + " block types matching \"" + blockQuery + "\"");
		out.add("results", results);
		out.addProperty("saved_chunks_read", chunksRead);
		if (truncated) {
			out.addProperty("note", "Stopped after reading the nearest saved chunks that could hold this block; farther ones weren't searched.");
		} else if (results.isEmpty()) {
			out.addProperty("note", "None found in any generated chunk" + (maxDistance < Integer.MAX_VALUE ? " within " + maxDistance + " blocks." : "."));
		}
		return out;
	}

	private static JsonObject blockJson(BlockFinder.Found found, String dimension, WorldIndex.PlayerInfo player) {
		JsonObject r = new JsonObject();
		addBlockState(r, found.state());
		r.addProperty("dimension", dimension);
		r.addProperty("x", found.pos().getX());
		r.addProperty("y", found.pos().getY());
		r.addProperty("z", found.pos().getZ());
		addDirection(r, player, dimension, found.pos().getX(), (double) found.pos().getY(), found.pos().getZ());
		r.addProperty("data", found.live() ? "live" : "last save");
		addBlockEntity(r, found.blockEntity());
		return r;
	}

	/** "block": "minecraft:respawn_anchor", "properties": {"charges": "4"}. */
	private static void addBlockState(JsonObject r, BlockState state) {
		r.addProperty("block", BlockFinder.id(state));
		Map<String, String> properties = BlockFinder.properties(state);
		if (!properties.isEmpty()) {
			JsonObject props = new JsonObject();
			properties.forEach(props::addProperty);
			r.add("properties", props);
		}
	}

	/** The block entity's data as trimmed SNBT, plus "spawns" for spawners. */
	private static void addBlockEntity(JsonObject r, CompoundTag blockEntity) {
		if (blockEntity != null) {
			// Spawners and trial spawners keep the mob in SpawnData; say it plainly rather than leave it in the SNBT.
			blockEntity.getCompound("SpawnData").flatMap(spawn -> spawn.getCompound("entity"))
				.map(entity -> entity.getStringOr("id", "")).filter(id -> !id.isEmpty())
				.ifPresent(id -> r.addProperty("spawns", id));
			CompoundTag trimmed = blockEntity.copy();
			for (String key : List.of("id", "x", "y", "z", "keepPacked", "components")) {
				trimmed.remove(key);
			}
			if (!trimmed.isEmpty()) {
				String snbt = trimmed.toString();
				r.addProperty("block_entity", snbt.length() > 400 ? snbt.substring(0, 400) + "…" : snbt);
			}
		}
	}

	// --- one position ---------------------------------------------------------------------------------------------

	private JsonElement inspectLocation(JsonObject args) throws Exception {
		if (args == null || !args.has("x") || !args.has("z")) {
			throw new IllegalArgumentException("x and z are required");
		}
		int x = integer(args, "x", 0);
		int z = integer(args, "z", 0);
		Integer y = args.has("y") && !args.get("y").isJsonNull() ? integer(args, "y", 0) : null;

		WorldIndex.Snapshot snapshot = index.snapshot();
		MinecraftServer server = index.server();
		if (server == null || snapshot.players().isEmpty()) {
			throw new IllegalStateException("No player in a single-player world.");
		}
		WorldIndex.PlayerInfo player = snapshot.players().getFirst();
		String dimension = string(args, "dimension", player.dimension());
		ServerLevel level = null;
		for (ServerLevel candidate : server.getAllLevels()) {
			if (WorldIndex.dimensionName(candidate.dimension()).equals(dimension)) {
				level = candidate;
			}
		}
		if (level == null) {
			throw new IllegalArgumentException("No dimension \"" + dimension + "\".");
		}

		LocationInspector.Inspection inspection = LocationInspector.inspect(index, snapshot, level, x, y, z);
		JsonObject out = new JsonObject();
		out.addProperty("dimension", dimension);
		out.addProperty("x", x);
		out.addProperty("y", inspection.y());
		out.addProperty("z", z);
		addDirection(out, player, dimension, x, (double) inspection.y(), z);
		out.addProperty("source", inspection.source());
		addBlockState(out, inspection.state());
		addBlockEntity(out, inspection.blockEntity());
		out.addProperty("biome", inspection.biome());
		if (inspection.surfaceY() != null && !level.dimensionType().hasCeiling()) {
			out.addProperty("top_block_y", inspection.surfaceY());
		}
		return out;
	}

	/** {"charges": "4"}, or the same written as "charges=4,facing=north". */
	private static Map<String, String> properties(JsonObject args) {
		Map<String, String> properties = new LinkedHashMap<>();
		if (args == null || !args.has("properties")) {
			return properties;
		}
		JsonElement element = args.get("properties");
		if (element.isJsonObject()) {
			element.getAsJsonObject().entrySet().forEach(e -> properties.put(e.getKey().trim(),
				e.getValue().isJsonPrimitive() ? e.getValue().getAsString().trim().toLowerCase(Locale.ROOT) : e.getValue().toString()));
		} else if (element.isJsonPrimitive()) {
			for (String pair : element.getAsString().replaceAll("[\\[\\]{}]", "").split(",")) {
				String[] kv = pair.split("[=:]", 2);
				if (kv.length == 2) {
					properties.put(kv[0].trim().replace("\"", ""), kv[1].trim().replace("\"", "").toLowerCase(Locale.ROOT));
				}
			}
		}
		return properties;
	}

	/** Fail with the valid names and values rather than silently finding nothing. */
	private static void checkProperties(List<Block> blocks, Map<String, String> properties) {
		for (Map.Entry<String, String> wanted : properties.entrySet()) {
			boolean valid = blocks.stream().anyMatch(block -> {
				Property<?> property = block.getStateDefinition().getProperty(wanted.getKey());
				return property != null && property.getValue(wanted.getValue()).isPresent();
			});
			if (!valid) {
				Block block = blocks.getFirst();
				String available = block.getStateDefinition().getProperties().stream()
					.map(p -> p.getName() + " (" + p.getPossibleValues().stream().map(v -> valueName(p, v)).collect(Collectors.joining("|")) + ")")
					.collect(Collectors.joining(", "));
				throw new IllegalArgumentException("Property " + wanted.getKey() + "=" + wanted.getValue() + " doesn't exist for "
					+ BuiltInRegistries.BLOCK.getKey(block) + ". Its properties: " + (available.isEmpty() ? "none" : available));
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static <T extends Comparable<T>> String valueName(Property<T> property, Object value) {
		return property.getName((T) value);
	}

	// --- helpers --------------------------------------------------------------------------------------------------

	private static double distance(WorldIndex.PlayerInfo player, String dimension, double x, double y, double z) {
		if (player == null || !player.dimension().equals(dimension)) {
			return Double.MAX_VALUE / 2;
		}
		return Math.sqrt(Math.pow(x - player.x(), 2) + Math.pow(y - player.y(), 2) + Math.pow(z - player.z(), 2));
	}

	/** "distance": 2830, "direction": "south-east" (plus "blocks_up"/"blocks_down" when y is known). */
	private static void addDirection(JsonObject r, WorldIndex.PlayerInfo player, String dimension, double x, Double y, double z) {
		if (player == null || !player.dimension().equals(dimension)) {
			r.addProperty("distance", "in another dimension");
			return;
		}
		double dx = x - player.x();
		double dz = z - player.z();
		r.addProperty("distance_blocks", Math.round(Math.sqrt(dx * dx + dz * dz + (y == null ? 0 : Math.pow(y - player.y(), 2)))));
		r.addProperty("direction", compass(dx, dz));
		if (y != null) {
			long dy = Math.round(y - player.y());
			if (Math.abs(dy) >= 3) {
				r.addProperty(dy > 0 ? "blocks_up" : "blocks_down", Math.abs(dy));
			}
		}
	}

	/** Minecraft compass: north is -Z, east is +X. */
	private static String compass(double dx, double dz) {
		if (Math.abs(dx) < 2 && Math.abs(dz) < 2) {
			return "here";
		}
		String[] names = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
		double angle = Math.toDegrees(Math.atan2(dx, -dz));
		int index = (int) Math.round(((angle % 360) + 360) % 360 / 45) % 8;
		return names[index];
	}

	private static JsonObject playerPosition(WorldIndex.PlayerInfo player) {
		JsonObject o = new JsonObject();
		if (player != null) {
			o.addProperty("dimension", player.dimension());
			o.addProperty("x", (int) Math.floor(player.x()));
			o.addProperty("y", (int) Math.floor(player.y()));
			o.addProperty("z", (int) Math.floor(player.z()));
		}
		return o;
	}

	private static JsonArray itemsJson(List<WorldIndex.Item> items) {
		JsonArray array = new JsonArray();
		for (WorldIndex.Item item : items) {
			String text = item.count() + " " + item.name() + " (" + item.id() + ")"
				+ (item.enchantments().isEmpty() ? "" : " {" + String.join(", ", item.enchantments()) + "}");
			if (item.contents().isEmpty()) {
				array.add(text);
			} else {
				JsonObject o = new JsonObject();
				o.addProperty("item", text);
				o.add("contains", itemsJson(item.contents()));
				array.add(o);
			}
		}
		return array;
	}

	private static boolean contains(String haystack, String needle) {
		return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
	}

	private static JsonObject tool(String name, String description, String schema) {
		JsonObject tool = new JsonObject();
		tool.addProperty("name", name);
		tool.addProperty("description", description);
		tool.add("inputSchema", JsonParser.parseString(schema));
		return tool;
	}

	private static String string(JsonObject args, String key, String fallback) {
		return args != null && args.has(key) && args.get(key).isJsonPrimitive() ? args.get(key).getAsString() : fallback;
	}

	private static int integer(JsonObject args, String key, int fallback) {
		try {
			return args != null && args.has(key) && args.get(key).isJsonPrimitive() ? args.get(key).getAsInt() : fallback;
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
