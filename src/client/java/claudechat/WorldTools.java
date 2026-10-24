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
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
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
	/** Runs the world-independent warm-up in {@link #prepare}, one at a time, at low priority. */
	private final ExecutorService warmUp = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "Claude Chat materials");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});
	private Future<?> warmUpRun;

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
			  "dimension": {"type": "string", "description": "overworld, the_nether, the_end, or a modded dimension id. Default: all, the player's first; only the player's with max_distance."},
			  "max_distance": {"type": "integer", "description": "Only within this many blocks of the player (of the player's position translated there, x/8 in the Nether, for another dimension)."},
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
				+ "has already visited. \"Visited\" means players spent at least a second within 128 blocks of some part of "
				+ "the structure (Minecraft's per-chunk InhabitedTime, at most of any chunk its pieces cover); structures in "
				+ "never-generated land are always unvisited. x/z is the /locate "
				+ "position; each result also has the structure's bounding box, its pieces (rooms and parts, by template "
				+ "or class name, with counts, e.g. \"end_city/ship\", \"village/plains/houses/plains_temple_3\", "
				+ "\"stronghold/library\") and the biomes they occupy (share of sampled cells), all worked out from the seed "
				+ "exactly as generation would, without generating anything. Pieces say what generates, not what's still "
				+ "there (looted chests, a taken elytra). The biome filter matches a structure "
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
			  "with_pieces": {"type": "array", "items": {"type": "string"}, "description": "Only structures that have a piece whose name contains each of these, e.g. [\\"ship\\"] for an end city with a ship, [\\"temple\\"] for a village with a temple, [\\"library\\"] for a stronghold with a library. To learn the names, search without it and read the pieces."},
			  "min_distance": {"type": "integer"},
			  "max_distance": {"type": "integer", "description": "Search radius in blocks (default 8000, max 30000)."},
			  "limit": {"type": "integer", "description": "Max results (default 5, max 20)."}
			}, "required": ["structure"]}
			"""));
		tools.add(tool("find_blocks",
			"Find the placed blocks nearest the player, by block id or tag, optionally narrowed by block state properties "
				+ "and by text in the block's data (its block entity). Examples: a skeleton spawner is block \"spawner\" with "
				+ "data \"skeleton\"; a fully charged respawn anchor is block \"respawn_anchor\" with properties {\"charges\": \"4\"}. "
				+ "Searches the player's dimension first, then the others (only the player's with max_distance); in another "
				+ "dimension, distances are from the player's position translated there (x/8 in the Nether). Covers loaded chunks live and every saved chunk; "
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
			  "visited": {"type": "string", "enum": ["any", "unvisited", "visited"], "description": "Whether players spent at least a second within 128 blocks of the spot. Default: any."},
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
				+ "pieces. Templates with several block variants (palettes) match on any of them. Covers Minecraft, mods, the "
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
				+ "columns west to east, '.' is air, ' ' is nothing (structure void or not part of it: keeps whatever was there), "
				+ "and a legend maps the other "
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
				+ "schematics folder, with name, author, size, and block count where the file records one (Litematica). The files "
				+ "are gzipped NBT and can't be read with Read; use inspect_schematic.",
			"""
			{"type": "object", "properties": {
			  "query": {"type": "string", "description": "Only files whose path (folders separated by /) or schematic name contains this text: the same files inspect_schematic chooses from."}
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
			  "schematic": {"type": "string", "description": "File name (\\"Witch Farm.litematic\\", \\"farms/Witch Farm\\"), part of it, or the schematic's name. If several match, the error lists them."},
			  "compare_with": {"type": "string", "description": "Container to check against: custom name (\\"witch farm\\"; every container with that name counts, e.g. both halves of a double chest), \\"x y z\\", or \\"inventory\\"."},
			  "layers": {"type": "boolean"},
			  "y_from": {"type": "integer"},
			  "y_to": {"type": "integer"}
			}, "required": ["schematic"]}
			"""));
		return tools;
	}

	/** Is a single-player world open? The world tools need its integrated server. */
	public boolean available() {
		return index.server() != null;
	}

	public JsonElement call(String name, JsonObject args) throws Exception {
		return switch (name) {
			case "search_containers" -> searchHolders(args, holder -> holder.kind() != WorldIndex.Holder.Kind.ENTITY || !holder.items().isEmpty(), true);
			case "search_entities" -> searchHolders(args, holder -> holder.kind() == WorldIndex.Holder.Kind.ENTITY, false);
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

	/**
	 * {@code kind} picks which holders the tool covers (the player's inventory and ender chest are holders too, so
	 * "where are my emeralds?" finds the ones in their pockets).
	 */
	private JsonElement searchHolders(JsonObject args, Predicate<WorldIndex.Holder> kind, boolean queryRequired) throws Exception {
		String query = string(args, "query", "").trim().toLowerCase(Locale.ROOT);
		if (queryRequired && query.isEmpty()) {
			throw new IllegalArgumentException("query is required");
		}
		int maxDistance = integer(args, "max_distance", Integer.MAX_VALUE);
		int limit = Math.max(1, Math.min(50, integer(args, "limit", 10)));

		WorldIndex.Snapshot snapshot = index.snapshot(WorldIndex.Detail.HOLDERS);
		MinecraftServer server = snapshot.server();
		WorldIndex.PlayerInfo host = snapshot.host();
		Optional<ResourceKey<Level>> requested = Dimensions.parse(string(args, "dimension", ""), server.levelKeys());
		List<ResourceKey<Level>> dimensions = Dimensions.searchOrder(host.dimension(), requested, maxDistance < Integer.MAX_VALUE, server.levelKeys());
		Map<ResourceKey<Level>, Origin> origins = new HashMap<>();
		dimensions.forEach(dimension -> origins.put(dimension, originIn(server, host.origin(), dimension)));

		record Hit(WorldIndex.Holder holder, Origin origin, List<String> matches, int count, double distance) {
		}
		List<WorldIndex.Holder> candidates = new ArrayList<>(snapshot.holders());
		snapshot.players().forEach(player -> candidates.addAll(player.holders()));
		List<Hit> hits = new ArrayList<>();
		for (WorldIndex.Holder holder : candidates) {
			Origin origin = origins.get(holder.dimension());
			if (origin == null || !kind.test(holder)) {
				continue;
			}
			double distance = origin.distanceTo(holder.x(), holder.y(), holder.z());
			if (distance > maxDistance) {
				continue;
			}
			List<String> matches = new ArrayList<>();
			int[] count = {0};
			collectMatches(holder.items(), query, null, matches, count);
			if (matches.isEmpty() && !holderMatches(holder, query)) {
				continue;
			}
			hits.add(new Hit(holder, origin, matches, count[0], distance));
		}
		hits.sort(Comparator.comparingInt((Hit hit) -> dimensions.indexOf(hit.holder().dimension())).thenComparingDouble(Hit::distance));
		List<Hit> shown = hits.subList(0, Math.min(limit, hits.size()));

		JsonObject out = new JsonObject();
		out.add("player", playerPosition(host));
		addOriginNote(out, shown.stream().map(Hit::origin).toList());
		out.addProperty("total_found", hits.size());
		out.addProperty("total_matching_items", hits.stream().mapToInt(Hit::count).sum());
		JsonArray results = new JsonArray();
		for (Hit hit : shown) {
			WorldIndex.Holder h = hit.holder();
			JsonObject r = new JsonObject();
			r.addProperty("what", describe(h));
			if (h.name() != null) {
				r.addProperty("name", h.name());
			}
			if (h.tamed()) {
				r.addProperty("tamed", true);
			}
			r.addProperty("dimension", Dimensions.name(h.dimension()));
			r.addProperty("x", h.x());
			r.addProperty("y", h.y());
			r.addProperty("z", h.z());
			addDirection(r, hit.origin(), h.x(), (double) h.y(), h.z());
			r.addProperty("data", h.provenance().label());
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

	/** The holder itself matches: its custom name, block or entity type, or loot table. Everything does for no query. */
	private static boolean holderMatches(WorldIndex.Holder holder, String query) {
		return query.isEmpty() || contains(holder.name(), query)
			|| (!holder.kind().ofPlayer() && contains(holder.type(), query))
			|| (holder.lootTable() != null && contains(holder.lootTable(), query));
	}

	/** "chest", "entity wolf", "inventory of Steve". */
	private static String describe(WorldIndex.Holder holder) {
		return switch (holder.kind()) {
			case BLOCK -> holder.type();
			case ENTITY -> "entity " + holder.type();
			case INVENTORY -> "inventory of " + holder.type();
			case ENDER_CHEST -> "ender chest of " + holder.type() + " (open any ender chest)";
		};
	}

	/** Every player, the host (whose game this is) first. */
	private JsonElement getPlayer() throws Exception {
		WorldIndex.Snapshot snapshot = index.snapshot(WorldIndex.Detail.VISITS);
		UUID hostId = snapshot.host().id();
		List<WorldIndex.PlayerInfo> hostFirst = snapshot.players().stream()
			.sorted(Comparator.comparing((WorldIndex.PlayerInfo p) -> !p.id().equals(hostId))).toList();
		JsonArray players = new JsonArray();
		for (WorldIndex.PlayerInfo p : hostFirst) {
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
		Visit.Filter visited = Visit.Filter.parse(string(args, "visited", "any"));
		int minDistance = integer(args, "min_distance", 0);
		int maxDistance = Math.max(16, Math.min(30_000, integer(args, "max_distance", 8000)));
		int limit = Math.max(1, Math.min(20, integer(args, "limit", 5)));

		WorldIndex.Snapshot snapshot = index.snapshot(WorldIndex.Detail.VISITS);
		MinecraftServer server = snapshot.server();
		WorldIndex.PlayerInfo host = snapshot.host();
		if (!server.getWorldGenSettings().options().generateStructures()) {
			throw new IllegalStateException("Structures are turned off in this world.");
		}
		List<Holder<Structure>> structures = RegistryQuery.resolve(server.registryAccess(), Registries.STRUCTURE, query,
			RegistryQuery.Precedence.TAG_FIRST);
		if (structures.isEmpty()) {
			throw new IllegalArgumentException("No structure or structure tag matches \"" + query + "\". Structure ids are under data/<namespace>/worldgen/structure.");
		}
		Set<String> biomes = biomeIds(server, string(args, "biome", ""));
		int minShare = Math.max(0, Math.min(100, integer(args, "min_biome_share", 0)));
		if (minShare > 0 && biomes.isEmpty()) {
			throw new IllegalArgumentException("min_biome_share needs a biome.");
		}
		// "ship" or "temple" → matched against piece names like "end_city/ship" or "village/plains/houses/plains_temple_3".
		List<String> pieceTerms = strings(args, "with_pieces").stream()
			.map(t -> t.toLowerCase(Locale.ROOT).replace(' ', '_')).toList();

		Optional<ResourceKey<Level>> requested = Dimensions.parse(string(args, "dimension", ""), server.levelKeys());
		// The generator's placements are worked out lazily on first use, which only the server thread may do.
		List<ResourceKey<Level>> generatesIn = ServerTasks.call(server, () -> dimensionsWhere(server, level -> structures.stream()
			.anyMatch(s -> !level.getChunkSource().getGeneratorState().getPlacementsForStructure(s).isEmpty())),
			Duration.ofSeconds(20), "Finding where the structure generates");
		ResourceKey<Level> dimension = Dimensions.whereItExists(host.dimension(), requested, generatesIn, "\"" + query + "\"");
		Origin origin = originIn(server, host.origin(), dimension);
		StructureFinder.Request request = new StructureFinder.Request(structures, minDistance, maxDistance, limit, visited, pieceTerms,
			biomes, minShare);
		List<StructureFinder.Found> found = StructureFinder.find(server.getLevel(dimension), origin, request, snapshot);

		JsonObject out = new JsonObject();
		out.add("player", playerPosition(host));
		out.addProperty("searched", structures.size() == 1 ? RegistryQuery.id(structures.getFirst())
			: structures.size() + " structure types matching \"" + query + "\"");
		out.addProperty("dimension", Dimensions.name(dimension));
		addOriginNote(out, List.of(origin));
		JsonArray results = new JsonArray();
		for (StructureFinder.Found f : found) {
			JsonObject r = new JsonObject();
			r.addProperty("structure", f.structure());
			r.addProperty("x", f.x());
			r.addProperty("z", f.z());
			addDirection(r, origin, f.x(), null, f.z());
			r.addProperty("generated", f.visit().generated());
			r.addProperty("time_players_spent_nearby_seconds", f.visit().seconds());
			r.addProperty("visited", f.visit().visited());
			StructureFinder.Footprint footprint = f.footprint();
			BoundingBox box = footprint.bounds();
			r.addProperty("bounds", box.minX() + " " + box.minY() + " " + box.minZ() + " to " + box.maxX() + " " + box.maxY() + " " + box.maxZ());
			JsonObject shares = new JsonObject();
			footprint.biomeSamples().entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.forEach(e -> shares.addProperty(e.getKey(), Math.max(1, Math.round(100f * e.getValue() / footprint.totalSamples())) + "%"));
			r.add("biomes", shares);
			if (!biomes.isEmpty()) {
				r.addProperty("share_in_requested_biome", Math.round(footprint.share(biomes)) + "%");
			}
			JsonObject pieces = new JsonObject();
			f.pieces().forEach(pieces::addProperty);
			if (!pieces.isEmpty()) {
				r.add("pieces", pieces);
			}
			results.add(r);
		}
		out.add("results", results);
		if (found.isEmpty()) {
			out.addProperty("note", "None found within " + maxDistance + " blocks with these filters.");
		}
		return out;
	}

	/** The ids of the biomes a biome argument names; none for a blank argument. */
	private static Set<String> biomeIds(MinecraftServer server, String query) {
		if (query.isBlank()) {
			return Set.of();
		}
		Set<String> biomes = new HashSet<>();
		RegistryQuery.resolve(server.registryAccess(), Registries.BIOME, query, RegistryQuery.Precedence.ID_FIRST)
			.forEach(b -> biomes.add(RegistryQuery.id(b)));
		if (biomes.isEmpty()) {
			throw new IllegalArgumentException("No biome or biome tag matches \"" + query + "\". Biome ids are under data/<namespace>/worldgen/biome.");
		}
		return biomes;
	}

	// --- blocks ---------------------------------------------------------------------------------------------------

	private JsonElement findBlocks(JsonObject args) throws Exception {
		String blockQuery = string(args, "block", "");
		String data = string(args, "data", "").trim().toLowerCase(Locale.ROOT);
		int maxDistance = integer(args, "max_distance", Integer.MAX_VALUE);
		int limit = Math.max(1, Math.min(30, integer(args, "limit", 5)));

		WorldIndex.Snapshot snapshot = index.snapshot(WorldIndex.Detail.VISITS);
		MinecraftServer server = snapshot.server();
		WorldIndex.PlayerInfo host = snapshot.host();
		List<Block> blocks = BlockFinder.resolve(server.registryAccess(), blockQuery);
		if (blocks.isEmpty()) {
			throw new IllegalArgumentException("No block or block tag matches \"" + blockQuery + "\".");
		}
		Map<String, String> properties = properties(args);
		checkProperties(blocks, properties);
		BlockFinder.Query query = new BlockFinder.Query(new HashSet<>(blocks), properties, data);
		Optional<ResourceKey<Level>> requested = Dimensions.parse(string(args, "dimension", ""), server.levelKeys());
		List<ResourceKey<Level>> dimensions = Dimensions.searchOrder(host.dimension(), requested, maxDistance < Integer.MAX_VALUE, server.levelKeys());

		// The player's dimension first; other dimensions only fill the remaining slots.
		JsonArray results = new JsonArray();
		List<Origin> origins = new ArrayList<>();
		int chunksRead = 0;
		boolean truncated = false;
		for (ResourceKey<Level> dimension : dimensions) {
			if (results.size() >= limit) {
				break;
			}
			Origin origin = originIn(server, host.origin(), dimension);
			BlockFinder.Result result = BlockFinder.find(snapshot, server.getLevel(dimension), query, origin, maxDistance, limit - results.size());
			chunksRead += result.savedChunksRead();
			truncated |= result.truncated();
			for (BlockFinder.Found found : result.found()) {
				results.add(blockJson(found, origin));
				origins.add(origin);
			}
		}

		JsonObject out = new JsonObject();
		out.add("player", playerPosition(host));
		addOriginNote(out, origins);
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

	private static JsonObject blockJson(BlockFinder.Found found, Origin origin) {
		JsonObject r = new JsonObject();
		addBlockState(r, found.state());
		r.addProperty("dimension", Dimensions.name(origin.dimension()));
		r.addProperty("x", found.pos().getX());
		r.addProperty("y", found.pos().getY());
		r.addProperty("z", found.pos().getZ());
		addDirection(r, origin, found.pos().getX(), (double) found.pos().getY(), found.pos().getZ());
		r.addProperty("data", found.provenance().label());
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
		Visit.Filter visited = Visit.Filter.parse(string(args, "visited", "any"));
		int minSpacing = Math.max(32, integer(args, "min_spacing", 256));
		int maxDistance = Math.max(32, Math.min(30_000, integer(args, "max_distance", 6400)));
		int limit = Math.max(1, Math.min(20, integer(args, "limit", 5)));

		WorldIndex.Snapshot snapshot = index.snapshot(WorldIndex.Detail.VISITS);
		MinecraftServer server = snapshot.server();
		WorldIndex.PlayerInfo host = snapshot.host();
		Set<Holder<Biome>> biomes = new HashSet<>(RegistryQuery.resolve(server.registryAccess(), Registries.BIOME, query, RegistryQuery.Precedence.ID_FIRST));
		if (biomes.isEmpty()) {
			throw new IllegalArgumentException("No biome or biome tag matches \"" + query + "\". Biome ids are under data/<namespace>/worldgen/biome.");
		}
		Optional<ResourceKey<Level>> requested = Dimensions.parse(string(args, "dimension", ""), server.levelKeys());
		List<ResourceKey<Level>> existsIn = dimensionsWhere(server,
			level -> level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes().stream().anyMatch(biomes::contains));
		ResourceKey<Level> dimension = Dimensions.whereItExists(host.dimension(), requested, existsIn, "\"" + query + "\"");
		Origin origin = originIn(server, host.origin(), dimension);
		BiomeFinder.Request request = new BiomeFinder.Request(biomes, maxDistance, minSpacing, limit, visited);
		BiomeFinder.Result result = BiomeFinder.find(server.getLevel(dimension), origin, request, snapshot.visits(dimension));

		JsonObject out = new JsonObject();
		out.add("player", playerPosition(host));
		out.addProperty("searched", biomes.size() == 1 ? RegistryQuery.id(biomes.iterator().next())
			: biomes.size() + " biomes matching \"" + query + "\"");
		out.addProperty("dimension", Dimensions.name(dimension));
		addOriginNote(out, List.of(origin));
		JsonArray results = new JsonArray();
		for (BiomeFinder.Found f : result.found()) {
			JsonObject r = new JsonObject();
			r.addProperty("biome", f.biome());
			r.addProperty("x", f.x());
			r.addProperty("y", f.y());
			r.addProperty("z", f.z());
			addDirection(r, origin, f.x(), null, f.z());
			r.addProperty("generated", f.visit().generated());
			r.addProperty("visited", f.visit().visited());
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

		WorldIndex.Snapshot snapshot = index.snapshot(WorldIndex.Detail.VISITS);
		MinecraftServer server = snapshot.server();
		WorldIndex.PlayerInfo host = snapshot.host();
		ResourceKey<Level> dimension = Dimensions.parse(string(args, "dimension", ""), server.levelKeys()).orElse(host.dimension());
		Origin origin = originIn(server, host.origin(), dimension);

		LocationInspector.Inspection inspection = LocationInspector.inspect(snapshot, server.getLevel(dimension), x, y, z);
		JsonObject out = new JsonObject();
		out.addProperty("dimension", Dimensions.name(dimension));
		out.addProperty("x", x);
		out.addProperty("y", inspection.y());
		out.addProperty("z", z);
		addDirection(out, origin, x, (double) inspection.y(), z);
		addOriginNote(out, List.of(origin));
		out.addProperty("source", inspection.source().label());
		addBlockState(out, inspection.state());
		addBlockEntity(out, inspection.blockEntity());
		out.addProperty("biome", inspection.biome());
		if (inspection.surfaceY() != null) {
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

	/**
	 * Slow first-use work, in the background when the world opens or reloads: the structure template index, the
	 * recipes for material breakdowns, and the data fixer's rules for old schematics. The last two don't depend on the
	 * world, so one run at a time is enough.
	 */
	public void prepare(MinecraftServer server) {
		templates.prepare(server);
		synchronized (warmUp) {
			if (warmUpRun == null || warmUpRun.isDone()) {
				warmUpRun = warmUp.submit(() -> {
					long start = System.nanoTime();
					materials.prepare();
					long recipesDone = System.nanoTime();
					Schematics.prepare();
					ClaudeChatClient.LOGGER.info("Prepared recipes in {} ms and the schematic data fixer in {} ms",
						(recipesDone - start) / 1_000_000, (System.nanoTime() - recipesDone) / 1_000_000);
				});
			}
		}
	}

	/**
	 * Stops the background work for the world that's closing: the template index build, and a warm-up that hasn't
	 * started yet. A warm-up already running finishes; it doesn't hold the server.
	 */
	public void clearTemplates() {
		templates.stop();
		synchronized (warmUp) {
			if (warmUpRun != null) {
				warmUpRun.cancel(false);
				warmUpRun = null;
			}
		}
	}

	private JsonElement searchTemplates(JsonObject args) throws Exception {
		MinecraftServer server = index.server();
		if (server == null) {
			throw new IllegalStateException("No single-player world open.");
		}
		List<String> terms = strings(args, "blocks");
		String query = string(args, "query", "").trim();
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

		StructureTemplates.Index templateIndex = templates.index(server);
		StructureTemplates.SearchResult found = StructureTemplates.search(templateIndex,
			new StructureTemplates.Query(wanted, query, integer(args, "max_size", Integer.MAX_VALUE)));
		List<StructureTemplates.Hit> hits = found.hits();

		JsonObject out = new JsonObject();
		JsonObject searched = new JsonObject();
		wanted.forEach((term, blocks) -> searched.addProperty(term, blocks.size() == 1
			? StructureTemplates.id(blocks.iterator().next()) : blocks.size() + " blocks"));
		if (!wanted.isEmpty()) {
			out.add("searched_blocks", searched);
		}
		out.addProperty("templates_indexed", templateIndex.templates().size());
		if (!templateIndex.unreadable().isEmpty()) {
			out.addProperty("templates_unreadable", templateIndex.unreadable().size());
		}
		out.addProperty("total_found", hits.size());
		if (found.noneHasAll()) {
			out.addProperty("note", "No template has all of these blocks; these have the most.");
		}
		JsonArray results = new JsonArray();
		for (StructureTemplates.Hit hit : hits.subList(0, Math.min(limit, hits.size()))) {
			StructureTemplates.Summary t = hit.template();
			JsonObject r = new JsonObject();
			r.addProperty("template", t.id().toString());
			r.addProperty("from", t.origin().describe());
			r.addProperty("size", sizeText(t.size()));
			r.addProperty("solid_blocks", t.countedBlocks());
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
			List<String> similar = templateIndex.similarTo(id, 10).stream().map(Identifier::toString).toList();
			throw new IllegalArgumentException("No structure template " + id + "."
				+ (similar.isEmpty() ? " Use search_structure_templates to find one." : " Similar: " + String.join(", ", similar)));
		}

		JsonObject out = new JsonObject();
		out.addProperty("template", id.toString());
		out.addProperty("from", t.origin().describe());
		out.addProperty("size", sizeText(t.volume().size()));
		if (t.palettes() > 1) {
			out.addProperty("palettes", t.palettes() + " block variants, one picked at random when placed; showing the first");
		}
		Map<Block, Integer> counts = t.volume().countedBlocks();
		out.addProperty("solid_blocks", counts.values().stream().mapToInt(Integer::intValue).sum());
		JsonObject blocks = new JsonObject();
		counts.entrySet().stream().sorted(Map.Entry.<Block, Integer>comparingByValue().reversed()).limit(100)
			.forEach(e -> blocks.addProperty(StructureTemplates.id(e.getKey()), e.getValue()));
		out.add("blocks", blocks);
		addBlockEntities(out, t.volume());
		addTemplateUsage(out, templateIndex, id, 20);
		if (args != null && args.has("layers") && args.get("layers").getAsBoolean()) {
			addLayers(out, t.volume(), args);
		}
		return out;
	}

	/** "block_entities" (chests with loot tables, spawners, signs...), "entities" and "jigsaw_connectors". */
	private static void addBlockEntities(JsonObject out, BlockVolume volume) {
		JsonArray blockEntities = new JsonArray();
		JsonArray jigsaws = new JsonArray();
		int moreBlockEntities = 0;
		for (BlockVolume.BlockEntity b : volume.blockEntities()) {
			JsonObject r = new JsonObject();
			if (b.state().is(Blocks.JIGSAW)) {
				r.addProperty("pos", posText(b.pos()));
				String facing = BlockFinder.properties(b.state()).get("orientation");
				if (facing != null) {
					r.addProperty("facing", facing);
				}
				for (String key : List.of("pool", "name", "target", "final_state")) {
					String value = b.data().getStringOr(key, "");
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
			b.data().getString("LootTable").ifPresent(loot -> r.addProperty("loot_table", loot));
			CompoundTag rest = b.data().copy();
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
		if (!volume.entities().isEmpty()) {
			JsonArray entities = new JsonArray();
			volume.entities().stream().limit(30).forEach(e -> entities.add(e.type() + " at " + posText(e.pos())));
			out.add("entities", entities);
		}
		if (!jigsaws.isEmpty()) {
			out.add("jigsaw_connectors", jigsaws);
		}
	}

	// --- schematics -----------------------------------------------------------------------------------------------

	private JsonElement listSchematics(JsonObject args) throws Exception {
		String query = string(args, "query", "").trim();
		Schematics.Folder folder = Schematics.folder();
		JsonObject out = new JsonObject();
		out.addProperty("folder", folder.dir().toString());
		JsonArray results = new JsonArray();
		for (Schematics.Entry entry : folder.entries()) {
			if (!query.isEmpty() && !Schematics.matches(entry, query)) {
				continue;
			}
			JsonObject r = new JsonObject();
			r.addProperty("file", entry.path());
			switch (entry) {
				case Schematics.Readable readable -> {
					Schematics.Info info = readable.info();
					r.addProperty("name", info.name());
					if (!info.author().isEmpty()) {
						r.addProperty("author", info.author());
					}
					r.addProperty("size", sizeText(info.size()));
					info.recordedBlocks().ifPresent(blocks -> r.addProperty("blocks", blocks));
				}
				case Schematics.Unreadable unreadable -> r.addProperty("error", "unreadable: " + unreadable.error());
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
		Schematics.Folder folder = Schematics.folder();
		Schematics.Entry entry = switch (Schematics.match(folder.entries(), query)) {
			case Schematics.Found found -> found.entry();
			case Schematics.Ambiguous ambiguous -> throw new IllegalArgumentException("\"" + query + "\" matches several schematics: "
				+ String.join(", ", ambiguous.candidates().stream().limit(20).map(Schematics.Entry::path).toList()) + ". Give the file name.");
			case Schematics.NotFound notFound -> throw new IllegalArgumentException(
				"No schematic matching \"" + query + "\" in " + folder.dir() + ". Use list_schematics.");
		};
		Schematics.Schematic s = Schematics.load(entry.file());
		Schematics.Info info = s.info();

		JsonObject out = new JsonObject();
		out.addProperty("file", entry.path());
		out.addProperty("name", info.name());
		if (!info.author().isEmpty()) {
			out.addProperty("author", info.author());
		}
		if (!info.description().isEmpty()) {
			out.addProperty("description", info.description());
		}
		out.addProperty("size", sizeText(info.size()));
		if (s.regions().size() > 1) {
			JsonArray regions = new JsonArray();
			s.regions().forEach(r -> regions.add(r.name() + ": " + sizeText(r.size()) + " at " + posText(r.position())));
			out.add("regions", regions);
		}

		Map<String, Integer> noItem = new TreeMap<>();
		Map<String, Integer> placed = Materials.asPlaced(s.volume(), noItem);
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
		addBlockEntities(out, s.volume());
		if (args.has("layers") && args.get("layers").getAsBoolean()) {
			addLayers(out, s.volume(), args);
		}
		return out;
	}

	/** Checks the containers matching {@code query} against the materials; see inspect_schematic's description. */
	private JsonObject compare(String query, Map<String, Integer> placed) throws Exception {
		WorldIndex.Snapshot snapshot = index.snapshot(WorldIndex.Detail.HOLDERS);
		WorldIndex.PlayerInfo player = snapshot.host();
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
				// Only the player's dimension: chests at the same x y z in the Overworld and the Nether are different chests.
				if (h.kind() == WorldIndex.Holder.Kind.ENTITY || !h.dimension().equals(player.dimension())) {
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
				List<String> named = snapshot.holders().stream().filter(h -> h.name() != null && h.kind() != WorldIndex.Holder.Kind.ENTITY)
					.map(h -> "\"" + h.name() + "\" at " + h.x() + " " + h.y() + " " + h.z()).distinct().limit(20).toList();
				throw new IllegalArgumentException("No container matching \"" + query + "\"." + (named.isEmpty()
					? " There are no named containers; give its coordinates instead." : " Named containers: " + String.join(", ", named)));
			}
			for (WorldIndex.Holder h : matches) {
				items.addAll(h.items());
				used.add(h.type() + (h.name() != null ? " \"" + h.name() + "\"" : "") + " at " + h.x() + " " + h.y() + " " + h.z()
					+ (h.provenance() == Provenance.LIVE ? "" : " (" + h.provenance().label() + ")"));
			}
		}
		Map<String, Integer> stock = new TreeMap<>();
		flatten(items, stock);

		JsonObject out = new JsonObject();
		JsonArray containers = new JsonArray();
		used.forEach(containers::add);
		out.add("containers", containers);
		Materials.Comparison comparison = materials.compare(placed, stock);
		out.add("missing_as_placed", countsJson(comparison.missingAsPlaced()));
		out.add("still_missing_after_crafting_from_contents", countsJson(comparison.afterCrafting().remaining()));
		if (!comparison.afterCrafting().crafts().isEmpty()) {
			out.add("crafting_needed", craftsJson(comparison.afterCrafting().crafts()));
		}
		out.addProperty("complete", comparison.complete());
		out.add("useful_contents", countsJson(comparison.useful()));
		if (!comparison.notNeeded().isEmpty()) {
			out.add("not_needed", countsJson(comparison.notNeeded()));
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
		Set<Identifier> structures = index.structuresUsing(id);
		Set<Identifier> pools = index.poolsListing(id);
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
	private static void addLayers(JsonObject out, BlockVolume volume, JsonObject args) {
		Vec3i size = volume.size();
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
		// Symbols go to blocks by how common they are in the drawn layers, so the legend fits what's shown.
		Map<Block, Integer> counts = new HashMap<>();
		for (int y = yFrom; y <= yTo; y++) {
			for (int z = 0; z < sz; z++) {
				for (int x = 0; x < sx; x++) {
					if (volume.kind(x, y, z) == BlockVolume.Kind.BLOCK) {
						counts.merge(volume.state(x, y, z).getBlock(), 1, Integer::sum);
					}
				}
			}
		}
		Map<Block, Character> symbols = new HashMap<>();
		JsonObject legend = new JsonObject();
		legend.addProperty(".", "air");
		legend.addProperty(" ", "nothing: structure void, or not part of it (keeps the world's block)");
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
					char cell = switch (volume.kind(x, y, z)) {
						case ABSENT, VOID -> ' ';
						case AIR -> '.';
						case BLOCK -> symbols.get(volume.state(x, y, z).getBlock());
					};
					row.append(cell);
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

	/** The player's position in {@code dimension}: as it is in their own, translated (x/8 into the Nether) in another. */
	private static Origin originIn(MinecraftServer server, Origin home, ResourceKey<Level> dimension) {
		double scale = DimensionType.getTeleportationScale(server.getLevel(home.dimension()).dimensionType(),
			server.getLevel(dimension).dimensionType());
		return home.translatedTo(dimension, scale);
	}

	/** The world's dimensions where {@code test} holds, in the server's order. */
	private static List<ResourceKey<Level>> dimensionsWhere(MinecraftServer server, Predicate<ServerLevel> test) {
		List<ResourceKey<Level>> dimensions = new ArrayList<>();
		for (ServerLevel level : server.getAllLevels()) {
			if (test.test(level)) {
				dimensions.add(level.dimension());
			}
		}
		return dimensions;
	}

	/** "Distances and directions in the_nether are from ..." for each origin translated from the player's dimension. */
	private static void addOriginNote(JsonObject out, Collection<Origin> origins) {
		List<String> notes = origins.stream().filter(Origin::translated).distinct()
			.map(origin -> "Distances and directions in " + Dimensions.name(origin.dimension()) + " are from the player's position translated there: "
				+ Math.round(origin.x()) + " ~ " + Math.round(origin.z()) + ".")
			.toList();
		if (!notes.isEmpty()) {
			out.addProperty("note_on_distances", String.join(" ", notes));
		}
	}

	/** "distance": 2830, "direction": "south-east" (plus "blocks_up"/"blocks_down" when y is known), from an origin in the same dimension. */
	private static void addDirection(JsonObject r, Origin origin, double x, Double y, double z) {
		double dx = x - origin.x();
		double dz = z - origin.z();
		r.addProperty("distance_blocks", Math.round(y == null ? origin.horizontalDistanceTo(x, z) : origin.distanceTo(x, y, z)));
		r.addProperty("direction", compass(dx, dz));
		if (y != null) {
			long dy = Math.round(y - origin.y());
			if (Math.abs(dy) >= 3) {
				r.addProperty(dy > 0 ? "blocks_up" : "blocks_down", Math.abs(dy));
			}
		}
	}

	/** Minecraft compass: north is -Z, east is +X. */
	static String compass(double dx, double dz) {
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
		o.addProperty("dimension", Dimensions.name(player.dimension()));
		o.addProperty("x", (int) Math.floor(player.x()));
		o.addProperty("y", (int) Math.floor(player.y()));
		o.addProperty("z", (int) Math.floor(player.z()));
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

	static JsonObject tool(String name, String description, String schema) {
		JsonObject tool = new JsonObject();
		tool.addProperty("name", name);
		tool.addProperty("description", description);
		tool.add("inputSchema", JsonParser.parseString(schema));
		return tool;
	}

	private static String string(JsonObject args, String key, String fallback) {
		return args != null && args.has(key) && args.get(key).isJsonPrimitive() ? args.get(key).getAsString() : fallback;
	}

	/** A string array, or a comma-separated string, trimmed and without empty entries. */
	private static List<String> strings(JsonObject args, String key) {
		List<String> values = new ArrayList<>();
		if (args != null && args.has(key)) {
			JsonElement element = args.get(key);
			if (element.isJsonArray()) {
				element.getAsJsonArray().forEach(e -> values.add(e.getAsString()));
			} else if (element.isJsonPrimitive()) {
				values.addAll(List.of(element.getAsString().split(",")));
			}
			values.replaceAll(String::trim);
			values.removeIf(String::isEmpty);
		}
		return values;
	}

	private static int integer(JsonObject args, String key, int fallback) {
		try {
			return args != null && args.has(key) && args.get(key).isJsonPrimitive() ? args.get(key).getAsInt() : fallback;
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
