package claudechat;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * What it takes to build a set of blocks: the items as placed (a double slab is two slabs, a door is one item for two
 * blocks, redstone wire is redstone dust), and those broken down into what they're crafted from. Recipes are the
 * game's ({@link GameRecipes}), so data packs and mods count, and each item breaks down by its main recipe, the one
 * [[recipe:item]] shows.
 * <p>
 * Breaking down stops at "shapes" of one material (planks, slabs, stairs, trapdoors, storage blocks): only crafting
 * recipes with two or more different ingredients are expanded, so pistons, repeaters, torches and hoppers break down,
 * while oak planks stay oak planks. Ingredients that accept a tag (any planks) aren't expanded further.
 */
public final class Materials {
	/**
	 * What a material list counts: one item, or any item of a tag (an ingredient that accepts any planks). Written as
	 * its id, a tag with "#" first: "minecraft:stick", "#minecraft:planks".
	 */
	public sealed interface Material extends Comparable<Material> {
		static Material item(Identifier id) {
			return new Single(id);
		}

		record Single(Identifier item) implements Material {
			@Override
			public String toString() {
				return item.toString();
			}
		}

		record AnyOf(TagKey<Item> tag) implements Material {
			@Override
			public String toString() {
				return "#" + tag.location();
			}
		}

		@Override
		default int compareTo(Material other) {
			return toString().compareTo(other.toString());
		}
	}

	/** A crafting recipe: makes {@code count} of an item from these ingredients per craft. */
	public record Recipe(Identifier id, int count, Map<Material, Integer> ingredients) {
	}

	/** One step of the breakdown: craft {@code crafts} times to make {@code made}, of which {@code used} are needed. */
	public record Craft(Identifier item, int crafts, int made, int used, Recipe recipe) {
	}

	public record Breakdown(Map<Material, Integer> remaining, List<Craft> crafts) {
	}

	/**
	 * Materials checked against what the player has. {@code missingAsPlaced} is the shortfall without crafting;
	 * {@code afterCrafting} is what's still missing once components are crafted from the stock, with the crafts that
	 * takes. {@code useful} is the stock that is a material or an ingredient of one, {@code notNeeded} the rest.
	 */
	public record Comparison(Map<Material, Integer> missingAsPlaced, Breakdown afterCrafting, Map<Material, Integer> useful,
		Map<Material, Integer> notNeeded) {
		public boolean complete() {
			return afterCrafting.remaining().isEmpty();
		}
	}

	private final GameRecipes recipes;

	public Materials(KnowledgeBase knowledge) {
		this.recipes = knowledge.recipes();
	}

	// --- as placed ----------------------------------------------------------------------------------------------

	/**
	 * Items needed to place a volume's counted blocks ({@link BlockVolume#counts}). Blocks with no item (fire, end
	 * portal) go to {@code noItem}, by block id.
	 */
	public static Map<Material, Integer> asPlaced(BlockVolume volume, Map<Identifier, Integer> noItem) {
		Map<Material, Integer> items = new TreeMap<>();
		volume.forEachCounted(state -> addItems(state, items, noItem));
		return items;
	}

