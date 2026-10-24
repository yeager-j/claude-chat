package claudechat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;

/**
 * The tools Claude gets for the player's single-player world, served over MCP by {@link McpServer}. They search the
 * live world (plus saved chunks) and do the distance math, so Claude can just report coordinates and directions.
 */
public final class WorldTools {
	/** Farther than any world border reaches, for "only within this many blocks". */
	private static final int WORLD_SPAN = 60_000_000;
	/** What "visited" means, in the descriptions, from the rule that decides it. */
	private static final String VISITED_RULE = "players spent at least " + duration(Visit.VISITED_TICKS) + " within 128 blocks";

	// --- arguments, one definition each, shared where they mean the same -----------------------------------------

	private static final Param<String> DIMENSION_ALL = Param.text("dimension",
		"overworld, the_nether, the_end, or a modded dimension id. Default: all, the player's first; only the player's with max_distance.");
	private static final Param<String> DIMENSION_WHERE_IT_EXISTS = Param.text("dimension",
		"Default: the player's dimension, or where it generates if not there (a fortress asked about from the Overworld is "
			+ "searched in the Nether, around the player's position / 8).");
	private static final Param<OptionalInt> NEAR = Param.optionalInteger("max_distance",
		"Only within this many blocks of the player (of the player's position translated there, x/8 in the Nether, for another dimension).",
		1, WORLD_SPAN);
	private static final Param<Visit.Filter> VISITED = Param.choice("visited", "Whether " + VISITED_RULE + " of it.", Visit.Filter.ANY);
	private static final Param<Integer> FEW_RESULTS = Param.integer("limit", "Max results.", 5, 1, 20);
	private static final Param<Boolean> LAYERS = Param.flag("layers", "Also draw it layer by layer.", false);
	private static final Param<OptionalInt> Y_FROM = Param.optionalInteger("y_from", "With layers: lowest y to draw, 0 = bottom. Default 0.");
	private static final Param<OptionalInt> Y_TO = Param.optionalInteger("y_to", "With layers: highest y to draw. Default: the top.");

	private static final Param<String> ITEM_QUERY = Param.requiredText("query",
		"Item id or name, e.g. \"diamond\", \"minecraft:diamond\", \"mending\", or a container's custom name.");
	private static final Param<Integer> HOLDER_LIMIT = Param.integer("limit", "Max results.", 10, 1, 50);
	private static final Param<String> ENTITY_QUERY = Param.text("query",
		"Entity type (\"wolf\"), custom name (\"Rex\") or held item. Empty: all.");

	private static final Param<String> STRUCTURE = Param.requiredText("structure",
		"Structure id (\"minecraft:village_plains\"), tag (\"#minecraft:village\"), or a word matched against tags and ids "
			+ "(\"village\", \"ruined_portal\").");
	private static final Param<String> IN_BIOME = Param.text("biome",
		"Only structures with a piece in this biome: id (\"minecraft:warped_forest\"), tag (\"#minecraft:is_ocean\"), or a word (\"warped\").");
	private static final Param<Integer> MIN_BIOME_SHARE = Param.integer("min_biome_share",
		"With biome: only structures at least this percent in it, e.g. 75 for \"mostly in a warped forest\". Shares of several "
			+ "matching biomes (a tag) add up. 0: any piece.", 0, 0, 100);
	private static final Param<List<String>> WITH_PIECES = Param.texts("with_pieces",
		"Only structures that have a piece whose name contains each of these, e.g. [\"ship\"] for an end city with a ship, "
			+ "[\"temple\"] for a village with a temple, [\"library\"] for a stronghold with a library. To learn the names, "
			+ "search without it and read the pieces.");
	private static final Param<Integer> MIN_DISTANCE = Param.integer("min_distance", "Skip structures closer than this many blocks.", 0, 0, 30_000);
	private static final Param<Integer> STRUCTURE_RANGE = Param.integer("max_distance", "Search radius in blocks.", 8000, 16, 30_000);

	private static final Param<String> BLOCK = Param.requiredText("block",
		"Block id (\"minecraft:respawn_anchor\"), tag (\"#minecraft:beds\"), or a word matched against tags and ids (\"bed\", \"anchor\").");
	private static final Param<Map<String, String>> PROPERTIES = Param.custom("properties", propertiesSchema(), Map.of(), WorldTools::properties);
	private static final Param<String> BLOCK_DATA = Param.text("data",
		"Text the block entity's data must contain (case-insensitive), e.g. the mob in a spawner (\"skeleton\"), sign text or a custom name.");
	private static final Param<String> DIMENSION_ONLY = Param.text("dimension", "Only this dimension (overworld, the_nether, the_end, or a modded id).");
	private static final Param<OptionalInt> BLOCKS_NEAR = Param.optionalInteger("max_distance", "Only within this many blocks of the player.", 1, WORLD_SPAN);
	private static final Param<Integer> BLOCK_LIMIT = Param.integer("limit", "Max results.", 5, 1, 30);

	private static final Param<String> BIOME = Param.requiredText("biome",
		"Biome id (\"minecraft:cherry_grove\"), tag (\"#minecraft:is_ocean\"), or a word (\"cherry\", \"mushroom\").");
	private static final Param<Integer> MIN_SPACING = Param.integer("min_spacing", "Hits closer together than this many blocks count as one patch.",
		256, 32, 30_000);
	private static final Param<Integer> BIOME_RANGE = Param.integer("max_distance", "Search radius in blocks; /locate biome's is 6400.", 6400, 32, 30_000);

	private static final Param<Integer> X = Param.requiredInteger("x", "");
	private static final Param<OptionalInt> Y = Param.optionalInteger("y",
		"Omit for the top block of the column (not allowed in the Nether, whose top is the roof).");
	private static final Param<Integer> Z = Param.requiredInteger("z", "");
	private static final Param<String> DIMENSION_OR_PLAYERS = Param.text("dimension", "Default: the player's dimension.");

