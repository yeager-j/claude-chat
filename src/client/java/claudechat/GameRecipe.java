package claudechat;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;
import net.minecraft.world.item.equipment.trim.TrimPattern;

import java.util.List;
import java.util.Optional;

/**
 * One recipe as recipe grids and material lists use it, read from the game's own display of it (what the recipe book
 * shows), so modded recipe types that display like vanilla ones work too. Immutable: tag ingredients hold the items
 * the tag had when the recipe was read, so it can be used from any thread.
 */
public sealed interface GameRecipe {
	Identifier id();

	/** A shaped crafting recipe: {@code slots} row by row, {@code width} to a row, empty for an empty slot. */
	record Shaped(Identifier id, int width, List<Optional<Ingredient>> slots, Output output) implements GameRecipe {
	}

	record Shapeless(Identifier id, List<Ingredient> ingredients, Output output) implements GameRecipe {
	}

	/** Smelting, blasting, smoking or campfire cooking, at {@code station}, taking {@code ticks}. */
	record Cooking(Identifier id, Ingredient input, Output output, Optional<Holder<Item>> station, int ticks, float experience)
		implements GameRecipe {
	}

	record Stonecutting(Identifier id, Ingredient input, Output output, Optional<Holder<Item>> station) implements GameRecipe {
	}

	/** A smithing table recipe; {@code inputs} are template, base and addition, leaving out the ones it doesn't use. */
	record Smithing(Identifier id, List<Ingredient> inputs, Output output, Optional<Holder<Item>> station) implements GameRecipe {
	}

	/** A recipe the game works out when crafting (map cloning, firework stars...), or one with no display: just its type. */
	record Special(Identifier id, Identifier type) implements GameRecipe {
	}

	/** What fits in one slot: the items, and the tag they come from when the recipe names one ("any planks"). */
	record Ingredient(List<Holder<Item>> items, Optional<TagKey<Item>> tag) {
		static final Ingredient NONE = new Ingredient(List.of(), Optional.empty());
	}

	/** What a recipe makes: a stack of an item, or for a trim recipe, the trim it puts on the armor that goes in. */
	sealed interface Output {
		record Stack(Holder<Item> item, int count) implements Output {
		}

		record Trim(Holder<TrimPattern> pattern) implements Output {
		}
	}

	/** What it makes; empty for a special recipe. */
	default Optional<Output> makes() {
		return switch (this) {
			case Shaped shaped -> Optional.of(shaped.output());
			case Shapeless shapeless -> Optional.of(shapeless.output());
			case Cooking cooking -> Optional.of(cooking.output());
			case Stonecutting stonecutting -> Optional.of(stonecutting.output());
			case Smithing smithing -> Optional.of(smithing.output());
			case Special ignored -> Optional.empty();
		};
	}

	/** The item it makes, when it makes a fixed item (not a trim, not a special recipe). */
	default Optional<Holder<Item>> resultItem() {
		return makes().flatMap(output -> output instanceof Output.Stack stack ? Optional.of(stack.item()) : Optional.empty());
	}

	/** Made at a crafting table from a fixed set of ingredients. */
	default boolean isCrafting() {
		return this instanceof Shaped || this instanceof Shapeless;
	}

	/**
	 * The game's recipe as a {@link GameRecipe}, through its first display. A recipe without a display the model knows
	 * becomes {@link Special}.
	 */
	static GameRecipe of(Identifier id, Recipe<?> recipe) {
		Identifier type = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
		Special special = new Special(id, type);

		return recipe.display().stream()
			.findFirst()
			.flatMap(display -> fromDisplay(id, display))
			.orElse(special);
	}

	private static Optional<GameRecipe> fromDisplay(Identifier id, RecipeDisplay display) {
		Optional<Output> output = output(display.result());

		if (output.isEmpty()) {
			return Optional.empty();
		}

		Output made = output.get();
		Optional<Holder<Item>> station = ingredient(display.craftingStation()).items().stream().findFirst();

		GameRecipe recipe = switch (display) {
			case ShapedCraftingRecipeDisplay shaped -> new Shaped(id, shaped.width(), shaped.ingredients().stream().map(GameRecipe::slot).toList(), made);
			case ShapelessCraftingRecipeDisplay shapeless -> new Shapeless(id, ingredients(shapeless.ingredients()), made);
			case FurnaceRecipeDisplay furnace -> new Cooking(id, ingredient(furnace.ingredient()), made, station, furnace.duration(), furnace.experience());
			case StonecutterRecipeDisplay stonecutter -> new Stonecutting(id, ingredient(stonecutter.input()), made, station);
			case SmithingRecipeDisplay smithing -> new Smithing(id, ingredients(List.of(smithing.template(), smithing.base(), smithing.addition())),
				made, station);
			default -> null;
		};

		return Optional.ofNullable(recipe);
	}

	/** A grid slot: empty for {@link SlotDisplay.Empty}. */
	private static Optional<Ingredient> slot(SlotDisplay display) {
		return display instanceof SlotDisplay.Empty ? Optional.empty() : Optional.of(ingredient(display));
	}

	/** The slots that hold something. */
	private static List<Ingredient> ingredients(List<SlotDisplay> displays) {
		return displays.stream().flatMap(display -> slot(display).stream()).toList();
	}

	/** What a slot accepts. A tag that isn't bound (nothing loaded it) keeps its name and has no items. */
	static Ingredient ingredient(SlotDisplay display) {
		return switch (display) {
			case SlotDisplay.TagSlotDisplay(HolderSet<Item> set) -> new Ingredient(set.isBound() ? set.stream().toList() : List.of(), set.unwrapKey());
			case SlotDisplay.ItemSlotDisplay(Holder<Item> item) -> new Ingredient(List.of(item), Optional.empty());
			case SlotDisplay.ItemStackSlotDisplay(ItemStackTemplate stack) -> new Ingredient(List.of(stack.item()), Optional.empty());
			case SlotDisplay.WithRemainder(SlotDisplay input, SlotDisplay ignored) -> ingredient(input);
			case SlotDisplay.Composite(List<SlotDisplay> contents) -> new Ingredient(
				contents.stream().flatMap(content -> ingredient(content).items().stream()).distinct().toList(), Optional.empty());
			default -> Ingredient.NONE;
		};
	}

	/** What a result slot shows. A result that depends on the input (a dyed shulker box) shows its first case. */
	private static Optional<Output> output(SlotDisplay display) {
		return switch (display) {
			case SlotDisplay.ItemStackSlotDisplay(ItemStackTemplate stack) -> Optional.of(new Output.Stack(stack.item(), stack.count()));
			case SlotDisplay.ItemSlotDisplay(Holder<Item> item) -> Optional.of(new Output.Stack(item, 1));
			case SlotDisplay.SmithingTrimDemoSlotDisplay trim -> Optional.of(new Output.Trim(trim.pattern()));
			case SlotDisplay.Composite(List<SlotDisplay> contents) -> contents.stream().findFirst().flatMap(GameRecipe::output);
			default -> Optional.empty();
		};
	}
}