	private static void addItems(BlockState state, Map<Material, Integer> items, Map<Identifier, Integer> noItem) {
		Block block = state.getBlock();
		// An extended piston's head, and the second half of two-block things, come with the first part.
		if (block == Blocks.PISTON_HEAD) {
			return;
		}
		if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
			return;
		}
		if (state.hasProperty(BlockStateProperties.BED_PART) && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) {
			return;
		}
		if (block == Blocks.BUBBLE_COLUMN) {
			// Water over soul sand or magma; each column block is a water source.
			add(items, material(Items.WATER_BUCKET), 1);
			return;
		}
		if (block instanceof LiquidBlock) {
			// Only source blocks need a bucket; flowing liquid spreads from them.
			if (state.getValue(LiquidBlock.LEVEL) == 0) {
				add(items, material(block == Blocks.LAVA ? Items.LAVA_BUCKET : Items.WATER_BUCKET), 1);
			}
			return;
		}
		if (block instanceof FlowerPotBlock pot) {
			add(items, material(Items.FLOWER_POT), 1);
			if (pot.getPotted() != Blocks.AIR) {
				addItems(pot.getPotted().defaultBlockState(), items, noItem);
			}
			return;
		}
		Item item = block.asItem();
		if (item == Items.AIR) {
			Item cauldron = block == Blocks.WATER_CAULDRON || block == Blocks.LAVA_CAULDRON || block == Blocks.POWDER_SNOW_CAULDRON ? Items.CAULDRON : null;
			if (cauldron != null) {
				add(items, material(cauldron), 1);
			} else {
				add(noItem, BuiltInRegistries.BLOCK.getKey(block), 1);
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
		add(items, material(item), count);
	}

	// --- breakdown ----------------------------------------------------------------------------------------------

	/**
	 * Breaks crafted components down into their ingredients. Items in {@code stockIn} (a chest's contents) are used
	 * first, at every level: if the chest holds 3 pistons, only the rest are broken down. {@code remaining} is what's
	 * still needed after that; with an empty stock it's the full list of base materials.
	 */
	public Breakdown breakDown(Map<Material, Integer> needed, Map<Material, Integer> stockIn) {
		GameRecipes.Snapshot snapshot = recipes.snapshot();
		Map<Material, Integer> demand = new HashMap<>(needed);
		Map<Material, Integer> stock = new HashMap<>(stockIn);
		// Parents before children, so a component's total demand is known before it's broken down.
		List<Material> order = new ArrayList<>();
		Set<Material> visited = new HashSet<>();

		for (Material material : needed.keySet()) {
			visit(material, snapshot, visited, new HashSet<>(), order);
		}

		Collections.reverse(order);

		List<Craft> crafts = new ArrayList<>();

		for (Material material : order) {
			int need = demand.getOrDefault(material, 0);
			int fromStock = Math.min(need, stock.getOrDefault(material, 0));

			if (fromStock > 0) {
				need -= fromStock;
				stock.merge(material, -fromStock, Integer::sum);
			}

			Optional<Recipe> recipe = expandable(material, snapshot);

			if (need <= 0 || recipe.isEmpty() || !(material instanceof Material.Single single)) {
				demand.put(material, need);
				continue;
			}

			Recipe used = recipe.get();
			int times = (need + used.count() - 1) / used.count();
			crafts.add(new Craft(single.item(), times, times * used.count(), need, used));
			demand.put(material, 0);
			used.ingredients().forEach((ingredient, perCraft) -> demand.merge(ingredient, perCraft * times, Integer::sum));
		}

		Map<Material, Integer> remaining = new TreeMap<>();
		demand.forEach((material, count) -> {
			if (count > 0) {
				remaining.put(material, count);
			}
		});
		return new Breakdown(remaining, crafts);
	}

	/** Checks the materials for {@code placed} against {@code stock}; see {@link Comparison}. */
	public Comparison compare(Map<Material, Integer> placed, Map<Material, Integer> stock) {
		return compare(placed, stock, breakDown(placed, Map.of()), breakDown(placed, stock));
	}

	/** The comparison, given the breakdown with nothing in stock ({@code full}) and with the stock ({@code withStock}). */
	static Comparison compare(Map<Material, Integer> placed, Map<Material, Integer> stock, Breakdown full, Breakdown withStock) {
		Map<Material, Integer> missingAsPlaced = new TreeMap<>();
		placed.forEach((material, need) -> {
			int shortfall = need - stock.getOrDefault(material, 0);
			if (shortfall > 0) {
				missingAsPlaced.put(material, shortfall);
			}
		});

		Set<Material> materialsAndIngredients = new HashSet<>(placed.keySet());
		full.crafts().forEach(c -> materialsAndIngredients.addAll(c.recipe().ingredients().keySet()));
		Map<Material, Integer> useful = new TreeMap<>();
		Map<Material, Integer> notNeeded = new TreeMap<>();
		stock.forEach((material, count) -> (materialsAndIngredients.contains(material) ? useful : notNeeded).put(material, count));
		return new Comparison(missingAsPlaced, withStock, useful, notNeeded);
	}

	/** Reads the game's recipes ahead of the first question (thousands with mods), so the first breakdown is quick. */
	public void prepare() {
		recipes.snapshot();
	}

	/**
	 * A crafting recipe's ingredients per craft, each as a {@link #material}. Empty for other kinds (smelting,
	 * stonecutting...), which don't make "crafted components", and for recipes that don't make a fixed item.
	 */
	static Optional<Recipe> craftingRecipe(GameRecipe recipe) {
		List<GameRecipe.Ingredient> ingredients = switch (recipe) {
			case GameRecipe.Shaped shaped -> shaped.slots().stream().flatMap(Optional::stream).toList();
			case GameRecipe.Shapeless shapeless -> shapeless.ingredients();
			default -> List.of();
		};
		Optional<GameRecipe.Output> made = recipe.makes();

		if (ingredients.isEmpty() || !(made.orElse(null) instanceof GameRecipe.Output.Stack stack)) {
			return Optional.empty();
		}

		Map<Material, Integer> counts = new LinkedHashMap<>();
		ingredients.forEach(ingredient -> material(ingredient).ifPresent(material -> add(counts, material, 1)));
		return Optional.of(new Recipe(recipe.id(), stack.count(), counts));
	}

	/** An ingredient as a material: its tag when it names one, otherwise its first item (coal for "coal or charcoal"). */
	static Optional<Material> material(GameRecipe.Ingredient ingredient) {
		if (ingredient.tag().isPresent()) {
			return Optional.of(new Material.AnyOf(ingredient.tag().get()));
		}

		return ingredient.items().stream().findFirst()
			.flatMap(Holder::unwrapKey)
			.map(key -> Material.item(key.identifier()));
	}

	/** Depth-first post-order through expandable recipes; a cycle (ingots ↔ blocks) stops at the repeated item. */
	private static void visit(Material material, GameRecipes.Snapshot snapshot, Set<Material> visited, Set<Material> path, List<Material> order) {
		if (visited.contains(material) || !path.add(material)) {
			return;
		}

		expandable(material, snapshot).ifPresent(recipe -> {
			for (Material ingredient : recipe.ingredients().keySet()) {
				if (!path.contains(ingredient)) {
					visit(ingredient, snapshot, visited, path, order);
				}
			}
		});
		path.remove(material);
		visited.add(material);
		order.add(material);
	}

	/**
	 * The recipe to break a material down with: its main recipe, when that's a crafting recipe with two or more
	 * different ingredients, none of them the material itself. Empty for a base material, and for a tag.
	 */
	private static Optional<Recipe> expandable(Material material, GameRecipes.Snapshot snapshot) {
		if (!(material instanceof Material.Single single)) {
			return Optional.empty();
		}

		return snapshot.mainRecipe(single.item())
			.flatMap(Materials::craftingRecipe)
			.filter(recipe -> recipe.ingredients().size() >= 2 && !recipe.ingredients().containsKey(material));
	}

	private static <K> void add(Map<K, Integer> map, K key, int count) {
		map.merge(key, count, Integer::sum);
	}

	private static Material material(Item item) {
		return Material.item(BuiltInRegistries.ITEM.getKey(item));
	}
}