	private static final Param<List<String>> TEMPLATE_BLOCKS = Param.texts("blocks",
		"Blocks the template should contain: ids (\"minecraft:mossy_stone_bricks\"), tags (\"#minecraft:logs\") or words (\"spawner\", \"lantern\").");
	private static final Param<String> TEMPLATE_QUERY = Param.text("query",
		"Only templates whose id, or the id of a structure using them, contains this text, e.g. a mod namespace (\"dungeons_arise\") or \"village\".");
	private static final Param<OptionalInt> MAX_SIZE = Param.optionalInteger("max_size",
		"Only templates no larger than this many blocks along any side.", 1, Integer.MAX_VALUE);
	private static final Param<Integer> TEMPLATE_LIMIT = Param.integer("limit", "Max results.", 10, 1, 30);
	private static final Param<String> TEMPLATE = Param.requiredText("template",
		"Template id, e.g. \"minecraft:village/plains/houses/plains_small_house_1\" (the path under data/<namespace>/structure/, without .nbt).");

	private static final Param<String> SCHEMATIC_QUERY = Param.text("query",
		"Only files whose path (folders separated by /) or schematic name contains this text: the same files inspect_schematic chooses from.");
	private static final Param<String> SCHEMATIC = Param.requiredText("schematic",
		"File name (\"Witch Farm.litematic\", \"farms/Witch Farm\"), part of it, or the schematic's name. If several match, the error lists them.");
	private static final Param<String> COMPARE_WITH = Param.text("compare_with",
		"Container to check against: custom name (\"witch farm\"; every container with that name counts, e.g. both halves of a "
			+ "double chest), \"x y z\", or \"inventory\" for what the player carries plus their ender chest.");

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

	/** Is a single-player world open? The world tools need its integrated server. */
	public boolean available() {
		return index.server() != null;
	}

