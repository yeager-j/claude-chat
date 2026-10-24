package claudechat;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameRecipesTest {
	private static DynamicOps<JsonElement> ops;

	@BeforeAll
	static void bootstrap() {
		GameBootstrap.init();
		ops = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).createSerializationContext(JsonOps.INSTANCE);
	}

	@Test
	void shapedRecipesKeepTheirGridAndEveryAlternative() {
		GameRecipe recipe = parse("torch", """
			{"type": "minecraft:crafting_shaped", "key": {"#": "minecraft:stick", "X": ["minecraft:coal", "minecraft:charcoal"]},
			 "pattern": ["X", "#"], "result": {"count": 4, "id": "minecraft:torch"}}
			""");

		GameRecipe.Shaped shaped = assertInstanceOf(GameRecipe.Shaped.class, recipe);
		assertEquals(1, shaped.width());
		assertEquals(List.of(Items.COAL, Items.CHARCOAL), items(shaped.slots().get(0).orElseThrow()));
		assertEquals(List.of(Items.STICK), items(shaped.slots().get(1).orElseThrow()));
		assertEquals(new GameRecipe.Output.Stack(Items.TORCH.builtInRegistryHolder(), 4), shaped.output());
	}

	@Test
	void shapedGridsKeepEmptySlots() {
		GameRecipe recipe = parse("bowl", """
			{"type": "minecraft:crafting_shaped", "key": {"#": "minecraft:oak_planks"}, "pattern": ["# #", " # "],
			 "result": {"count": 4, "id": "minecraft:bowl"}}
			""");

		GameRecipe.Shaped shaped = assertInstanceOf(GameRecipe.Shaped.class, recipe);
		assertEquals(3, shaped.width());
		assertEquals(List.of(true, false, true, false, true, false), shaped.slots().stream().map(Optional::isPresent).toList());
	}

	@Test
	void cookingAndStonecuttingKeepTheirStation() {
		GameRecipe.Cooking smelted = assertInstanceOf(GameRecipe.Cooking.class, parse("iron_ingot_from_smelting_raw_iron", """
			{"type": "minecraft:smelting", "cookingtime": 200, "experience": 0.7, "ingredient": "minecraft:raw_iron",
			 "result": {"id": "minecraft:iron_ingot"}}
			"""));
		GameRecipe.Stonecutting cut = assertInstanceOf(GameRecipe.Stonecutting.class, parse("stone_bricks_from_stone_stonecutting", """
			{"type": "minecraft:stonecutting", "ingredient": "minecraft:stone", "result": {"id": "minecraft:stone_bricks"}}
			"""));

		assertEquals(List.of(Items.RAW_IRON), items(smelted.input()));
		assertEquals(200, smelted.ticks());
		assertEquals(0.7f, smelted.experience());
		assertEquals(Optional.of(Items.FURNACE), smelted.station().map(Holder::value));
		assertEquals(Optional.of(Items.STONECUTTER), cut.station().map(Holder::value));
	}

	@Test
	void ingredientsParseTheWayTheGameParsesThem() {
		// 26.x ingredients are an id, a #tag or a list of ids; the older {"item": ...} object no longer loads.
		Optional<GameRecipe> oldStyle = GameRecipes.parse(id("old_style"), JsonParser.parseString("""
			{"type": "minecraft:crafting_shapeless", "ingredients": [{"item": "minecraft:stick"}], "result": {"id": "minecraft:torch"}}
			"""), ops);

		assertEquals(Optional.empty(), oldStyle);
	}

	@Test
	void theMainRecipeIsNamedAfterTheItemThenCraftingThenLowestId() {
		Identifier ingot = Identifier.withDefaultNamespace("iron_ingot");
		GameRecipe fromNuggets = shapeless("iron_ingot_from_nuggets", Items.IRON_INGOT);
		GameRecipe fromBlock = shapeless("iron_ingot_from_iron_block", Items.IRON_INGOT);
		GameRecipe smelted = cooking("iron_ingot_from_blasting_raw_iron", Items.IRON_INGOT);
		GameRecipe named = cooking("iron_ingot", Items.IRON_INGOT);

		assertEquals(fromBlock, GameRecipes.mainRecipe(ingot, List.of(smelted, fromNuggets, fromBlock)));
		assertEquals(fromBlock, GameRecipes.mainRecipe(ingot, List.of(fromBlock, fromNuggets, smelted)));
		assertEquals(named, GameRecipes.mainRecipe(ingot, List.of(fromBlock, named, fromNuggets)));
	}

	@Test
	void findTriesTheRecipeIdThenTheItemsMainRecipe() {
		GameRecipe smelted = cooking("iron_ingot_from_smelting_raw_iron", Items.IRON_INGOT);
		GameRecipe fromBlock = shapeless("iron_ingot_from_iron_block", Items.IRON_INGOT);
		GameRecipes.Snapshot snapshot = GameRecipes.Snapshot.of(List.of(smelted, fromBlock));

		assertEquals(Optional.of(smelted), snapshot.find(id("iron_ingot_from_smelting_raw_iron")));
		assertEquals(Optional.of(fromBlock), snapshot.find(id("iron_ingot")));
		assertEquals(Optional.empty(), snapshot.find(id("diamond")));
	}

	@Test
	void aHigherPriorityPackReplacesARecipeFile(@TempDir Path dir) throws IOException {
		Path pack = write(dir.resolve("pack/data"), "minecraft/recipe/piston.json");
		Path vanilla = write(dir.resolve("vanilla/data"), "minecraft/recipe/piston.json");
		write(dir.resolve("vanilla/data"), "minecraft/recipe/sub/folder.json");

		Map<Identifier, Path> files = GameRecipes.recipeFiles(List.of(pack, vanilla));

		assertEquals(pack.resolve("minecraft/recipe/piston.json"), files.get(id("piston")));
		assertTrue(files.containsKey(id("sub/folder")));
	}

	@Test
	void filesAreReadInFullWithinTheLimits(@TempDir Path dir) throws IOException {
		Path data = recipes(dir, 5);

		GameRecipes.Snapshot snapshot = GameRecipes.fromFiles(List.of(data), ops, GameRecipes.Limits.DEFAULT);

		assertTrue(snapshot.complete());
		assertEquals(5, snapshot.byId().size());
	}

	@Test
	void tooManyEntriesStopTheWalk(@TempDir Path dir) throws IOException {
		Path data = recipes(dir, 50);

		GameRecipes.Snapshot snapshot = GameRecipes.fromFiles(List.of(data), ops, limits(10, 16, 1024, 1 << 20, 64));

		assertFalse(snapshot.complete());
		assertTrue(snapshot.byId().size() < 10, "read " + snapshot.byId().size());
	}

	@Test
	void theByteBudgetStopsReading(@TempDir Path dir) throws IOException {
		Path data = recipes(dir, 50);
		long fileSize = Files.size(data.resolve("minecraft/recipe/r0.json"));

		GameRecipes.Snapshot snapshot = GameRecipes.fromFiles(List.of(data), ops, limits(1000, 16, 1024, fileSize * 3, 64));

		assertFalse(snapshot.complete());
		assertEquals(3, snapshot.byId().size());
	}

	@Test
	void anOversizedOrTooDeepFileIsSkipped(@TempDir Path dir) throws IOException {
		Path data = recipes(dir, 2);
		Files.writeString(data.resolve("minecraft/recipe/big.json"), " ".repeat(2000) + "{}");
		Files.writeString(data.resolve("minecraft/recipe/deep.json"), "[".repeat(100) + "]".repeat(100));

		GameRecipes.Snapshot snapshot = GameRecipes.fromFiles(List.of(data), ops, limits(1000, 16, 1024, 1 << 20, 64));

		assertFalse(snapshot.complete());
		assertEquals(2, snapshot.byId().size());
	}

	@Test
	void foldersPastTheDepthLimitArentRead(@TempDir Path dir) throws IOException {
		Path data = recipes(dir, 1);
		Path deep = data.resolve("minecraft/recipe/a/b/c/d");
		Files.createDirectories(deep);
		Files.writeString(deep.resolve("stick.json"), stickRecipe());

		GameRecipes.Snapshot snapshot = GameRecipes.fromFiles(List.of(data), ops, limits(1000, 3, 1024, 1 << 20, 64));

		assertFalse(snapshot.complete());
		assertEquals(1, snapshot.byId().size());
	}

	@Test
	void anInterruptStopsReading(@TempDir Path dir) throws IOException {
		Path data = recipes(dir, 5);
		Thread.currentThread().interrupt();

		try {
			GameRecipes.Snapshot snapshot = GameRecipes.fromFiles(List.of(data), ops, GameRecipes.Limits.DEFAULT);

			assertFalse(snapshot.complete());
			assertEquals(0, snapshot.byId().size());
		} finally {
			Thread.interrupted();
		}
	}

	private static GameRecipes.Limits limits(int entries, int depth, int fileBytes, long totalBytes, int jsonDepth) {
		return new GameRecipes.Limits(entries, depth, fileBytes, totalBytes, jsonDepth, Duration.ofSeconds(30));
	}

	/** {@code count} stick recipes, r0 to r(count - 1), under {@code dir}/data; returns the data folder. */
	private static Path recipes(Path dir, int count) throws IOException {
		Path recipeDir = dir.resolve("data/minecraft/recipe");
		Files.createDirectories(recipeDir);

		for (int i = 0; i < count; i++) {
			Files.writeString(recipeDir.resolve("r" + i + ".json"), stickRecipe());
		}

		return dir.resolve("data");
	}

	private static String stickRecipe() {
		return """
			{"type": "minecraft:crafting_shaped", "key": {"#": "minecraft:oak_planks"}, "pattern": ["#", "#"],
			 "result": {"count": 4, "id": "minecraft:stick"}}
			""";
	}

	private static GameRecipe parse(String path, String json) {
		return GameRecipes.parse(id(path), JsonParser.parseString(json), ops).orElseThrow();
	}

	private static GameRecipe shapeless(String path, Item result) {
		GameRecipe.Ingredient stick = new GameRecipe.Ingredient(List.of(Items.STICK.builtInRegistryHolder()), Optional.empty());
		return new GameRecipe.Shapeless(id(path), List.of(stick), new GameRecipe.Output.Stack(result.builtInRegistryHolder(), 1));
	}

	private static GameRecipe cooking(String path, Item result) {
		GameRecipe.Ingredient raw = new GameRecipe.Ingredient(List.of(Items.RAW_IRON.builtInRegistryHolder()), Optional.empty());
		return new GameRecipe.Cooking(id(path), raw, new GameRecipe.Output.Stack(result.builtInRegistryHolder(), 1), Optional.empty(), 200, 0);
	}

	private static List<Item> items(GameRecipe.Ingredient ingredient) {
		return ingredient.items().stream().map(Holder::value).toList();
	}

	private static Identifier id(String path) {
		return Identifier.withDefaultNamespace(path);
	}

	/** An empty recipe file at {@code relative} under {@code data}; returns {@code data}. */
	private static Path write(Path data, String relative) throws IOException {
		Path file = data.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.writeString(file, "{}");
		return data;
	}
}
