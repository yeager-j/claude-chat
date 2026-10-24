package claudechat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Draws recipes from the knowledge base's data pack JSON as rows of item icons, like the crafting table. */
public final class RecipeRenderer {
	/** One ingredient slot: the items that fit, and a label for the hover text. */
	private record Ingredient(List<Holder<Item>> items, String label) {
	}

	/**
	 * Chat lines for a recipe. {@code grid} is true when they are the recipe's icon rows, which need every line to
	 * start at the same x; false for a note such as "(no recipe found …)".
	 */
	public record Lines(List<Component> lines, boolean grid) {
		private static Lines note(String text) {
			return new Lines(List.of(Component.literal(text).withStyle(ChatFormatting.GRAY)), false);
		}
	}

	private final KnowledgeBase knowledge;
	private Map<Identifier, Path> recipesByResult;
	private int indexedGeneration = -1;

	public RecipeRenderer(KnowledgeBase knowledge) {
		this.knowledge = knowledge;
	}

	/** The lines for [[recipe:id]] on its own line: the recipe as a grid when one is found. */
	public Lines render(Identifier id) {
		Optional<Path> file = find(id);
		if (file.isEmpty()) {
			return Lines.note("(no recipe found for " + id + ")");
		}

		try {
			return render(read(file.get()));
		} catch (IOException | RuntimeException e) {
			ClaudeChatClient.LOGGER.warn("Could not render recipe {}", id, e);
			return Lines.note("(couldn't read recipe " + id + ")");
		}
	}

	/** What [[recipe:id]] in the middle of a sentence shows: the recipe's result, or the item with that id. */
	public MutableComponent resultName(Identifier id) {
		Optional<Path> file = find(id);
		if (file.isEmpty()) {
			return ItemIcons.named(id);
		}

		try {
			return result(read(file.get()));
		} catch (IOException | RuntimeException e) {
			ClaudeChatClient.LOGGER.debug("Could not read recipe {}", id, e);
			return ItemIcons.named(id);
		}
	}

	/** The one place a recipe id gets its meaning: a recipe file of that name, or else a recipe that makes that item. */
	private Optional<Path> find(Identifier id) {
		return knowledge.findDataFile(id.getNamespace() + "/recipe/" + id.getPath() + ".json")
			.or(() -> Optional.ofNullable(recipesByResult().get(id)));
	}

	private static JsonObject read(Path file) throws IOException {
		return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
	}

	/** The recipe type without the "minecraft:" namespace: "crafting_shaped", "smithing_trim". */
	private static String type(JsonObject recipe) {
		return recipe.get("type").getAsString().replace("minecraft:", "");
	}

	private Lines render(JsonObject recipe) {
		String type = type(recipe);
		return switch (type) {
			case "crafting_shaped" -> new Lines(shaped(recipe, counted(recipe)), true);
			case "crafting_shapeless" -> grid(row(ingredients(recipe.getAsJsonArray("ingredients")), counted(recipe), null));
			case "smelting", "blasting", "smoking", "campfire_cooking" -> grid(row(List.of(ingredient(recipe.get("ingredient"))),
				counted(recipe), cookingNote(type, recipe)));
			case "stonecutting" -> grid(row(List.of(ingredient(recipe.get("ingredient"))), counted(recipe),
				Blocks.STONECUTTER.getName()));
			case "smithing_transform", "smithing_trim" -> grid(row(List.of(ingredient(recipe.get("template")),
				ingredient(recipe.get("base")), ingredient(recipe.get("addition"))), counted(recipe), Blocks.SMITHING_TABLE.getName()));
			case "crafting_transmute" -> grid(row(List.of(ingredient(recipe.get("input")), ingredient(recipe.get("material"))),
				counted(recipe), null));
			default -> Lines.note("(special recipe: " + type + ")");
		};
	}

	private static Lines grid(Component row) {
		return new Lines(List.of(row), true);
	}