	/** The world tools, offered while a single-player world is open. */
	public List<Tool> tools() {
		Tool.Requirement world = new Tool.Requirement(this::available, "No single-player world is open, so the world tools aren't available.");
		return List.of(
			Tool.of("search_containers",
				"Find where an item is in the player's world: chests, barrels, shulker boxes, minecarts, item frames etc., plus "
					+ "the player's own inventory and ender chest. Searches item ids, item names, enchantments and container names, "
					+ "including items inside shulker boxes and bundles. Nearest first, with exact distance and compass direction.",
				world, List.of(ITEM_QUERY, DIMENSION_ALL, NEAR, HOLDER_LIMIT),
				(args, context) -> searchHolders(args.get(ITEM_QUERY), args.get(DIMENSION_ALL), args.get(NEAR), args.get(HOLDER_LIMIT),
					holder -> holder.kind() != WorldIndex.Holder.Kind.ENTITY || !holder.items().isEmpty())),
			Tool.of("search_entities",
				"Find entities in the player's world that are named, tamed, or hold items: pets, named mobs, armor stands, "
					+ "item frames, chest minecarts and boats, dropped items. Nearest first.",
				world, List.of(ENTITY_QUERY, DIMENSION_ALL, NEAR, HOLDER_LIMIT),
				(args, context) -> searchHolders(args.get(ENTITY_QUERY), args.get(DIMENSION_ALL), args.get(NEAR), args.get(HOLDER_LIMIT),
					holder -> holder.kind() == WorldIndex.Holder.Kind.ENTITY)),
			Tool.of("get_player", "The player's position, dimension, inventory and ender chest.",
				world, List.of(), (args, context) -> getPlayer()),
			Tool.of("find_structures",
				"Find structures near the player, nearest first: like /locate, but returns several and can skip ones the player "
					+ "has already visited. \"Visited\" means " + VISITED_RULE + " of some part of the structure (Minecraft's "
					+ "per-chunk InhabitedTime, at most of any chunk its pieces cover); structures in never-generated land are "
					+ "always unvisited. x/z is the /locate position; each result also has the structure's bounding box, its "
					+ "pieces (rooms and parts, by template or class name, with counts, e.g. \"end_city/ship\", "
					+ "\"village/plains/houses/plains_temple_3\", \"stronghold/library\") and the biomes they occupy (share of "
					+ "sampled cells), all worked out from the seed exactly as generation would, without generating anything. "
					+ "Pieces say what generates, not what's still there (looted chests, a taken elytra). The biome filter "
					+ "matches a structure if any of its pieces is in that biome, so a fortress that starts in a crimson forest "
					+ "and reaches into a warped forest counts as in a warped forest. Structure ids are listed under "
					+ "data/<namespace>/worldgen/structure in the reference files.",
				world, List.of(STRUCTURE, IN_BIOME, MIN_BIOME_SHARE, DIMENSION_WHERE_IT_EXISTS, VISITED, WITH_PIECES, MIN_DISTANCE,
					STRUCTURE_RANGE, FEW_RESULTS),
				this::findStructures),
			Tool.of("find_blocks",
				"Find the placed blocks nearest the player, by block id or tag, optionally narrowed by block state properties "
					+ "and by text in the block's data (its block entity). Examples: a skeleton spawner is block \"spawner\" with "
					+ "data \"skeleton\"; a fully charged respawn anchor is block \"respawn_anchor\" with properties {\"charges\": \"4\"}. "
					+ "Searches the player's dimension first, then the others (only the player's with max_distance); in another "
					+ "dimension, distances are from the player's position translated there (x/8 in the Nether). Covers loaded "
					+ "chunks live and every saved chunk; it can't tell who placed a block. Block ids match the "
					+ "block.<namespace>.<id> keys in the lang files; use search_containers for what's inside chests.",
				world, List.of(BLOCK, PROPERTIES, BLOCK_DATA, DIMENSION_ONLY, BLOCKS_NEAR, BLOCK_LIMIT),
				this::findBlocks),
			Tool.of("find_biomes",
				"Find patches of a biome near the player, nearest first: like /locate biome, but returns several separate "
					+ "patches. Worked out from the seed without generating anything, sampling every " + BiomeFinder.STEP
					+ " blocks across and " + BiomeFinder.VERTICAL_STEP + " up and down, so patches smaller than that can be missed. Searches the player's dimension, or where the biome "
					+ "exists if not there (a warped forest asked about from the Overworld is searched in the Nether, around the "
					+ "player's position / 8). y is a height where the biome is: underground biomes like deep_dark and lush_caves "
					+ "are found at their depth. Biome ids are under data/<namespace>/worldgen/biome in the reference files.",
				world, List.of(BIOME, DIMENSION_WHERE_IT_EXISTS, VISITED, MIN_SPACING, BIOME_RANGE, FEW_RESULTS),
				this::findBiomes),
			Tool.of("inspect_location",
				"What's at a position: the block, its state and data, the biome, and the column's top block. Loaded chunks are "
					+ "read live and other generated chunks from the last save. Chunks that were never generated are predicted from "
					+ "the seed without generating them: the biome is exact, but the block is only the base terrain (stone, water, "
					+ "lava or air, before grass and other surface blocks, caves from carvers, trees, ores and structures). "
					+ "\"source\" says which applies.",
				world, List.of(X, Y, Z, DIMENSION_OR_PLAYERS),
				this::inspectLocation),
			Tool.of("search_structure_templates",
				"Search structure templates, the .nbt building blocks that structures are made of (a village house, an ancient "
					+ "city room, a modded tower), by the blocks they contain. Use it to identify a structure the player describes "
					+ "(\"a small tower of mossy stone bricks with a spawner\"): pass the blocks they mention. Templates containing "
					+ "the most of the requested blocks come first, then those with many of them that also make up much of the build. "
					+ "Big buildings are often split into several templates (a main building plus add-ons), so look past small "
					+ "pieces. Templates with several block variants (palettes) match on any of them. Covers Minecraft, mods, the "
					+ "world's data packs and templates saved in the world with a structure block. Each result says which structures "
					+ "can place it (traced through template pools; structures that place templates from code, like igloos or many "
					+ "mods' own structures, aren't traced, so that list can be empty). Template files are gzipped NBT and can't be "
					+ "read with Read; use inspect_structure_template for one template's details. Give blocks, a query, or both.",
				world, List.of(TEMPLATE_BLOCKS, TEMPLATE_QUERY, MAX_SIZE, TEMPLATE_LIMIT),
				this::searchTemplates),
			Tool.of("inspect_structure_template",
				"One structure template in detail: size, every block with its count, chests (with loot tables), spawners and "
					+ "other block entities, entities, jigsaw connectors (which pools it attaches to), and the structures that can "
					+ "place it. With layers, also draws it as text, one horizontal slice per y level: rows run north to south, "
					+ "columns west to east, '.' is air, ' ' is nothing (structure void or not part of it: keeps whatever was there), "
					+ "and a legend maps the other characters to blocks. Blocks shown are before processors run, so a structure may "
					+ "swap some in the world (cracked or mossy variants, random ores, decay).",
				world, List.of(TEMPLATE, LAYERS, Y_FROM, Y_TO),
				this::inspectTemplate),
			Tool.of("list_schematics",
				"List the player's schematic files (Litematica .litematic, WorldEdit .schem, structure .nbt) from their "
					+ "schematics folder, with name, author, size, and block count where the file records one (Litematica). The files "
					+ "are gzipped NBT and can't be read with Read; use inspect_schematic.",
				world, List.of(SCHEMATIC_QUERY),
				(args, context) -> listSchematics(args.get(SCHEMATIC_QUERY))),
			Tool.of("inspect_schematic",
				"A schematic's material list and contents. \"materials\" is the items needed to place every block, counted "
					+ "like Litematica's material list (a double slab is 2 slabs, a door is 1 item, redstone wire is redstone dust, "
					+ "water sources are water buckets). \"crafting\" breaks crafted components (pistons, repeaters, observers, "
					+ "torches, hoppers...) down into ingredients using the game's recipes (each step's \"recipe\" id works in "
					+ "[[recipe:...]]), and \"base_materials\" is the total "
					+ "after that, e.g. all the redstone dust including what goes into pistons. Shapes of one material (planks, "
					+ "slabs, trapdoors, storage blocks) aren't broken down. With compare_with, also checks a container in the "
					+ "world (a chest's custom name, x y z, or \"inventory\": what the player carries plus their ender chest) "
					+ "including shulker boxes in it: what's missing as placed, and what's still missing if the player crafts "
					+ "components from what's there; \"useful_contents\" is what they hold of the materials and their ingredients, "
					+ "\"not_needed\" the rest. With layers, draws it like inspect_structure_template.",
				world, List.of(SCHEMATIC, COMPARE_WITH, LAYERS, Y_FROM, Y_TO),
				this::inspectSchematic));
	}

	// --- containers and entities ----------------------------------------------------------------------------------

