package claudechat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * What it takes to build a set of blocks: the items as placed (a double slab is two slabs, a door is one item for two
 * blocks, redstone wire is redstone dust), and those broken down into what they're crafted from. Recipes come from the
 * knowledge base's data files, so data packs and mods count, in the game's override order.
 * <p>
 * Breaking down stops at "shapes" of one material (planks, slabs, stairs, trapdoors, storage blocks): only recipes
 * with two or more different ingredients are expanded, so pistons, repeaters, torches and hoppers break down, while
 * oak planks stay oak planks. Ingredients that accept a tag (any planks) aren't expanded further.
 */
public final class Materials {
	/** A crafting recipe: makes {@code count} of an item from these ingredients ("minecraft:stick", or "#minecraft:planks"). */
	public record Recipe(String file, int count, Map<String, Integer> ingredients) {
	}

	/** One step of the breakdown: craft {@code crafts} times to make {@code made}, of which {@code used} are needed. */
	public record Craft(String item, int crafts, int made, int used, Recipe recipe) {
	}

	public record Breakdown(Map<String, Integer> remaining, List<Craft> crafts) {
	}

	private final KnowledgeBase knowledge;
	private Map<String, List<Recipe>> recipes;
	private int indexedGeneration = -1;

	public Materials(KnowledgeBase knowledge) {
		this.knowledge = knowledge;
	}

	// --- as placed ----------------------------------------------------------------------------------------------

	/** Items needed to place these blocks, by item id. Blocks with no item (fire, piston heads) go to {@code noItem}. */
	public static Map<String, Integer> asPlaced(Iterable<BlockState> states, Map<String, Integer> noItem) {
		Map<String, Integer> items = new TreeMap<>();
		for (BlockState state : states) {
			addItems(state, items, noItem);
		}
		return items;
	}