	/** "Blast Furnace, 5s, 0.1 xp", with the station's name in the player's language. */
	private static Component cookingNote(String type, JsonObject recipe) {
		Block station = switch (type) {
			case "blasting" -> Blocks.BLAST_FURNACE;
			case "smoking" -> Blocks.SMOKER;
			case "campfire_cooking" -> Blocks.CAMPFIRE;
			default -> Blocks.FURNACE;
		};
		double seconds = (recipe.has("cookingtime") ? recipe.get("cookingtime").getAsInt() : 200) / 20.0;
		String time = seconds % 1 == 0 ? String.valueOf((int) seconds) : String.valueOf(seconds);
		String xp = recipe.has("experience") ? ", " + recipe.get("experience").getAsFloat() + " xp" : "";
		return station.getName().append(", " + time + "s" + xp);
	}

	/** A shaped recipe as a grid, with "→ result" on the middle row. */
	private List<Component> shaped(JsonObject recipe, MutableComponent result) {
		JsonObject key = recipe.getAsJsonObject("key");
		List<String> pattern = new ArrayList<>();
		recipe.getAsJsonArray("pattern").forEach(row -> pattern.add(row.getAsString()));
		int width = pattern.stream().mapToInt(String::length).max().orElse(0);
		Map<Character, Ingredient> keys = new HashMap<>();
		key.entrySet().forEach(entry -> keys.put(entry.getKey().charAt(0), ingredient(entry.getValue())));

		List<Component> lines = new ArrayList<>();
		for (int r = 0; r < pattern.size(); r++) {
			MutableComponent line = Component.literal("  ");
			String row = pattern.get(r);
			for (int c = 0; c < width; c++) {
				Ingredient ingredient = c < row.length() ? keys.get(row.charAt(c)) : null;
				line.append(ingredient == null ? ItemIcons.emptySlot() : slot(ingredient));
			}
			if (r == (pattern.size() - 1) / 2) {
				line.append(Component.literal("  → ").withStyle(ChatFormatting.GRAY)).append(result);
			}
			lines.add(line);
		}
		return lines;
	}

	/** Ingredients in a row: "[a] + [b] → result (station)". */
	private static Component row(List<Ingredient> ingredients, MutableComponent result, Component station) {
		MutableComponent line = Component.literal("  ");
		for (int i = 0; i < ingredients.size(); i++) {
			if (i > 0) {
				line.append(Component.literal(" + ").withStyle(ChatFormatting.GRAY));
			}
			line.append(slot(ingredients.get(i)));
		}
		line.append(Component.literal("  → ").withStyle(ChatFormatting.GRAY)).append(result);
		if (station != null) {
			line.append(Component.empty().withStyle(ChatFormatting.GRAY).append(" (").append(station).append(")"));
		}
		return line;
	}

