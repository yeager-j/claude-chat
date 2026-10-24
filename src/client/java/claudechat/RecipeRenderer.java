package claudechat;

import claudechat.GameRecipe.Ingredient;
import claudechat.GameRecipe.Output;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Draws the game's recipes ({@link GameRecipes}) as rows of item icons, like the crafting table. Builds chat
 * components from atlas sprites and translations, so call it on the client thread, with a snapshot of the recipes
 * read beforehand (reading them can take a while).
 */
public final class RecipeRenderer {
	/**
	 * Chat lines for a recipe. {@code grid} is true when they are the recipe's icon rows, which need every line to
	 * start at the same x; false for a note such as "(no recipe found …)".
	 */
	public record Lines(List<Component> lines, boolean grid) {
		private static Lines note(String text) {
			return new Lines(List.of(Component.literal(text).withStyle(ChatFormatting.GRAY)), false);
		}
	}

	private RecipeRenderer() {
	}

	/** The lines for [[recipe:id]] on its own line: the recipe as a grid when {@code recipes} has one. */
	public static Lines render(GameRecipes.Snapshot recipes, Identifier id) {
		return recipes.find(id)
			.map(RecipeRenderer::render)
			.orElseGet(() -> Lines.note("(no recipe found for " + id + ")"));
	}

	/** What [[recipe:id]] in the middle of a sentence shows: the recipe's result, or the item with that id. */
	public static MutableComponent resultName(GameRecipes.Snapshot recipes, Identifier id) {
		return recipes.find(id)
			.flatMap(GameRecipe::makes)
			.map(RecipeRenderer::result)
			.orElseGet(() -> ItemIcons.named(id));
	}

	private static Lines render(GameRecipe recipe) {
		return switch (recipe) {
			case GameRecipe.Shaped shaped -> new Lines(shaped(shaped), true);
			case GameRecipe.Shapeless shapeless -> grid(row(shapeless.ingredients(), counted(shapeless.output()), Optional.empty()));
			case GameRecipe.Cooking cooking -> grid(row(List.of(cooking.input()), counted(cooking.output()), Optional.of(cookingNote(cooking))));
			case GameRecipe.Stonecutting cutting -> grid(row(List.of(cutting.input()), counted(cutting.output()), cutting.station().map(RecipeRenderer::stationName)));
			case GameRecipe.Smithing smithing -> grid(row(smithing.inputs(), counted(smithing.output()), smithing.station().map(RecipeRenderer::stationName)));
			case GameRecipe.Special special -> Lines.note("(special recipe: " + special.type() + ")");
		};
	}

	private static Lines grid(Component row) {
		return new Lines(List.of(row), true);
	}

	/** "Blast Furnace, 5s, 0.1 xp", with the station's name in the player's language. */
	private static Component cookingNote(GameRecipe.Cooking cooking) {
		double seconds = cooking.ticks() / 20.0;
		String time = seconds % 1 == 0 ? String.valueOf((int) seconds) : String.valueOf(seconds);
		String xp = cooking.experience() > 0 ? ", " + cooking.experience() + " xp" : "";
		MutableComponent note = Component.empty();

		cooking.station().ifPresent(station -> note.append(stationName(station)).append(", "));
		return note.append(time + "s" + xp);
	}

	private static MutableComponent stationName(Holder<Item> station) {
		return new ItemStack(station.value()).getHoverName().copy();
	}

	/** A shaped recipe as a grid, with "→ result" on the middle row. */
	private static List<Component> shaped(GameRecipe.Shaped recipe) {
		int width = recipe.width();
		int height = recipe.slots().size() / width;
		List<Component> lines = new ArrayList<>();

		for (int r = 0; r < height; r++) {
			MutableComponent line = Component.literal("  ");

			for (Optional<Ingredient> slot : recipe.slots().subList(r * width, (r + 1) * width)) {
				line.append(slot.map(RecipeRenderer::slot).orElseGet(ItemIcons::emptySlot));
			}

			if (r == (height - 1) / 2) {
				line.append(Component.literal("  → ").withStyle(ChatFormatting.GRAY)).append(counted(recipe.output()));
			}

			lines.add(line);
		}

		return lines;
	}

	/** Ingredients in a row: "[a] + [b] → result (station)". */
	private static Component row(List<Ingredient> ingredients, MutableComponent result, Optional<Component> station) {
		MutableComponent line = Component.literal("  ");

		for (int i = 0; i < ingredients.size(); i++) {
			if (i > 0) {
				line.append(Component.literal(" + ").withStyle(ChatFormatting.GRAY));
			}

			line.append(slot(ingredients.get(i)));
		}

		line.append(Component.literal("  → ").withStyle(ChatFormatting.GRAY)).append(result);
		station.ifPresent(note -> line.append(Component.empty().withStyle(ChatFormatting.GRAY).append(" (").append(note).append(")")));
		return line;
	}

	/** The first matching item's icon; if several items fit, hovering lists them. */
	private static MutableComponent slot(Ingredient ingredient) {
		if (ingredient.items().isEmpty()) {
			return Component.literal("[" + label(ingredient) + "]").withStyle(ChatFormatting.GRAY);
		}

		MutableComponent icon = ItemIcons.iconOrName(ingredient.items().getFirst());

		if (ingredient.items().size() > 1) {
			icon.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal(label(ingredient)))));
		}

		return icon;
	}

	/** "any planks" for a tag, otherwise the items' names: "Coal or Charcoal". */
	private static String label(Ingredient ingredient) {
		if (ingredient.tag().isPresent()) {
			return "any " + ItemIcons.readableName(ingredient.tag().get().location());
		}

		List<String> names = ingredient.items().stream().map(item -> ItemIcons.name(item).getString()).toList();
		return names.isEmpty() ? "?" : String.join(" or ", names);
	}

	/** The result with how many the recipe makes: "4× [icon] Torch". */
	private static MutableComponent counted(Output output) {
		MutableComponent out = Component.empty();

		if (output instanceof Output.Stack stack && stack.count() > 1) {
			out.append(Component.literal(stack.count() + "× ").withStyle(ChatFormatting.WHITE));
		}

		return out.append(result(output));
	}

	/** What the recipe makes. A trim recipe's result is the armor that went in, trimmed, so it shows the trim's name. */
	private static MutableComponent result(Output output) {
		return switch (output) {
			case Output.Stack stack -> ItemIcons.named(stack.item());
			case Output.Trim trim -> trim.pattern().value().description().copy().withStyle(ChatFormatting.YELLOW);
		};
	}
}
