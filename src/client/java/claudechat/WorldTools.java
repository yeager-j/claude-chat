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
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
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
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The tools Claude gets for the player's single-player world, served over MCP by {@link McpServer}. They search the
 * live world (plus saved chunks) and do the distance math, so Claude can just report coordinates and directions.
 */
public final class WorldTools {
	private final WorldIndex index;
	private final StructureTemplates templates = new StructureTemplates();
	private final Materials materials;

	public WorldTools(WorldIndex index, KnowledgeBase knowledge) {
		this.index = index;
		this.materials = new Materials(knowledge);
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
			  "min_biome_share": {"type": "integer", "description": "With biome: only structures at least this percent in it (0-100), e.g. 75 for \\"mostly in a warped forest\\". Shares of several matching biomes (a tag) add up. Default: any piece."},
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
		tools.add(tool("find_biomes",
			"Find patches of a biome near the player, nearest first: like /locate biome, but returns several separate "
				+ "patches. Worked out from the seed without generating anything, sampling every 32 blocks across and 64 up "
				+ "and down, so patches smaller than that can be missed. Searches the player's dimension, or where the biome "
				+ "exists if not there (a warped forest asked about from the Overworld is searched in the Nether, around the "
				+ "player's position / 8). y is a height where the biome is: underground biomes like deep_dark and lush_caves "
				+ "are found at their depth. Biome ids are under data/<namespace>/worldgen/biome in the reference files.",
			"""
			{"type": "object", "properties": {
			  "biome": {"type": "string", "description": "Biome id (\\"minecraft:cherry_grove\\"), tag (\\"#minecraft:is_ocean\\"), or a word (\\"cherry\\", \\"mushroom\\")."},
			  "dimension": {"type": "string"},
			  "visited": {"type": "string", "enum": ["any", "unvisited", "visited"], "description": "Whether players spent over a minute near the spot. Default: any."},
			  "min_spacing": {"type": "integer", "description": "Hits closer together than this many blocks count as one patch (default 256)."},
			  "max_distance": {"type": "integer", "description": "Search radius in blocks (default 6400 like /locate, max 30000)."},
			  "limit": {"type": "integer", "description": "Max results (default 5, max 20)."}
			}, "required": ["biome"]}
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
		tools.add(tool("search_structure_templates",
			"Search structure templates, the .nbt building blocks that structures are made of (a village house, an ancient "
				+ "city room, a modded tower), by the blocks they contain. Use it to identify a structure the player describes "
				+ "(\"a small tower of mossy stone bricks with a spawner\"): pass the blocks they mention. Templates containing "
				+ "the most of the requested blocks come first, then those with many of them that also make up much of the build. "
				+ "Big buildings are often split into several templates (a main building plus add-ons), so look past small "
				+ "pieces. Covers Minecraft, mods, the "
				+ "world's data packs and templates saved in the world with a structure block. Each result says which structures "
				+ "can place it (traced through template pools; structures that place templates from code, like igloos or many "
				+ "mods' own structures, aren't traced, so that list can be empty). Template files are gzipped NBT and can't be "
				+ "read with Read; use inspect_structure_template for one template's details.",
			"""
			{"type": "object", "properties": {
			  "blocks": {"type": "array", "items": {"type": "string"}, "description": "Blocks the template should contain: ids (\\"minecraft:mossy_stone_bricks\\"), tags (\\"#minecraft:logs\\") or words (\\"spawner\\", \\"lantern\\")."},
			  "query": {"type": "string", "description": "Only templates whose id, or the id of a structure using them, contains this text, e.g. a mod namespace (\\"dungeons_arise\\") or \\"village\\"."},
			  "max_size": {"type": "integer", "description": "Only templates no larger than this many blocks along any side."},
			  "limit": {"type": "integer", "description": "Max results (default 10, max 30)."}
			}}
			"""));
		tools.add(tool("inspect_structure_template",
			"One structure template in detail: size, every block with its count, chests (with loot tables), spawners and "
				+ "other block entities, entities, jigsaw connectors (which pools it attaches to), and the structures that can "
				+ "place it. With layers, also draws it as text, one horizontal slice per y level: rows run north to south, "
				+ "columns west to east, '.' is air, ' ' is empty (keeps whatever was there), and a legend maps the other "
				+ "characters to blocks. Blocks shown are before processors run, so a structure may swap some in the world "
				+ "(cracked or mossy variants, random ores, decay).",
			"""
			{"type": "object", "properties": {
			  "template": {"type": "string", "description": "Template id, e.g. \\"minecraft:village/plains/houses/plains_small_house_1\\" (the path under data/<namespace>/structure/, without .nbt)."},
			  "layers": {"type": "boolean", "description": "Also draw it layer by layer (default false)."},
			  "y_from": {"type": "integer", "description": "With layers: lowest y to draw, 0 = bottom of the template."},
			  "y_to": {"type": "integer", "description": "With layers: highest y to draw."}
			}, "required": ["template"]}
			"""));
		tools.add(tool("list_schematics",
			"List the player's schematic files (Litematica .litematic, WorldEdit .schem, structure .nbt) from their "
				+ "schematics folder, with name, author, size and block count. The files are gzipped NBT and can't be read with "
				+ "Read; use inspect_schematic.",
			"""
			{"type": "object", "properties": {
			  "query": {"type": "string", "description": "Only files whose path or schematic name contains this text."}
			}}
			"""));
		tools.add(tool("inspect_schematic",
			"A schematic's material list and contents. \"materials\" is the items needed to place every block, counted "
				+ "like Litematica's material list (a double slab is 2 slabs, a door is 1 item, redstone wire is redstone dust, "
				+ "water sources are water buckets). \"crafting\" breaks crafted components (pistons, repeaters, observers, "
				+ "torches, hoppers...) down into ingredients using the game's recipes, and \"base_materials\" is the total "
				+ "after that, e.g. all the redstone dust including what goes into pistons. Shapes of one material (planks, "
				+ "slabs, trapdoors, storage blocks) aren't broken down. With compare_with, also checks a container in the "
				+ "world (a chest's custom name, x y z, or \"inventory\") including shulker boxes in it: what's missing as "
				+ "placed, and what's still missing if the player crafts components from what's there; \"useful_contents\" is what "
				+ "they hold of the materials and their ingredients, \"not_needed\" the rest. With layers, draws it "
				+ "like inspect_structure_template.",
			"""
			{"type": "object", "properties": {
			  "schematic": {"type": "string", "description": "File name (\\"Witch Farm.litematic\\"), part of it, or the schematic's name."},
			  "compare_with": {"type": "string", "description": "Container to check against: custom name (\\"witch farm\\"; every container with that name counts, e.g. both halves of a double chest), \\"x y z\\", or \\"inventory\\"."},
			  "layers": {"type": "boolean"},
			  "y_from": {"type": "integer"},
			  "y_to": {"type": "integer"}
			}, "required": ["schematic"]}
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
			case "find_biomes" -> findBiomes(args);
			case "search_structure_templates" -> searchTemplates(args);
			case "inspect_structure_template" -> inspectTemplate(args);
			case "list_schematics" -> listSchematics(args);
			case "inspect_schematic" -> inspectSchematic(args);
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
		int minShare = Math.max(0, Math.min(100, integer(args, "min_biome_share", 0)));
		if (minShare > 0 && biomes.isEmpty()) {
			throw new IllegalArgumentException("min_biome_share needs a biome.");
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
			WorldIndex.PlayerInfo origin = originIn(server, player, level);
			Predicate<StructureFinder.Found> filter = f -> f.distance() >= minDistance && switch (visited) {
				case "unvisited" -> f.inhabitedTicks() < StructureFinder.VISITED_TICKS;
				case "visited" -> f.inhabitedTicks() >= StructureFinder.VISITED_TICKS;
				default -> true;
			} && (biomes.isEmpty() || inBiome(footprints.computeIfAbsent(f, k -> StructureFinder.footprint(level, k)), biomes, minShare));
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
		addOriginNote(out, origin, player);
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
				if (!biomes.isEmpty()) {
					r.addProperty("share_in_requested_biome", Math.round(biomeShare(footprint, biomes)) + "%");
				}
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
	 * Where to search from in {@code level}: the player, or from another dimension the player's position translated
	 * there (x/8 into the Nether), with the player's y.
	 */
	private static WorldIndex.PlayerInfo originIn(MinecraftServer server, WorldIndex.PlayerInfo player, ServerLevel level) {
		String dimension = WorldIndex.dimensionName(level.dimension());
		if (dimension.equals(player.dimension())) {
			return player;
		}
		ServerLevel from = level;
		for (ServerLevel candidate : server.getAllLevels()) {
			if (WorldIndex.dimensionName(candidate.dimension()).equals(player.dimension())) {
				from = candidate;
			}
		}
		double scale = DimensionType.getTeleportationScale(from.dimensionType(), level.dimensionType());
		return new WorldIndex.PlayerInfo(player.name(), dimension, player.x() * scale, player.y(), player.z() * scale, List.of(), List.of());
	}

	/** "Distances and directions are from ..." when searching another dimension than the player's. */
	private static void addOriginNote(JsonObject out, WorldIndex.PlayerInfo origin, WorldIndex.PlayerInfo player) {
		if (origin != player) {
			out.addProperty("note_on_distances", "Distances and directions are from the player's position translated to "
				+ origin.dimension() + ": " + Math.round(origin.x()) + " ~ " + Math.round(origin.z()) + ".");
		}
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

	/** Any piece in one of the biomes, and at least {@code minShare} percent of the sampled cells in them combined. */
	private static boolean inBiome(StructureFinder.Footprint footprint, Set<String> biomes, int minShare) {
		double share = biomeShare(footprint, biomes);
		return share > 0 && share >= minShare;
	}

	/** Percent of the structure's sampled cells in any of these biomes. */
	private static double biomeShare(StructureFinder.Footprint footprint, Set<String> biomes) {
		if (footprint == null || footprint.totalSamples() == 0) {
			return 0;
		}
		int matching = footprint.biomeSamples().entrySet().stream()
			.filter(e -> biomes.contains(e.getKey())).mapToInt(Map.Entry::getValue).sum();
		return 100.0 * matching / footprint.totalSamples();
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

	// --- biomes --------------------------------------------------------------------------------------------------

	private JsonElement findBiomes(JsonObject args) throws Exception {
		String query = string(args, "biome", "");
		String visited = string(args, "visited", "any");
		String requested = string(args, "dimension", "");
		int minSpacing = Math.max(32, integer(args, "min_spacing", 256));
		int maxDistance = Math.max(32, Math.min(30_000, integer(args, "max_distance", 6400)));
		int limit = Math.max(1, Math.min(20, integer(args, "limit", 5)));

		WorldIndex.Snapshot snapshot = index.snapshot();
		MinecraftServer server = index.server();
		if (server == null || snapshot.players().isEmpty()) {
			throw new IllegalStateException("No player in a single-player world.");
		}
		Set<Holder<Biome>> biomes = new HashSet<>(RegistryQuery.resolve(server.registryAccess(), Registries.BIOME, query));
		if (biomes.isEmpty()) {
			throw new IllegalArgumentException("No biome or biome tag matches \"" + query + "\". Biome ids are under data/<namespace>/worldgen/biome.");
		}
		WorldIndex.PlayerInfo player = snapshot.players().getFirst();

		// The requested dimension, else the player's if the biome exists there, else the first one where it does.
		ServerLevel level = null;
		ServerLevel elsewhere = null;
		for (ServerLevel candidate : server.getAllLevels()) {
			String name = WorldIndex.dimensionName(candidate.dimension());
			boolean exists = candidate.getChunkSource().getGenerator().getBiomeSource().possibleBiomes().stream().anyMatch(biomes::contains);
			if (requested.isEmpty() ? exists && name.equals(player.dimension()) : name.equals(requested)) {
				level = candidate;
			} else if (exists && elsewhere == null) {
				elsewhere = candidate;
			}
		}
		if (level == null && !requested.isEmpty()) {
			throw new IllegalArgumentException("No dimension \"" + requested + "\".");
		}
		if (level == null && elsewhere == null) {
			throw new IllegalArgumentException("\"" + query + "\" doesn't generate in any dimension of this world.");
		}
		ServerLevel searchLevel = level != null ? level : elsewhere;
		String dimension = WorldIndex.dimensionName(searchLevel.dimension());
		WorldIndex.PlayerInfo origin = originIn(server, player, searchLevel);

		// Visited filtering needs more candidates than the limit; the biome noise is cheap, so over-fetch.
		int fetch = visited.equals("any") ? limit : Math.min(200, limit * 10);
		BiomeFinder.Result result = BiomeFinder.find(searchLevel, biomes, origin.x(), origin.y(), origin.z(), maxDistance, minSpacing, fetch);

		JsonObject out = new JsonObject();
		out.add("player", playerPosition(player));
		out.addProperty("searched", biomes.size() == 1 ? RegistryQuery.id(biomes.iterator().next())
			: biomes.size() + " biomes matching \"" + query + "\"");
		out.addProperty("dimension", dimension);
		addOriginNote(out, origin, player);
		JsonArray results = new JsonArray();
		for (BiomeFinder.Found f : result.found()) {
			long ticks = snapshot.inhabited(dimension, SectionPos.blockToSectionCoord(f.x()), SectionPos.blockToSectionCoord(f.z()));
			boolean isVisited = ticks >= StructureFinder.VISITED_TICKS;
			if ((visited.equals("visited") && !isVisited) || (visited.equals("unvisited") && isVisited) || results.size() >= limit) {
				continue;
			}
			JsonObject r = new JsonObject();
			r.addProperty("biome", f.biome());
			r.addProperty("x", f.x());
			r.addProperty("y", f.y());
			r.addProperty("z", f.z());
			addDirection(r, origin, dimension, f.x(), null, f.z());
			r.addProperty("generated", ticks >= 0);
			r.addProperty("visited", isVisited);
			results.add(r);
		}
		out.add("results", results);
		if (result.truncated()) {
			out.addProperty("note", "Stopped early: the search area was too large. Narrow max_distance to search farther in parts.");
		} else if (results.isEmpty()) {
			out.addProperty("note", "None found within " + maxDistance + " blocks with these filters.");
		}
		return out;
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

	// --- structure templates --------------------------------------------------------------------------------------

	/** Characters for the layer drawing, most common block first; '.' (air), ' ' (empty) and '?' (other) are reserved. */
	private static final String LAYER_SYMBOLS = "#ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789@$%&*+=!~^<>/|:;";
	/** About 10k tokens of drawing. */
	private static final int MAX_LAYER_CELLS = 40_000;
	private static final int MIN_BUILDING_BLOCKS = 8;

	/**
	 * Slow first-use work, in the background when the world opens or reloads: the structure template index, the
	 * recipes for material breakdowns, and the data fixer's block state rules for old schematics.
	 */
	public void prepare(MinecraftServer server) {
		templates.prepare(server);
		Thread thread = new Thread(() -> {
			long start = System.nanoTime();
			materials.prepare();
			long recipesDone = System.nanoTime();
			Schematics.prepare();
			ClaudeChatClient.LOGGER.info("Prepared recipes in {} ms and the schematic data fixer in {} ms",
				(recipesDone - start) / 1_000_000, (System.nanoTime() - recipesDone) / 1_000_000);
		}, "Claude Chat materials");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		thread.start();
	}

	public void clearTemplates() {
		templates.clear();
	}

	private JsonElement searchTemplates(JsonObject args) throws Exception {
		MinecraftServer server = index.server();
		if (server == null) {
			throw new IllegalStateException("No single-player world open.");
		}
		List<String> terms = new ArrayList<>();
		if (args != null && args.has("blocks")) {
			JsonElement element = args.get("blocks");
			if (element.isJsonArray()) {
				element.getAsJsonArray().forEach(e -> terms.add(e.getAsString().trim()));
			} else if (element.isJsonPrimitive()) {
				terms.addAll(List.of(element.getAsString().split(",")));
			}
			terms.replaceAll(String::trim);
			terms.removeIf(String::isEmpty);
		}
		String query = string(args, "query", "").trim().toLowerCase(Locale.ROOT);
		int maxSize = integer(args, "max_size", Integer.MAX_VALUE);
		int limit = Math.max(1, Math.min(30, integer(args, "limit", 10)));
		if (terms.isEmpty() && query.isEmpty()) {
			throw new IllegalArgumentException("Give blocks, a query, or both.");
		}
		Map<String, Set<Block>> wanted = new LinkedHashMap<>();
		for (String term : terms) {
			List<Block> blocks = BlockFinder.resolve(server.registryAccess(), term);
			if (blocks.isEmpty()) {
				throw new IllegalArgumentException("No block or block tag matches \"" + term + "\".");
			}
			wanted.put(term, new HashSet<>(blocks));
		}
		Set<Block> anyWanted = new HashSet<>();
		wanted.values().forEach(anyWanted::addAll);

		StructureTemplates.Index templateIndex = templates.index(server);
		/** score: matching blocks × their share, so both many matches and a build mostly made of them rank high. */
		record Hit(StructureTemplates.Summary template, Map<String, Integer> perTerm, int termsMatched, double share, double score) {
		}
		List<Hit> hits = new ArrayList<>();
		for (StructureTemplates.Summary t : templateIndex.templates()) {
			Vec3i size = t.size();
			if (Math.max(size.getX(), Math.max(size.getY(), size.getZ())) > maxSize) {
				continue;
			}
			Set<Identifier> structures = templateIndex.structures().getOrDefault(t.id(), Set.of());
			if (!query.isEmpty() && !t.id().toString().contains(query) && structures.stream().noneMatch(s -> s.toString().contains(query))) {
				continue;
			}
			Map<String, Integer> perTerm = new LinkedHashMap<>();
			int termsMatched = 0;
			for (Map.Entry<String, Set<Block>> term : wanted.entrySet()) {
				int count = term.getValue().stream().mapToInt(b -> t.blockCounts().getOrDefault(b, 0)).sum();
				perTerm.put(term.getKey(), count);
				termsMatched += count > 0 ? 1 : 0;
			}
			if (!wanted.isEmpty() && termsMatched == 0) {
				continue;
			}
			int matchingBlocks = anyWanted.stream().mapToInt(b -> t.blockCounts().getOrDefault(b, 0)).sum();
			double share = t.solidBlocks() == 0 ? 0 : (double) matchingBlocks / t.solidBlocks();
			hits.add(new Hit(t, perTerm, termsMatched, share, matchingBlocks * share));
		}
		// Tiny pieces (a lone spawner on a jigsaw) match a single block perfectly but rarely identify anything; rank them after real buildings.
		hits.sort(Comparator.comparingInt(Hit::termsMatched).reversed()
			.thenComparing(h -> h.template().solidBlocks() < MIN_BUILDING_BLOCKS)
			.thenComparing(Comparator.comparingDouble(Hit::score).reversed())
			.thenComparing(h -> h.template().id().toString()));

		JsonObject out = new JsonObject();
		JsonObject searched = new JsonObject();
		wanted.forEach((term, blocks) -> searched.addProperty(term, blocks.size() == 1
			? StructureTemplates.id(blocks.iterator().next()) : blocks.size() + " blocks"));
		if (!wanted.isEmpty()) {
			out.add("searched_blocks", searched);
		}
		out.addProperty("templates_indexed", templateIndex.templates().size());
		out.addProperty("total_found", hits.size());
		if (!hits.isEmpty() && wanted.size() > 1 && hits.getFirst().termsMatched() < wanted.size()) {
			out.addProperty("note", "No template has all of these blocks; these have the most.");
		}
		JsonArray results = new JsonArray();
		for (Hit hit : hits.subList(0, Math.min(limit, hits.size()))) {
			StructureTemplates.Summary t = hit.template();
			JsonObject r = new JsonObject();
			r.addProperty("template", t.id().toString());
			r.addProperty("from", t.pack());
			r.addProperty("size", sizeText(t.size()));
			r.addProperty("solid_blocks", t.solidBlocks());
			if (!wanted.isEmpty()) {
				JsonObject matching = new JsonObject();
				hit.perTerm().forEach(matching::addProperty);
				r.add("requested_blocks", matching);
				r.addProperty("share_of_requested_blocks", Math.round(100 * hit.share()) + "%");
			}
			JsonArray main = new JsonArray();
			t.blockCounts().entrySet().stream().sorted(Map.Entry.<Block, Integer>comparingByValue().reversed()).limit(6)
				.forEach(e -> main.add(StructureTemplates.id(e.getKey()) + " ×" + e.getValue()));
			r.add("main_blocks", main);
			if (!t.entityCounts().isEmpty()) {
				JsonObject entities = new JsonObject();
				t.entityCounts().forEach(entities::addProperty);
				r.add("entities", entities);
			}
			addTemplateUsage(r, templateIndex, t.id(), 6);
			results.add(r);
		}
		out.add("results", results);
		if (hits.size() > limit) {
			out.addProperty("more", (hits.size() - limit) + " more not shown; add blocks, a query or max_size to narrow it.");
		}
		return out;
	}

	private JsonElement inspectTemplate(JsonObject args) throws Exception {
		MinecraftServer server = index.server();
		if (server == null) {
			throw new IllegalStateException("No single-player world open.");
		}
		String name = string(args, "template", "").trim().replaceFirst("\\.nbt$", "");
		Identifier id = Identifier.tryParse(name);
		if (id == null) {
			throw new IllegalArgumentException("\"" + name + "\" isn't a valid template id.");
		}
		StructureTemplates.Index templateIndex = templates.index(server);
		StructureTemplates.Template t = StructureTemplates.load(server, id).orElse(null);
		if (t == null) {
			String last = id.getPath().substring(id.getPath().lastIndexOf('/') + 1);
			List<String> similar = templateIndex.templates().stream().map(s -> s.id().toString())
				.filter(s -> s.contains(last)).limit(10).toList();
			throw new IllegalArgumentException("No structure template " + id + "."
				+ (similar.isEmpty() ? " Use search_structure_templates to find one." : " Similar: " + String.join(", ", similar)));
		}

		JsonObject out = new JsonObject();
		out.addProperty("template", id.toString());
		out.addProperty("from", t.pack());
		out.addProperty("size", sizeText(t.size()));
		if (t.palettes() > 1) {
			out.addProperty("palettes", t.palettes() + " block variants, one picked at random when placed; showing the first");
		}
		Map<Block, Integer> counts = new HashMap<>();
		t.blocks().stream().filter(b -> !b.state().isAir() && !b.state().is(Blocks.JIGSAW)).forEach(b -> counts.merge(b.state().getBlock(), 1, Integer::sum));
		out.addProperty("solid_blocks", counts.values().stream().mapToInt(Integer::intValue).sum());
		JsonObject blocks = new JsonObject();
		counts.entrySet().stream().sorted(Map.Entry.<Block, Integer>comparingByValue().reversed()).limit(100)
			.forEach(e -> blocks.addProperty(StructureTemplates.id(e.getKey()), e.getValue()));
		out.add("blocks", blocks);
		addBlockEntities(out, t.blocks(), t.entities());
		addTemplateUsage(out, templateIndex, id, 20);
		if (args != null && args.has("layers") && args.get("layers").getAsBoolean()) {
			addLayers(out, t.size(), t.blocks(), args);
		}
		return out;
	}

	/** "block_entities" (chests with loot tables, spawners, signs...), "entities" and "jigsaw_connectors". */
	private static void addBlockEntities(JsonObject out, List<StructureTemplates.BlockInfo> all, List<StructureTemplates.EntityInfo> entityList) {
		JsonArray blockEntities = new JsonArray();
		JsonArray jigsaws = new JsonArray();
		int moreBlockEntities = 0;
		for (StructureTemplates.BlockInfo b : all) {
			if (b.nbt() == null) {
				continue;
			}
			JsonObject r = new JsonObject();
			if (b.state().is(Blocks.JIGSAW)) {
				r.addProperty("pos", posText(b.pos()));
				String facing = BlockFinder.properties(b.state()).get("orientation");
				if (facing != null) {
					r.addProperty("facing", facing);
				}
				for (String key : List.of("pool", "name", "target", "final_state")) {
					String value = b.nbt().getStringOr(key, "");
					if (!value.isEmpty() && !value.equals("minecraft:empty")) {
						r.addProperty(key.equals("final_state") ? "turns_into" : key, value);
					}
				}
				if (jigsaws.size() < 40) {
					jigsaws.add(r);
				}
				continue;
			}
			if (blockEntities.size() >= 40) {
				moreBlockEntities++;
				continue;
			}
			addBlockState(r, b.state());
			r.addProperty("pos", posText(b.pos()));
			b.nbt().getString("LootTable").ifPresent(loot -> r.addProperty("loot_table", loot));
			CompoundTag rest = b.nbt().copy();
			rest.remove("LootTable");
			addBlockEntity(r, rest);
			blockEntities.add(r);
		}
		if (!blockEntities.isEmpty()) {
			out.add("block_entities", blockEntities);
			if (moreBlockEntities > 0) {
				out.addProperty("more_block_entities", moreBlockEntities);
			}
		}
		if (!entityList.isEmpty()) {
			JsonArray entities = new JsonArray();
			entityList.stream().limit(30).forEach(e -> entities.add(e.type() + " at " + posText(e.pos())));
			out.add("entities", entities);
		}
		if (!jigsaws.isEmpty()) {
			out.add("jigsaw_connectors", jigsaws);
		}
	}

	// --- schematics -----------------------------------------------------------------------------------------------

	private JsonElement listSchematics(JsonObject args) throws Exception {
		String query = string(args, "query", "").trim().toLowerCase(Locale.ROOT);
		JsonObject out = new JsonObject();
		out.addProperty("folder", Schematics.directory().toString());
		JsonArray results = new JsonArray();
		for (java.nio.file.Path file : Schematics.list()) {
			JsonObject r = new JsonObject();
			r.addProperty("file", Schematics.relativeName(file));
			try {
				Schematics.Schematic s = Schematics.load(file);
				if (!query.isEmpty() && !Schematics.relativeName(file).toLowerCase(Locale.ROOT).contains(query)
					&& !s.name().toLowerCase(Locale.ROOT).contains(query)) {
					continue;
				}
				r.addProperty("name", s.name());
				if (!s.author().isEmpty()) {
					r.addProperty("author", s.author());
				}
				r.addProperty("size", sizeText(s.size()));
				r.addProperty("blocks", s.blocks().stream().filter(b -> !b.state().isAir()).count());
			} catch (Exception e) {
				if (!query.isEmpty() && !Schematics.relativeName(file).toLowerCase(Locale.ROOT).contains(query)) {
					continue;
				}
				r.addProperty("error", "unreadable: " + e.getMessage());
			}
			results.add(r);
		}
		out.add("schematics", results);
		if (results.isEmpty()) {
			out.addProperty("note", "No schematics" + (query.isEmpty() ? "" : " matching \"" + query + "\"") + " in that folder.");
		}
		return out;
	}

	private JsonElement inspectSchematic(JsonObject args) throws Exception {
		String query = string(args, "schematic", "").trim();
		java.nio.file.Path file = Schematics.find(query).orElseThrow(() -> new IllegalArgumentException(
			"No schematic matching \"" + query + "\" in " + Schematics.directory() + ". Use list_schematics."));
		Schematics.Schematic s = Schematics.load(file);

		JsonObject out = new JsonObject();
		out.addProperty("file", Schematics.relativeName(file));
		out.addProperty("name", s.name());
		if (!s.author().isEmpty()) {
			out.addProperty("author", s.author());
		}
		if (!s.description().isEmpty()) {
			out.addProperty("description", s.description());
		}
		out.addProperty("size", sizeText(s.size()));
		if (s.regions().size() > 1) {
			JsonArray regions = new JsonArray();
			s.regions().forEach(r -> regions.add(r.name() + ": " + sizeText(r.size()) + " at " + posText(r.position())));
			out.add("regions", regions);
		}

		Map<String, Integer> noItem = new TreeMap<>();
		Map<String, Integer> placed = Materials.asPlaced(s.blocks().stream().map(StructureTemplates.BlockInfo::state).toList(), noItem);
		out.add("materials", countsJson(placed));
		out.addProperty("total_items", placed.values().stream().mapToInt(Integer::intValue).sum());
		if (!noItem.isEmpty()) {
			out.add("blocks_without_an_item", countsJson(noItem));
		}
		Materials.Breakdown full = materials.breakDown(placed, Map.of());
		if (!full.crafts().isEmpty()) {
			out.add("crafting", craftsJson(full.crafts()));
			out.add("base_materials", countsJson(full.remaining()));
		}

		String compare = string(args, "compare_with", "").trim();
		if (!compare.isEmpty()) {
			out.add("comparison", compare(compare, placed));
		}
		addBlockEntities(out, s.blocks(), s.entities());
		if (args.has("layers") && args.get("layers").getAsBoolean()) {
			addLayers(out, s.size(), s.blocks(), args);
		}
		return out;
	}

	/** Checks the containers matching {@code query} against the materials; see inspect_schematic's description. */
	private JsonObject compare(String query, Map<String, Integer> placed) throws Exception {
		WorldIndex.Snapshot snapshot = index.snapshot();
		if (index.server() == null || snapshot.players().isEmpty()) {
			throw new IllegalStateException("Comparing with a container needs a single-player world.");
		}
		WorldIndex.PlayerInfo player = snapshot.players().getFirst();
		List<String> used = new ArrayList<>();
		List<WorldIndex.Item> items = new ArrayList<>();
		String q = query.toLowerCase(Locale.ROOT);
		if (q.equals("inventory")) {
			items.addAll(player.inventory());
			used.add("inventory of " + player.name());
		} else {
			String[] parts = q.replace(",", " ").trim().split("\\s+");
			Integer[] xyz = parts.length == 3 ? parseInts(parts) : null;
			List<WorldIndex.Holder> matches = new ArrayList<>();
			for (WorldIndex.Holder h : snapshot.holders()) {
				if (h.kind().equals("entity") || !h.dimension().equals(player.dimension()) && xyz == null) {
					continue;
				}
				boolean match = xyz != null ? h.x() == xyz[0] && h.y() == xyz[1] && h.z() == xyz[2] : h.name() != null && h.name().toLowerCase(Locale.ROOT).contains(q);
				if (match) {
					matches.add(h);
				}
			}
			if (xyz != null && matches.size() == 1) {
				// The other half of a double chest.
				WorldIndex.Holder first = matches.getFirst();
				for (WorldIndex.Holder h : snapshot.holders()) {
					if (h != first && h.type().equals(first.type()) && h.dimension().equals(first.dimension()) && h.y() == first.y()
						&& Math.abs(h.x() - first.x()) + Math.abs(h.z() - first.z()) == 1 && java.util.Objects.equals(h.name(), first.name())) {
						matches.add(h);
					}
				}
			}
			if (matches.isEmpty()) {
				List<String> named = snapshot.holders().stream().filter(h -> h.name() != null && !h.kind().equals("entity"))
					.map(h -> "\"" + h.name() + "\" at " + h.x() + " " + h.y() + " " + h.z()).distinct().limit(20).toList();
				throw new IllegalArgumentException("No container matching \"" + query + "\"." + (named.isEmpty()
					? " There are no named containers; give its coordinates instead." : " Named containers: " + String.join(", ", named)));
			}
			for (WorldIndex.Holder h : matches) {
				items.addAll(h.items());
				used.add(h.type() + (h.name() != null ? " \"" + h.name() + "\"" : "") + " at " + h.x() + " " + h.y() + " " + h.z()
					+ (h.live() ? "" : " (last save)"));
			}
		}
		Map<String, Integer> stock = new TreeMap<>();
		flatten(items, stock);

		JsonObject out = new JsonObject();
		JsonArray containers = new JsonArray();
		used.forEach(containers::add);
		out.add("containers", containers);
		Map<String, Integer> missingAsPlaced = new TreeMap<>();
		placed.forEach((item, need) -> {
			int shortfall = need - stock.getOrDefault(item, 0);
			if (shortfall > 0) {
				missingAsPlaced.put(item, shortfall);
			}
		});
		out.add("missing_as_placed", countsJson(missingAsPlaced));
		Materials.Breakdown withStock = materials.breakDown(placed, stock);
		out.add("still_missing_after_crafting_from_contents", countsJson(withStock.remaining()));
		if (!withStock.crafts().isEmpty()) {
			out.add("crafting_needed", craftsJson(withStock.crafts()));
		}
		out.addProperty("complete", withStock.remaining().isEmpty());
		Map<String, Integer> fullNeeds = new HashMap<>(placed);
		materials.breakDown(placed, Map.of()).crafts().forEach(c -> c.recipe().ingredients().keySet().forEach(i -> fullNeeds.putIfAbsent(i, 0)));
		Map<String, Integer> have = new TreeMap<>();
		Map<String, Integer> extra = new TreeMap<>();
		stock.forEach((item, count) -> (fullNeeds.containsKey(item) ? have : extra).put(item, count));
		out.add("useful_contents", countsJson(have));
		if (!extra.isEmpty()) {
			out.add("not_needed", countsJson(extra));
		}
		return out;
	}

	/** Item counts by id, including what's inside shulker boxes and bundles (the box itself counts too). */
	private static void flatten(List<WorldIndex.Item> items, Map<String, Integer> into) {
		for (WorldIndex.Item item : items) {
			into.merge(item.id(), item.count(), Integer::sum);
			flatten(item.contents(), into);
		}
	}

	private static Integer[] parseInts(String[] parts) {
		try {
			return new Integer[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** {"minecraft:redstone": 41, ...}, most first. */
	private static JsonObject countsJson(Map<String, Integer> counts) {
		JsonObject o = new JsonObject();
		counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).forEach(e -> o.addProperty(e.getKey(), e.getValue()));
		return o;
	}

	private static JsonArray craftsJson(List<Materials.Craft> crafts) {
		JsonArray array = new JsonArray();
		for (Materials.Craft c : crafts) {
			JsonObject o = new JsonObject();
			o.addProperty("item", c.item());
			o.addProperty("needed", c.used());
			o.addProperty("craft_times", c.crafts());
			if (c.made() > c.used()) {
				o.addProperty("left_over", c.made() - c.used());
			}
			JsonObject per = new JsonObject();
			c.recipe().ingredients().forEach(per::addProperty);
			o.add("ingredients_per_craft", per);
			o.addProperty("recipe", c.recipe().file());
			array.add(o);
		}
		return array;
	}

	/** "used_by_structures" (traced through pools) and "in_pools". */
	private static void addTemplateUsage(JsonObject r, StructureTemplates.Index index, Identifier id, int max) {
		Set<Identifier> structures = index.structures().getOrDefault(id, Set.of());
		Set<Identifier> pools = index.pools().getOrDefault(id, Set.of());
		if (!structures.isEmpty()) {
			r.add("used_by_structures", idList(structures, max));
		}
		if (!pools.isEmpty()) {
			r.add("in_pools", idList(pools, max));
		}
		if (structures.isEmpty() && pools.isEmpty()) {
			r.addProperty("used_by_structures", "not in any template pool; probably placed by code (grep the namespace's src for the template path)");
		}
	}

	private static JsonArray idList(Set<Identifier> ids, int max) {
		JsonArray array = new JsonArray();
		ids.stream().limit(max).forEach(i -> array.add(i.toString()));
		if (ids.size() > max) {
			array.add("and " + (ids.size() - max) + " more");
		}
		return array;
	}

	/** One text grid per y level, plus the legend. Skipped with a note if it would be too long. */
	private static void addLayers(JsonObject out, Vec3i size, List<StructureTemplates.BlockInfo> blockList, JsonObject args) {
		int sx = size.getX();
		int sy = size.getY();
		int sz = size.getZ();
		if (sx <= 0 || sy <= 0 || sz <= 0) {
			return;
		}
		int yFrom = Math.max(0, Math.min(sy - 1, integer(args, "y_from", 0)));
		int yTo = Math.max(yFrom, Math.min(sy - 1, integer(args, "y_to", sy - 1)));
		long perLayer = (long) sx * sz;
		if (perLayer * (yTo - yFrom + 1) > MAX_LAYER_CELLS) {
			long fits = MAX_LAYER_CELLS / perLayer;
			out.addProperty("layers", fits == 0
				? "Too wide to draw (" + sx + "×" + sz + " per layer)."
				: "Too large to draw all at once; ask for at most " + fits + " layers with y_from/y_to (0 to " + (sy - 1) + ").");
			return;
		}
		BlockState[] cells = new BlockState[(int) perLayer * (yTo - yFrom + 1)];
		Map<Block, Integer> counts = new HashMap<>();
		for (StructureTemplates.BlockInfo b : blockList) {
			BlockPos p = b.pos();
			if (p.getY() < yFrom || p.getY() > yTo || p.getX() < 0 || p.getX() >= sx || p.getZ() < 0 || p.getZ() >= sz) {
				continue;
			}
			cells[(p.getY() - yFrom) * (int) perLayer + p.getZ() * sx + p.getX()] = b.state();
			if (!b.state().isAir()) {
				counts.merge(b.state().getBlock(), 1, Integer::sum);
			}
		}
		Map<Block, Character> symbols = new HashMap<>();
		JsonObject legend = new JsonObject();
		legend.addProperty(".", "air");
		legend.addProperty(" ", "nothing (keeps the world's block)");
		List<Block> byCount = counts.entrySet().stream().sorted(Map.Entry.<Block, Integer>comparingByValue().reversed()).map(Map.Entry::getKey).toList();
		for (int i = 0; i < byCount.size(); i++) {
			char c = i < LAYER_SYMBOLS.length() ? LAYER_SYMBOLS.charAt(i) : '?';
			symbols.put(byCount.get(i), c);
			if (c != '?') {
				legend.addProperty(String.valueOf(c), StructureTemplates.id(byCount.get(i)));
			}
		}
		if (byCount.size() > LAYER_SYMBOLS.length()) {
			legend.addProperty("?", (byCount.size() - LAYER_SYMBOLS.length()) + " rarer blocks");
		}
		JsonArray layers = new JsonArray();
		for (int y = yFrom; y <= yTo; y++) {
			JsonArray rows = new JsonArray();
			for (int z = 0; z < sz; z++) {
				StringBuilder row = new StringBuilder(sx);
				for (int x = 0; x < sx; x++) {
					BlockState state = cells[(y - yFrom) * (int) perLayer + z * sx + x];
					row.append(state == null ? ' ' : state.isAir() ? '.' : symbols.get(state.getBlock()));
				}
				rows.add(row.toString());
			}
			JsonObject layer = new JsonObject();
			layer.addProperty("y", y);
			layer.add("rows_north_to_south", rows);
			layers.add(layer);
		}
		out.add("legend", legend);
		out.add("layers", layers);
	}

	/** "9 wide (x) × 7 tall (y) × 11 deep (z)". */
	private static String sizeText(Vec3i size) {
		return size.getX() + " wide (x) × " + size.getY() + " tall (y) × " + size.getZ() + " deep (z)";
	}

	private static String posText(BlockPos pos) {
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
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
