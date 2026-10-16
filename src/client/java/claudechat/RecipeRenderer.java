package claudechat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;

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

	private final KnowledgeBase knowledge;
	private Map<String, Path> recipesByResult;
	private int indexedGeneration = -1;

	public RecipeRenderer(KnowledgeBase knowledge) {
		this.knowledge = knowledge;
	}

	/** Lines for "[[recipe:minecraft:crafter]]". The id is a recipe file, or failing that, an item it makes. */
	public List<Component> render(String recipeId) {
		String id = recipeId.contains(":") ? recipeId.trim() : "minecraft:" + recipeId.trim();
		String ns = id.substring(0, id.indexOf(':'));
		String path = id.substring(id.indexOf(':') + 1);

		Optional<Path> file = knowledge.findDataFile(ns + "/recipe/" + path + ".json")
			.or(() -> Optional.ofNullable(recipesByResult().get(id)));
		if (file.isEmpty()) {
			return List.of(Component.literal("(no recipe found for " + id + ")").withStyle(ChatFormatting.GRAY));
		}
		try {
			JsonObject recipe = JsonParser.parseString(Files.readString(file.get())).getAsJsonObject();
			return render(recipe);
		} catch (IOException | RuntimeException e) {
			ClaudeChatClient.LOGGER.warn("Could not render recipe {}", id, e);
			return List.of(Component.literal("(couldn't read recipe " + id + ")").withStyle(ChatFormatting.GRAY));
		}
	}

	private List<Component> render(JsonObject recipe) {
		String type = recipe.get("type").getAsString().replace("minecraft:", "");
		MutableComponent result = result(recipe.get("result"));
		return switch (type) {
			case "crafting_shaped" -> shaped(recipe, result);
			case "crafting_shapeless" -> List.of(row(ingredients(recipe.getAsJsonArray("ingredients")), result, null));
			case "smelting", "blasting", "smoking", "campfire_cooking" -> {
				String station = switch (type) {
					case "blasting" -> "Blast Furnace";
					case "smoking" -> "Smoker";
					case "campfire_cooking" -> "Campfire";
					default -> "Furnace";
				};
				double seconds = (recipe.has("cookingtime") ? recipe.get("cookingtime").getAsInt() : 200) / 20.0;
				String xp = recipe.has("experience") ? ", " + recipe.get("experience").getAsFloat() + " xp" : "";
				yield List.of(row(List.of(ingredient(recipe.get("ingredient"))), result,
					station + ", " + (seconds % 1 == 0 ? (int) seconds : seconds) + "s" + xp));
			}
			case "stonecutting" -> List.of(row(List.of(ingredient(recipe.get("ingredient"))), result, "Stonecutter"));
			case "smithing_transform", "smithing_trim" -> List.of(row(List.of(
				ingredient(recipe.get("template")), ingredient(recipe.get("base")), ingredient(recipe.get("addition"))), result, "Smithing Table"));
			case "crafting_transmute" -> List.of(row(List.of(
				ingredient(recipe.get("input")), ingredient(recipe.get("material"))), result, null));
			default -> List.of(Component.literal("(special recipe: " + type + ")").withStyle(ChatFormatting.GRAY));
		};
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
	private static Component row(List<Ingredient> ingredients, MutableComponent result, String station) {
		MutableComponent line = Component.literal("  ");
		for (int i = 0; i < ingredients.size(); i++) {
			if (i > 0) {
				line.append(Component.literal(" + ").withStyle(ChatFormatting.GRAY));
			}
			line.append(slot(ingredients.get(i)));
		}
		line.append(Component.literal("  → ").withStyle(ChatFormatting.GRAY)).append(result);
		if (station != null) {
			line.append(Component.literal(" (" + station + ")").withStyle(ChatFormatting.GRAY));
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

	private MutableComponent result(JsonElement json) {
		JsonObject result = json.getAsJsonObject();
		int count = result.has("count") ? result.get("count").getAsInt() : 1;
		MutableComponent out = Component.empty();
		if (count > 1) {
			out.append(Component.literal(count + "× ").withStyle(ChatFormatting.WHITE));
		}
		ItemIcons.find(result.get("id").getAsString()).ifPresentOrElse(
			item -> out.append(ItemIcons.named(item)),
			() -> out.append(Component.literal(result.get("id").getAsString())));
		return out;
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
				names.add("any " + id.substring(1).replaceAll(".*[:/]", "").replace('_', ' '));
				expandTag(id.substring(1), items, 0);
			} else {
				ItemIcons.find(id).ifPresent(item -> {
					items.add(item);
					names.add(ItemIcons.name(item).getString());
				});
			}
		}
		return new Ingredient(items, String.join(" or ", names));
	}

	private void expandTag(String tag, List<Holder<Item>> into, int depth) {
		if (depth > 5) {
			return;
		}
		String ns = tag.contains(":") ? tag.substring(0, tag.indexOf(':')) : "minecraft";
		String path = tag.substring(tag.indexOf(':') + 1);
		Optional<Path> file = knowledge.findDataFile(ns + "/tags/item/" + path + ".json");
		if (file.isEmpty()) {
			return;
		}
		try {
			JsonArray values = JsonParser.parseString(Files.readString(file.get())).getAsJsonObject().getAsJsonArray("values");
			for (JsonElement value : values) {
				String id = value.isJsonObject() ? value.getAsJsonObject().get("id").getAsString() : value.getAsString();
				if (id.startsWith("#")) {
					expandTag(id.substring(1), into, depth + 1);
				} else {
					ItemIcons.find(id).ifPresent(into::add);
				}
			}
		} catch (IOException | RuntimeException e) {
			ClaudeChatClient.LOGGER.debug("Could not read tag {}", tag, e);
		}
	}

	/** Recipe files are named freely (iron_ingot_from_smelting_raw_iron), so also index them by what they make. */
	private synchronized Map<String, Path> recipesByResult() {
		// Rebuild when new data has arrived (e.g. a mod finished extracting).
		if (recipesByResult != null && indexedGeneration == knowledge.generation()) {
			return recipesByResult;
		}
		indexedGeneration = knowledge.generation();
		Map<String, Path> index = new HashMap<>();
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
									String result = recipe.getAsJsonObject("result").get("id").getAsString();
									// Prefer the recipe whose file is named after the item (the "main" recipe).
									String fileName = file.getFileName().toString().replace(".json", "");
									if (!index.containsKey(result) || result.endsWith(":" + fileName)) {
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