	/** The first matching item's icon; if several items fit, hovering lists them. */
	private static MutableComponent slot(Ingredient ingredient) {
		if (ingredient.items().isEmpty()) {
			return Component.literal("[" + ingredient.label() + "]").withStyle(ChatFormatting.GRAY);
		}
		MutableComponent icon = ItemIcons.iconOrName(ingredient.items().getFirst());
		if (ingredient.items().size() > 1) {
			icon.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal(ingredient.label()))));
		}
		return icon;
	}

	/** The result with how many the recipe makes: "4× [icon] Torch". */
	private static MutableComponent counted(JsonObject recipe) {
		JsonObject result = recipe.getAsJsonObject("result");
		int count = result != null && result.has("count") ? result.get("count").getAsInt() : 1;
		MutableComponent out = Component.empty();
		if (count > 1) {
			out.append(Component.literal(count + "× ").withStyle(ChatFormatting.WHITE));
		}
		return out.append(result(recipe));
	}

	/**
	 * What the recipe makes. A trim recipe has no result (the armor that goes in comes out trimmed), so it shows the
	 * trim's name.
	 */
	private static MutableComponent result(JsonObject recipe) {
		if (type(recipe).equals("smithing_trim")) {
			Identifier pattern = Identifier.parse(recipe.get("pattern").getAsString());
			return Component.translatableWithFallback(pattern.toLanguageKey("trim_pattern"),
				ItemIcons.readableName(pattern) + " armor trim").withStyle(ChatFormatting.YELLOW);
		}

		return ItemIcons.named(Identifier.parse(recipe.getAsJsonObject("result").get("id").getAsString()));
	}

	private List<Ingredient> ingredients(JsonArray array) {
		List<Ingredient> list = new ArrayList<>();
		array.forEach(element -> list.add(ingredient(element)));
		return list;
	}

	/** "minecraft:stick", "#minecraft:planks", or a list of either. */
	private Ingredient ingredient(JsonElement json) {
		if (json == null || json.isJsonNull()) {
			return new Ingredient(List.of(), "?");
		}
		List<String> ids = new ArrayList<>();
		if (json.isJsonArray()) {
			json.getAsJsonArray().forEach(element -> ids.add(element.getAsString()));
		} else {
			ids.add(json.getAsString());
		}

		List<Holder<Item>> items = new ArrayList<>();
		List<String> names = new ArrayList<>();
		for (String id : ids) {
			if (id.startsWith("#")) {
				Identifier tag = Identifier.parse(id.substring(1));
				names.add("any " + ItemIcons.readableName(tag));
				items.addAll(tagItems(tag));
			} else {
				ItemIcons.find(Identifier.parse(id)).ifPresent(item -> {
					items.add(item);
					names.add(ItemIcons.name(item).getString());
				});
			}
		}
		return new Ingredient(items, String.join(" or ", names));
	}

	/**
	 * The items in an item tag, from the tags the game loaded. Tag files merge across packs and mods (a mod's
	 * planks.json adds to vanilla's), so the game's merged tag is the only correct answer.
	 */
	private static List<Holder<Item>> tagItems(Identifier tag) {
		return BuiltInRegistries.ITEM.get(TagKey.create(Registries.ITEM, tag))
			.map(HolderSet.Named::stream)
			.map(Stream::toList)
			.orElse(List.of());
	}

	/** Recipe files are named freely (iron_ingot_from_smelting_raw_iron), so also index them by what they make. */
	private synchronized Map<Identifier, Path> recipesByResult() {
		// Rebuild when new data has arrived (e.g. a mod finished extracting).
		if (recipesByResult != null && indexedGeneration == knowledge.generation()) {
			return recipesByResult;
		}
		indexedGeneration = knowledge.generation();
		Map<Identifier, Path> index = new HashMap<>();
		List<Path> dataRoots = knowledge.dataRoots();
		if (dataRoots.isEmpty()) {
			return index; // knowledge base not built yet; try again next time
		}
		for (Path data : dataRoots) {
			try (Stream<Path> namespaces = Files.list(data)) {
				for (Path recipes : namespaces.map(ns -> ns.resolve("recipe")).filter(Files::isDirectory).toList()) {
					try (Stream<Path> files = Files.walk(recipes)) {
						for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
							try {
								JsonObject recipe = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
								if (recipe.has("result") && recipe.get("result").isJsonObject()) {
									Identifier result = Identifier.parse(recipe.getAsJsonObject("result").get("id").getAsString());
									// Prefer the recipe whose file is named after the item (the "main" recipe).
									String fileName = file.getFileName().toString().replace(".json", "");
									if (!index.containsKey(result) || result.getPath().equals(fileName)) {
										index.put(result, file);
									}
								}
							} catch (IOException | RuntimeException ignored) {
							}
						}
					}
				}
			} catch (IOException e) {
				ClaudeChatClient.LOGGER.debug("Could not index recipes in {}", data, e);
			}
		}
		recipesByResult = index;
		return index;
	}
}