	/**
	 * {@code kind} picks which holders the tool covers (the player's inventory and ender chest are holders too, so
	 * "where are my emeralds?" finds the ones in their pockets). An empty query finds every holder of that kind.
	 */
	private Tool.Result searchHolders(String queryText, String dimensionArgument, OptionalInt near, int limit,
		Predicate<WorldIndex.Holder> kind) throws Exception {
		String query = queryText.toLowerCase(Locale.ROOT);
		int maxDistance = near.orElse(Integer.MAX_VALUE);

		WorldIndex.Snapshot snapshot = index.snapshot(WorldIndex.Detail.HOLDERS);
		MinecraftServer server = snapshot.server();
		WorldIndex.PlayerInfo host = snapshot.host();
		Optional<ResourceKey<Level>> requested = Dimensions.parse(dimensionArgument, server.levelKeys());
		List<ResourceKey<Level>> dimensions = Dimensions.searchOrder(host.dimension(), requested, near.isPresent(), server.levelKeys());
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
			ToolFormat.addDirection(r, hit.origin(), h.x(), (double) h.y(), h.z());
			r.addProperty("data", h.provenance().label());
			if (h.lootTable() != null) {
				r.addProperty("unopened_loot_table", ToolFormat.id(h.lootTable()));
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
			ToolFormat.noteMore(out, (hits.size() - limit) + " more; raise limit or narrow the search.");
		}
		return Tool.Result.of(out);
	}