	private static void addItems(BlockState state, Map<String, Integer> items, Map<String, Integer> noItem) {
		Block block = state.getBlock();
		if (state.isAir() || block == Blocks.STRUCTURE_VOID || block == Blocks.JIGSAW || block == Blocks.PISTON_HEAD
			|| block == Blocks.MOVING_PISTON) {
			return;
		}
		// The second half of two-block things comes with the first.
		if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
			return;
		}
		if (state.hasProperty(BlockStateProperties.BED_PART) && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) {
			return;
		}
		if (block == Blocks.BUBBLE_COLUMN) {
			// Water over soul sand or magma; each column block is a water source.
			add(items, id(Items.WATER_BUCKET), 1);
			return;
		}
		if (block instanceof LiquidBlock) {
			// Only source blocks need a bucket; flowing liquid spreads from them.
			if (state.getValue(LiquidBlock.LEVEL) == 0) {
				add(items, id(block == Blocks.LAVA ? Items.LAVA_BUCKET : Items.WATER_BUCKET), 1);
			}
			return;
		}
		if (block instanceof FlowerPotBlock pot) {
			add(items, id(Items.FLOWER_POT), 1);
			if (pot.getPotted() != Blocks.AIR) {
				addItems(pot.getPotted().defaultBlockState(), items, noItem);
			}
			return;
		}
		Item item = block.asItem();
		if (item == Items.AIR) {
			Item cauldron = block == Blocks.WATER_CAULDRON || block == Blocks.LAVA_CAULDRON || block == Blocks.POWDER_SNOW_CAULDRON ? Items.CAULDRON : null;
			if (cauldron != null) {
				add(items, id(cauldron), 1);
			} else {
				add(noItem, BuiltInRegistries.BLOCK.getKey(block).toString(), 1);
			}
			return;
		}
		int count = 1;
		if (state.hasProperty(BlockStateProperties.SLAB_TYPE) && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) {
			count = 2;
		}
		for (IntegerProperty property : List.of(BlockStateProperties.CANDLES, BlockStateProperties.PICKLES, BlockStateProperties.EGGS,
			BlockStateProperties.LAYERS, BlockStateProperties.FLOWER_AMOUNT, BlockStateProperties.SEGMENT_AMOUNT)) {
			if (state.hasProperty(property)) {
				count = state.getValue(property);
			}
		}
		add(items, id(item), count);
	}

	// --- breakdown ----------------------------------------------------------------------------------------------

	/**
	 * Breaks crafted components down into their ingredients. Items in {@code stock} (a chest's contents) are used
	 * first, at every level: if the chest holds 3 pistons, only the rest are broken down. {@code remaining} is what's
	 * still needed after that; with an empty stock it's the full list of base materials.
	 */
	public Breakdown breakDown(Map<String, Integer> needed, Map<String, Integer> stockIn) {
		Map<String, List<Recipe>> all = recipes();
		Map<String, Integer> demand = new HashMap<>(needed);
		Map<String, Integer> stock = new HashMap<>(stockIn);
		// Parents before children, so a component's total demand is known before it's broken down.
		List<String> order = new ArrayList<>();
		Set<String> visited = new HashSet<>();
		for (String item : needed.keySet()) {
			visit(item, all, visited, new HashSet<>(), order);
		}
		java.util.Collections.reverse(order);

		List<Craft> crafts = new ArrayList<>();
		for (String item : order) {
			int need = demand.getOrDefault(item, 0);
			int fromStock = Math.min(need, stock.getOrDefault(item, 0));
			if (fromStock > 0) {
				need -= fromStock;
				stock.merge(item, -fromStock, Integer::sum);
			}
			Recipe recipe = expandable(item, all);
			if (need <= 0 || recipe == null) {
				demand.put(item, need);
				continue;
			}
			int times = (need + recipe.count() - 1) / recipe.count();
			crafts.add(new Craft(item, times, times * recipe.count(), need, recipe));
			demand.put(item, 0);
			recipe.ingredients().forEach((ingredient, perCraft) -> demand.merge(ingredient, perCraft * times, Integer::sum));
		}
		Map<String, Integer> remaining = new TreeMap<>();
		demand.forEach((item, count) -> {
			if (count > 0) {
				remaining.put(item, count);
			}
		});
		return new Breakdown(remaining, crafts);
	}

	/** Depth-first post-order through expandable recipes; a cycle (ingots ↔ blocks) stops at the repeated item. */
	private void visit(String item, Map<String, List<Recipe>> all, Set<String> visited, Set<String> path, List<String> order) {
		if (visited.contains(item) || !path.add(item)) {
			return;
		}
		Recipe recipe = expandable(item, all);
		if (recipe != null) {
			for (String ingredient : recipe.ingredients().keySet()) {
				if (!path.contains(ingredient)) {
					visit(ingredient, all, visited, path, order);
				}
			}
		}
		path.remove(item);
		visited.add(item);
		order.add(item);
	}

	/** The recipe to break an item down with, or null if it's a base material here. */
	private static Recipe expandable(String item, Map<String, List<Recipe>> all) {
		if (item.startsWith("#")) {
			return null;
		}
		List<Recipe> candidates = all.getOrDefault(item, List.of());
		String path = item.substring(item.indexOf(':') + 1);
		// The recipe named after the item is its main one (minecraft:piston, not some alternative).
		Recipe main = candidates.stream().filter(r -> r.file().endsWith("/" + path + ".json") || r.file().endsWith("\\" + path + ".json"))
			.findFirst().orElse(candidates.isEmpty() ? null : candidates.getFirst());
		if (main == null || main.ingredients().size() < 2 || main.ingredients().containsKey(item)) {
			return null;
		}
		return main;
	}

	/** Reads the recipes ahead of the first question (thousands of files with mods). */
	public void prepare() {
		recipes();
	}

	/** Crafting recipes by result item. Rebuilt when the knowledge base changes (a mod extracted, a data pack added). */
	private synchronized Map<String, List<Recipe>> recipes() {
		if (recipes != null && indexedGeneration == knowledge.generation()) {
			return recipes;
		}
		indexedGeneration = knowledge.generation();
		Map<String, List<Recipe>> index = new HashMap<>();
		Set<String> seen = new HashSet<>();
		// Earlier roots override later ones: a data pack's minecraft/recipe/piston.json replaces Minecraft's.
		for (Path data : knowledge.dataRoots()) {
			try (Stream<Path> namespaces = Files.list(data)) {
				for (Path ns : namespaces.toList()) {
					Path recipeDir = ns.resolve("recipe");
					if (!Files.isDirectory(recipeDir)) {
						continue;
					}
					try (Stream<Path> files = Files.walk(recipeDir)) {
						for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
							String key = ns.getFileName() + "/" + recipeDir.relativize(file).toString().replace('\\', '/');
							if (!seen.add(key)) {
								continue;
							}
							try {
								parse(key, JsonParser.parseString(Files.readString(file)).getAsJsonObject(), index);
							} catch (IOException | RuntimeException ignored) {
							}
						}
					}
				}
			} catch (IOException e) {
				ClaudeChatClient.LOGGER.debug("Could not index recipes in {}", data, e);
			}
		}
		recipes = index;
		return index;
	}

	private static void parse(String file, JsonObject recipe, Map<String, List<Recipe>> index) {
		String type = recipe.has("type") ? recipe.get("type").getAsString() : "";
		if (!recipe.has("result") || !recipe.get("result").isJsonObject()) {
			return;
		}
		JsonObject result = recipe.getAsJsonObject("result");
		String id = result.get("id").getAsString();
		int count = result.has("count") ? result.get("count").getAsInt() : 1;
		Map<String, Integer> ingredients = new LinkedHashMap<>();
		switch (type.replace("minecraft:", "")) {
			case "crafting_shaped" -> {
				JsonObject key = recipe.getAsJsonObject("key");
				for (JsonElement row : recipe.getAsJsonArray("pattern")) {
					for (char c : row.getAsString().toCharArray()) {
						if (c != ' ' && key.has(String.valueOf(c))) {
							ingredients.merge(ingredient(key.get(String.valueOf(c))), 1, Integer::sum);
						}
					}
				}
			}
			case "crafting_shapeless" -> {
				for (JsonElement element : recipe.getAsJsonArray("ingredients")) {
					ingredients.merge(ingredient(element), 1, Integer::sum);
				}
			}
			default -> {
				return; // smelting, stonecutting etc. aren't "crafted components"
			}
		}
		if (!ingredients.isEmpty()) {
			index.computeIfAbsent(id.contains(":") ? id : "minecraft:" + id, k -> new ArrayList<>()).add(new Recipe(file, count, ingredients));
		}
	}

	/** "minecraft:stick" or "#minecraft:planks"; for a list of alternatives, the first. */
	private static String ingredient(JsonElement json) {
		JsonElement first = json.isJsonArray() ? ((JsonArray) json).get(0) : json;
		String id = first.isJsonObject() ? first.getAsJsonObject().get(first.getAsJsonObject().has("tag") ? "tag" : "item").getAsString()
			: first.getAsString();
		if (first.isJsonObject() && first.getAsJsonObject().has("tag")) {
			id = "#" + id;
		}
		if (!id.startsWith("#") && !id.contains(":")) {
			id = "minecraft:" + id;
		}
		return id;
	}

	private static void add(Map<String, Integer> map, String id, int count) {
		map.merge(id, count, Integer::sum);
	}

	private static String id(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}
}