	/** Matching stacks, including inside shulker boxes and bundles ("32 Emerald (emerald) inside Shulker Box"). */
	private static void collectMatches(List<WorldIndex.Item> items, String query, String inside, List<String> out, int[] count) {
		for (WorldIndex.Item item : items) {
			boolean matches = !query.isEmpty() && (item.id().equals(query) || item.id().equals("minecraft:" + query)
				|| contains(item.name(), query) || contains(item.id(), query)
				|| item.enchantments().stream().anyMatch(e -> contains(e, query)));
			if (matches) {
				count[0] += item.count();
				out.add(itemText(item) + (inside != null ? " inside " + inside : ""));
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
	private Tool.Result getPlayer() throws Exception {
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
		return Tool.Result.of(players);
	}

	// --- structures -----------------------------------------------------------------------------------------------

	private Tool.Result findStructures(Arguments args, Tool.Context context) throws Exception {
		String query = args.get(STRUCTURE);
		int maxDistance = args.get(STRUCTURE_RANGE);

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
		Set<String> biomes = biomeIds(server, args.get(IN_BIOME));
		int minShare = args.get(MIN_BIOME_SHARE);
		if (minShare > 0 && biomes.isEmpty()) {
			throw new IllegalArgumentException("min_biome_share needs a biome.");
		}
		// "ship" or "temple" → matched against piece names like "end_city/ship" or "village/plains/houses/plains_temple_3".
		List<String> pieceTerms = args.get(WITH_PIECES).stream().map(t -> t.toLowerCase(Locale.ROOT).replace(' ', '_')).toList();

		Optional<ResourceKey<Level>> requested = Dimensions.parse(args.get(DIMENSION_WHERE_IT_EXISTS), server.levelKeys());
		// The generator's placements are worked out lazily on first use, which only the server thread may do.
		List<ResourceKey<Level>> generatesIn = ServerTasks.call(server, () -> dimensionsWhere(server, level -> structures.stream()
			.anyMatch(s -> !level.getChunkSource().getGeneratorState().getPlacementsForStructure(s).isEmpty())),
			Duration.ofSeconds(20), "Finding where the structure generates");
		ResourceKey<Level> dimension = Dimensions.whereItExists(host.dimension(), requested, generatesIn, "\"" + query + "\"");
		Origin origin = originIn(server, host.origin(), dimension);
		StructureFinder.Request request = new StructureFinder.Request(structures, args.get(MIN_DISTANCE), maxDistance, args.get(FEW_RESULTS),
			args.get(VISITED), pieceTerms, biomes, minShare);
		List<StructureFinder.Found> found = StructureFinder.find(server.getLevel(dimension), origin, request, snapshot);

		JsonObject out = new JsonObject();
		out.add("player", playerPosition(host));
		out.addProperty("searched", ToolFormat.searched(structures.stream().map(ToolFormat::id).toList(), query, "structure types"));
		out.addProperty("dimension", Dimensions.name(dimension));
		addOriginNote(out, List.of(origin));
		JsonArray results = new JsonArray();
		for (StructureFinder.Found f : found) {
			JsonObject r = new JsonObject();
			r.addProperty("structure", ToolFormat.id(f.structure()));
			r.addProperty("x", f.x());
			r.addProperty("z", f.z());
			ToolFormat.addDirection(r, origin, f.x(), null, f.z());
			r.addProperty("generated", f.visit().generated());
			r.addProperty("time_players_spent_nearby_seconds", f.visit().seconds());
			r.addProperty("visited", f.visit().visited());
			StructureFinder.Footprint footprint = f.footprint();
			BoundingBox box = footprint.bounds();
			r.addProperty("bounds", box.minX() + " " + box.minY() + " " + box.minZ() + " to " + box.maxX() + " " + box.maxY() + " " + box.maxZ());
			JsonObject shares = new JsonObject();
			footprint.biomeSamples().entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.forEach(e -> shares.addProperty(ToolFormat.id(e.getKey()), Math.max(1, Math.round(100f * e.getValue() / footprint.totalSamples())) + "%"));
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
		return Tool.Result.of(out);
	}

	/** The full ids of the biomes a biome argument names; none for a blank argument. */
	private static Set<String> biomeIds(MinecraftServer server, String query) {
		if (query.isEmpty()) {
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

	private Tool.Result findBlocks(Arguments args, Tool.Context context) throws Exception {
		String blockQuery = args.get(BLOCK);
		OptionalInt near = args.get(BLOCKS_NEAR);
		int maxDistance = near.orElse(Integer.MAX_VALUE);
		int limit = args.get(BLOCK_LIMIT);

		WorldIndex.Snapshot snapshot = index.snapshot(WorldIndex.Detail.VISITS);
		MinecraftServer server = snapshot.server();
		WorldIndex.PlayerInfo host = snapshot.host();
		List<Block> blocks = resolveBlocks(server, blockQuery);
		BlockFinder.Query query = BlockFinder.Query.of(blocks, args.get(PROPERTIES), args.get(BLOCK_DATA));
		Optional<ResourceKey<Level>> requested = Dimensions.parse(args.get(DIMENSION_ONLY), server.levelKeys());
		List<ResourceKey<Level>> dimensions = Dimensions.searchOrder(host.dimension(), requested, near.isPresent(), server.levelKeys());

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
		out.addProperty("searched", ToolFormat.searched(blocks.stream().map(ToolFormat::id).toList(), blockQuery, "block types"));
		out.add("results", results);
		out.addProperty("saved_chunks_read", chunksRead);
		if (truncated) {
			ToolFormat.noteMore(out, "Stopped after reading the nearest saved chunks that could hold this block; farther ones weren't searched.");
		} else if (results.isEmpty()) {
			out.addProperty("note", "None found in any generated chunk" + (near.isPresent() ? " within " + maxDistance + " blocks." : "."));
		}
		return Tool.Result.of(out);
	}

	/** Blocks matching a query: an id, a tag, or a word ("anchor" → respawn_anchor); see {@link RegistryQuery#resolve}. */
	private static List<Block> resolveBlocks(MinecraftServer server, String query) {
		List<Block> blocks = RegistryQuery.resolve(server.registryAccess(), Registries.BLOCK, query, RegistryQuery.Precedence.ID_FIRST)
			.stream().map(Holder::value).toList();
		if (blocks.isEmpty()) {
			throw new IllegalArgumentException("No block or block tag matches \"" + query + "\".");
		}
		return blocks;
	}

	private static JsonObject blockJson(BlockFinder.Found found, Origin origin) {
		JsonObject r = new JsonObject();
		ToolFormat.addBlockState(r, found.state());
		r.addProperty("dimension", Dimensions.name(origin.dimension()));
		r.addProperty("x", found.pos().getX());
		r.addProperty("y", found.pos().getY());
		r.addProperty("z", found.pos().getZ());
		ToolFormat.addDirection(r, origin, found.pos().getX(), (double) found.pos().getY(), found.pos().getZ());
		r.addProperty("data", found.provenance().label());
		addBlockEntity(r, found.blockEntity());
		return r;
	}

	/** The block entity's data as trimmed SNBT, plus "spawns" for spawners. */
	private static void addBlockEntity(JsonObject r, CompoundTag blockEntity) {
		if (blockEntity != null) {
			// Spawners and trial spawners keep the mob in SpawnData; say it plainly rather than leave it in the SNBT.
			blockEntity.getCompound("SpawnData").flatMap(spawn -> spawn.getCompound("entity"))
				.map(entity -> entity.getStringOr("id", "")).filter(id -> !id.isEmpty())
				.ifPresent(id -> r.addProperty("spawns", ToolFormat.id(id)));
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

	/** The find_blocks "properties" argument: {"charges": "4"}, or the same written as "charges=4,facing=north". */
	private static JsonObject propertiesSchema() {
		JsonObject value = new JsonObject();
		value.addProperty("type", "string");
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("additionalProperties", value);
		schema.addProperty("description", "Block state properties that must match, e.g. {\"charges\": \"4\"} or {\"facing\": \"north\"}.");
		return schema;
	}

	private static Map<String, String> properties(JsonElement element) {
		Map<String, String> properties = new LinkedHashMap<>();
		if (element.isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
				if (!e.getValue().isJsonPrimitive()) {
					throw new IllegalArgumentException("values must be text, like {\"charges\": \"4\"}, not " + e.getValue() + ".");
				}
				properties.put(e.getKey().trim(), e.getValue().getAsString().trim().toLowerCase(Locale.ROOT));
			}
		} else if (element.isJsonPrimitive()) {
			for (String pair : element.getAsString().replaceAll("[\\[\\]{}]", "").split(",")) {
				String[] kv = pair.split("[=:]", 2);
				if (kv.length != 2) {
					throw new IllegalArgumentException("must be pairs like \"charges=4,facing=north\", not \"" + element.getAsString() + "\".");
				}
				properties.put(kv[0].trim().replace("\"", ""), kv[1].trim().replace("\"", "").toLowerCase(Locale.ROOT));
			}
		} else {
			throw new IllegalArgumentException("must be an object like {\"charges\": \"4\"}, not " + element + ".");
		}
		return properties;
	}

	// --- biomes --------------------------------------------------------------------------------------------------

	private Tool.Result findBiomes(Arguments args, Tool.Context context) throws Exception {
		String query = args.get(BIOME);
		int maxDistance = args.get(BIOME_RANGE);

		WorldIndex.Snapshot snapshot = index.snapshot(WorldIndex.Detail.VISITS);
		MinecraftServer server = snapshot.server();
		WorldIndex.PlayerInfo host = snapshot.host();
		Set<Holder<Biome>> biomes = new HashSet<>(RegistryQuery.resolve(server.registryAccess(), Registries.BIOME, query, RegistryQuery.Precedence.ID_FIRST));
		if (biomes.isEmpty()) {
			throw new IllegalArgumentException("No biome or biome tag matches \"" + query + "\". Biome ids are under data/<namespace>/worldgen/biome.");
		}
		Optional<ResourceKey<Level>> requested = Dimensions.parse(args.get(DIMENSION_WHERE_IT_EXISTS), server.levelKeys());
		List<ResourceKey<Level>> existsIn = dimensionsWhere(server,
			level -> level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes().stream().anyMatch(biomes::contains));
		ResourceKey<Level> dimension = Dimensions.whereItExists(host.dimension(), requested, existsIn, "\"" + query + "\"");
		Origin origin = originIn(server, host.origin(), dimension);
		BiomeFinder.Request request = new BiomeFinder.Request(biomes, maxDistance, args.get(MIN_SPACING), args.get(FEW_RESULTS), args.get(VISITED));
		BiomeFinder.Result result = BiomeFinder.find(server.getLevel(dimension), origin, request, snapshot.visits(dimension));

		JsonObject out = new JsonObject();
		out.add("player", playerPosition(host));
		out.addProperty("searched", ToolFormat.searched(biomes.stream().map(ToolFormat::id).toList(), query, "biomes"));
		out.addProperty("dimension", Dimensions.name(dimension));
		addOriginNote(out, List.of(origin));
		JsonArray results = new JsonArray();
		for (BiomeFinder.Found f : result.found()) {
			JsonObject r = new JsonObject();
			r.addProperty("biome", ToolFormat.id(f.biome()));
			r.addProperty("x", f.x());
			r.addProperty("y", f.y());
			r.addProperty("z", f.z());
			ToolFormat.addDirection(r, origin, f.x(), null, f.z());
			r.addProperty("generated", f.visit().generated());
			r.addProperty("visited", f.visit().visited());
			results.add(r);
		}
		out.add("results", results);
		if (result.truncated()) {
			ToolFormat.noteMore(out, "Stopped early: the search area was too large. Narrow max_distance to search farther in parts.");
		} else if (results.isEmpty()) {
			out.addProperty("note", "None found within " + maxDistance + " blocks with these filters.");
		}
		return Tool.Result.of(out);
	}

	// --- one position ---------------------------------------------------------------------------------------------

	private Tool.Result inspectLocation(Arguments args, Tool.Context context) throws Exception {
		int x = args.get(X);
		int z = args.get(Z);
		OptionalInt y = args.get(Y);

		WorldIndex.Snapshot snapshot = index.snapshot(WorldIndex.Detail.VISITS);
		MinecraftServer server = snapshot.server();
		WorldIndex.PlayerInfo host = snapshot.host();
		ResourceKey<Level> dimension = Dimensions.parse(args.get(DIMENSION_OR_PLAYERS), server.levelKeys()).orElse(host.dimension());
		Origin origin = originIn(server, host.origin(), dimension);

		LocationInspector.Inspection inspection = LocationInspector.inspect(snapshot, server.getLevel(dimension), x,
			y.isPresent() ? y.getAsInt() : null, z);
		JsonObject out = new JsonObject();
		out.addProperty("dimension", Dimensions.name(dimension));
		out.addProperty("x", x);
		out.addProperty("y", inspection.y());
		out.addProperty("z", z);
		ToolFormat.addDirection(out, origin, x, (double) inspection.y(), z);
		addOriginNote(out, List.of(origin));
		out.addProperty("source", inspection.source().label());
		ToolFormat.addBlockState(out, inspection.state());
		addBlockEntity(out, inspection.blockEntity());
		out.addProperty("biome", ToolFormat.id(inspection.biome()));
		if (inspection.surfaceY() != null) {
			out.addProperty("top_block_y", inspection.surfaceY());
		}
		return Tool.Result.of(out);
	}

	// --- structure templates --------------------------------------------------------------------------------------

	/** Characters for the layer drawing, most common block first; '.' (air), ' ' (empty) and '?' (other) are reserved. */
	private static final String LAYER_SYMBOLS = "#ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789@$%&*+=!~^<>/|:;";
	/** About 10k tokens of drawing. */
	private static final int MAX_LAYER_CELLS = 40_000;
	private static final int MAX_BLOCK_ENTITIES = 40;
	private static final int MAX_TEMPLATE_ENTITIES = 30;

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

	private Tool.Result searchTemplates(Arguments args, Tool.Context context) throws Exception {
		MinecraftServer server = server();
		List<String> terms = args.get(TEMPLATE_BLOCKS);
		String query = args.get(TEMPLATE_QUERY);
		int limit = args.get(TEMPLATE_LIMIT);
		if (terms.isEmpty() && query.isEmpty()) {
			throw new IllegalArgumentException("Give blocks, a query, or both.");
		}
		Map<String, Set<Block>> wanted = new LinkedHashMap<>();
		for (String term : terms) {
			wanted.put(term, new HashSet<>(resolveBlocks(server, term)));
		}

		StructureTemplates.Index templateIndex = templates.index(server);
		StructureTemplates.SearchResult found = StructureTemplates.search(templateIndex,
			new StructureTemplates.Query(wanted, query, args.get(MAX_SIZE).orElse(Integer.MAX_VALUE)));
		List<StructureTemplates.Hit> hits = found.hits();

		JsonObject out = new JsonObject();
		JsonObject searched = new JsonObject();
		wanted.forEach((term, blocks) -> searched.addProperty(term, ToolFormat.searched(blocks.stream().map(ToolFormat::id).toList(), term, "blocks")));
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
			r.addProperty("template", ToolFormat.id(t.id()));
			r.addProperty("from", t.origin().describe());
			r.addProperty("size", ToolFormat.size(t.size()));
			r.addProperty("solid_blocks", t.countedBlocks());
			if (!wanted.isEmpty()) {
				JsonObject matching = new JsonObject();
				hit.perTerm().forEach(matching::addProperty);
				r.add("requested_blocks", matching);
				r.addProperty("share_of_requested_blocks", Math.round(100 * hit.share()) + "%");
			}
			JsonArray main = new JsonArray();
			t.blockCounts().entrySet().stream().sorted(Map.Entry.<Block, Integer>comparingByValue().reversed()).limit(6)
				.forEach(e -> main.add(ToolFormat.id(e.getKey()) + " ×" + e.getValue()));
			r.add("main_blocks", main);
			if (!t.entityCounts().isEmpty()) {
				JsonObject entities = new JsonObject();
				t.entityCounts().forEach((type, count) -> entities.addProperty(ToolFormat.id(type), count));
				r.add("entities", entities);
			}
			addTemplateUsage(r, templateIndex, t.id(), 6);
			results.add(r);
		}
		out.add("results", results);
		if (hits.size() > limit) {
			ToolFormat.noteMore(out, (hits.size() - limit) + " more; add blocks, a query or max_size to narrow it.");
		}
		return Tool.Result.of(out);
	}

	private Tool.Result inspectTemplate(Arguments args, Tool.Context context) throws Exception {
		MinecraftServer server = server();
		String name = args.get(TEMPLATE).replaceFirst("\\.nbt$", "");
		Identifier id = Identifier.tryParse(name);
		if (id == null) {
			throw new IllegalArgumentException("\"" + name + "\" isn't a valid template id.");
		}
		StructureTemplates.Index templateIndex = templates.index(server);
		StructureTemplates.Template t = StructureTemplates.load(server, id).orElse(null);
		if (t == null) {
			List<String> similar = templateIndex.similarTo(id, 10).stream().map(ToolFormat::id).toList();
			throw new IllegalArgumentException("No structure template " + ToolFormat.id(id) + "."
				+ (similar.isEmpty() ? " Use search_structure_templates to find one." : " Similar: " + String.join(", ", similar)));
		}

		JsonObject out = new JsonObject();
		out.addProperty("template", ToolFormat.id(id));
		out.addProperty("from", t.origin().describe());
		out.addProperty("size", ToolFormat.size(t.volume().size()));
		if (t.palettes() > 1) {
			out.addProperty("palettes", t.palettes() + " block variants, one picked at random when placed; showing the first");
		}
		Map<Block, Integer> counts = t.volume().countedBlocks();
		out.addProperty("solid_blocks", counts.values().stream().mapToInt(Integer::intValue).sum());
		JsonObject blocks = new JsonObject();
		counts.entrySet().stream().sorted(Map.Entry.<Block, Integer>comparingByValue().reversed()).limit(100)
			.forEach(e -> blocks.addProperty(ToolFormat.id(e.getKey()), e.getValue()));
		out.add("blocks", blocks);
		if (counts.size() > 100) {
			ToolFormat.noteMore(out, (counts.size() - 100) + " rarer kinds of block.");
		}
		addBlockEntities(out, t.volume());
		addTemplateUsage(out, templateIndex, id, 20);
		if (args.get(LAYERS)) {
			addLayers(out, t.volume(), args.get(Y_FROM), args.get(Y_TO));
		}
		return Tool.Result.of(out);
	}

	/** "block_entities" (chests with loot tables, spawners, signs...), "entities" and "jigsaw_connectors". */
	private static void addBlockEntities(JsonObject out, BlockVolume volume) {
		JsonArray blockEntities = new JsonArray();
		JsonArray jigsaws = new JsonArray();
		int moreBlockEntities = 0;
		int moreJigsaws = 0;
		for (BlockVolume.BlockEntity b : volume.blockEntities()) {
			JsonObject r = new JsonObject();
			if (b.state().is(Blocks.JIGSAW)) {
				if (jigsaws.size() >= MAX_BLOCK_ENTITIES) {
					moreJigsaws++;
					continue;
				}
				r.addProperty("pos", ToolFormat.position(b.pos()));
				String facing = ToolFormat.properties(b.state()).get("orientation");
				if (facing != null) {
					r.addProperty("facing", facing);
				}
				for (String key : List.of("pool", "name", "target")) {
					String value = b.data().getStringOr(key, "");
					if (!value.isEmpty() && !value.equals("minecraft:empty")) {
						r.addProperty(key, ToolFormat.id(value));
					}
				}
				b.data().getString("final_state").filter(state -> !state.isEmpty()).ifPresent(state -> r.addProperty("turns_into", ToolFormat.blockState(state)));
				jigsaws.add(r);
				continue;
			}
			if (blockEntities.size() >= MAX_BLOCK_ENTITIES) {
				moreBlockEntities++;
				continue;
			}
			ToolFormat.addBlockState(r, b.state());
			r.addProperty("pos", ToolFormat.position(b.pos()));
			b.data().getString("LootTable").ifPresent(loot -> r.addProperty("loot_table", ToolFormat.id(loot)));
			CompoundTag rest = b.data().copy();
			rest.remove("LootTable");
			addBlockEntity(r, rest);
			blockEntities.add(r);
		}
		if (!blockEntities.isEmpty()) {
			out.add("block_entities", blockEntities);
		}
		if (moreBlockEntities > 0) {
			ToolFormat.noteMore(out, moreBlockEntities + " more block entities.");
		}
		if (!volume.entities().isEmpty()) {
			JsonArray entities = new JsonArray();
			volume.entities().stream().limit(MAX_TEMPLATE_ENTITIES)
				.forEach(e -> entities.add(ToolFormat.id(e.type()) + " at " + ToolFormat.position(e.pos())));
			out.add("entities", entities);
			if (volume.entities().size() > MAX_TEMPLATE_ENTITIES) {
				ToolFormat.noteMore(out, (volume.entities().size() - MAX_TEMPLATE_ENTITIES) + " more entities.");
			}
		}
		if (!jigsaws.isEmpty()) {
			out.add("jigsaw_connectors", jigsaws);
		}
		if (moreJigsaws > 0) {
			ToolFormat.noteMore(out, moreJigsaws + " more jigsaw connectors.");
		}
	}

	// --- schematics -----------------------------------------------------------------------------------------------

	private Tool.Result listSchematics(String query) throws Exception {
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
					r.addProperty("size", ToolFormat.size(info.size()));
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
		return Tool.Result.of(out);
	}

	private Tool.Result inspectSchematic(Arguments args, Tool.Context context) throws Exception {
		String query = args.get(SCHEMATIC);
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
		out.addProperty("size", ToolFormat.size(info.size()));
		if (s.regions().size() > 1) {
			JsonArray regions = new JsonArray();
			s.regions().forEach(r -> regions.add(r.name() + ": " + ToolFormat.size(r.size()) + " at " + ToolFormat.position(r.position())));
			out.add("regions", regions);
		}

		Map<Identifier, Integer> noItem = new TreeMap<>();
		Map<Materials.Material, Integer> placed = Materials.asPlaced(s.volume(), noItem);
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

		String compare = args.get(COMPARE_WITH);
		if (!compare.isEmpty()) {
			out.add("comparison", compare(compare, placed));
		}
		addBlockEntities(out, s.volume());
		if (args.get(LAYERS)) {
			addLayers(out, s.volume(), args.get(Y_FROM), args.get(Y_TO));
		}
		return Tool.Result.of(out);
	}

	/** Checks the containers matching {@code query} against the materials; see inspect_schematic's description. */
	private JsonObject compare(String query, Map<Materials.Material, Integer> placed) throws Exception {
		WorldIndex.Snapshot snapshot = index.snapshot(WorldIndex.Detail.HOLDERS);
		WorldIndex.PlayerInfo player = snapshot.host();
		List<String> used = new ArrayList<>();
		List<WorldIndex.Item> items = new ArrayList<>();
		String q = query.toLowerCase(Locale.ROOT);
		if (q.equals("inventory")) {
			items.addAll(player.inventory());
			items.addAll(player.enderChest());
			used.add("inventory of " + player.name());
			used.add("ender chest of " + player.name());
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
						&& Math.abs(h.x() - first.x()) + Math.abs(h.z() - first.z()) == 1 && Objects.equals(h.name(), first.name())) {
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
		Map<Materials.Material, Integer> stock = new TreeMap<>();
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
	private static void flatten(List<WorldIndex.Item> items, Map<Materials.Material, Integer> into) {
		for (WorldIndex.Item item : items) {
			into.merge(Materials.Material.item(Identifier.parse(item.id())), item.count(), Integer::sum);
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

	/** {"redstone": 41, "#planks": 6, ...}: items and tags in the shared id format, most first. */
	private static JsonObject countsJson(Map<?, Integer> counts) {
		JsonObject o = new JsonObject();
		counts.entrySet().stream().sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
			.forEach(e -> o.addProperty(ToolFormat.id(e.getKey().toString()), e.getValue()));
		return o;
	}

	private static JsonArray craftsJson(List<Materials.Craft> crafts) {
		JsonArray array = new JsonArray();
		for (Materials.Craft c : crafts) {
			JsonObject o = new JsonObject();
			o.addProperty("item", ToolFormat.id(c.item()));
			o.addProperty("needed", c.used());
			o.addProperty("craft_times", c.crafts());
			if (c.made() > c.used()) {
				o.addProperty("left_over", c.made() - c.used());
			}
			JsonObject per = new JsonObject();
			c.recipe().ingredients().forEach((ingredient, count) -> per.addProperty(ToolFormat.id(ingredient.toString()), count));
			o.add("ingredients_per_craft", per);
			// A bare path is Minecraft's, so the short id still works in [[recipe:...]].
			o.addProperty("recipe", ToolFormat.id(c.recipe().id()));
			array.add(o);
		}
		return array;
	}

	/** "used_by_structures" (traced through pools) and "in_pools", at most {@code max} of each. */
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
		if (structures.size() > max) {
			ToolFormat.noteMore(r, (structures.size() - max) + " more structures using it.");
		}
		if (pools.size() > max) {
			ToolFormat.noteMore(r, (pools.size() - max) + " more pools listing it.");
		}
	}

	private static JsonArray idList(Set<Identifier> ids, int max) {
		JsonArray array = new JsonArray();
		ids.stream().limit(max).forEach(i -> array.add(ToolFormat.id(i)));
		return array;
	}

	/** One text grid per y level, plus the legend. Skipped with a note if it would be too long. */
	private static void addLayers(JsonObject out, BlockVolume volume, OptionalInt from, OptionalInt to) {
		Vec3i size = volume.size();
		int sx = size.getX();
		int sy = size.getY();
		int sz = size.getZ();
		if (sx <= 0 || sy <= 0 || sz <= 0) {
			return;
		}
		int yFrom = Math.clamp(from.orElse(0), 0, sy - 1);
		int yTo = Math.clamp(to.orElse(sy - 1), yFrom, sy - 1);
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
				legend.addProperty(String.valueOf(c), ToolFormat.id(byCount.get(i)));
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

	// --- helpers --------------------------------------------------------------------------------------------------

	/** The open world's server; the tool's requirement checked it, but the world may have closed since. */
	private MinecraftServer server() {
		MinecraftServer server = index.server();
		if (server == null) {
			throw new IllegalStateException("No single-player world is open.");
		}
		return server;
	}

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
			if (item.contents().isEmpty()) {
				array.add(itemText(item));
			} else {
				JsonObject o = new JsonObject();
				o.addProperty("item", itemText(item));
				o.add("contains", itemsJson(item.contents()));
				array.add(o);
			}
		}
		return array;
	}

	private static String itemText(WorldIndex.Item item) {
		return ToolFormat.item(item.count(), item.id(), item.name(), item.enchantments());
	}

	private static boolean contains(String haystack, String needle) {
		return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
	}

	/** "a second", "5 seconds", or ticks when it isn't whole seconds. */
	private static String duration(long ticks) {
		if (ticks % 20 != 0) {
			return ticks + " game ticks";
		}
		return ticks == 20 ? "a second" : ticks / 20 + " seconds";
	}
}
