package claudechat;

import claudechat.Materials.Material;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialsTest {
	private static final TagKey<Item> PLANKS = TagKey.create(Registries.ITEM, Identifier.withDefaultNamespace("planks"));

	@BeforeAll
	static void bootstrap() {
		GameBootstrap.init();
	}

	@Test
	void placedItemsCountOnlyCountedBlocksAndTheFirstPartOfMultiBlockThings() throws IOException {
		BlockVolume volume = new BlockVolume.Builder(new Vec3i(7, 1, 1))
			.set(0, 0, 0, Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE))
			.set(1, 0, 0, Blocks.OAK_DOOR.defaultBlockState())
			.set(2, 0, 0, Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER))
			.set(3, 0, 0, Blocks.PISTON_HEAD.defaultBlockState())
			.set(4, 0, 0, Blocks.JIGSAW.defaultBlockState())
			.set(5, 0, 0, Blocks.STRUCTURE_VOID.defaultBlockState())
			.set(6, 0, 0, Blocks.FIRE.defaultBlockState())
			.build();
		Map<Identifier, Integer> noItem = new TreeMap<>();

		Map<Material, Integer> placed = Materials.asPlaced(volume, noItem);

		assertEquals(Map.of(item("stone_slab"), 2, item("oak_door"), 1), placed);
		assertEquals(Map.of(Identifier.withDefaultNamespace("fire"), 1), noItem);
	}

	@Test
	void comparisonSplitsStockIntoUsefulAndNotNeeded() {
		Materials.Recipe piston = new Materials.Recipe(Identifier.withDefaultNamespace("piston"), 1,
			Map.of(new Material.AnyOf(PLANKS), 3, item("cobblestone"), 4, item("iron_ingot"), 1, item("redstone"), 1));
		Map<Material, Integer> placed = Map.of(item("piston"), 2, item("redstone"), 5);
		Map<Material, Integer> stock = Map.of(item("redstone"), 4, item("iron_ingot"), 2, item("dirt"), 64);
		Materials.Breakdown full = new Materials.Breakdown(Map.of(),
			List.of(new Materials.Craft(Identifier.withDefaultNamespace("piston"), 2, 2, 2, piston)));
		Materials.Breakdown withStock = new Materials.Breakdown(Map.of(item("redstone"), 3), List.of());

		Materials.Comparison comparison = Materials.compare(placed, stock, full, withStock);

		assertEquals(Map.of(item("piston"), 2, item("redstone"), 1), comparison.missingAsPlaced());
		assertEquals(Map.of(item("redstone"), 4, item("iron_ingot"), 2), comparison.useful());
		assertEquals(Map.of(item("dirt"), 64), comparison.notNeeded());
		assertFalse(comparison.complete());
	}

	@Test
	void anIngredientIsItsTagOrElseItsFirstItem() {
		GameRecipe.Ingredient planks = GameRecipe.ingredient(new SlotDisplay.TagSlotDisplay(HolderSet.emptyNamed(BuiltInRegistries.ITEM, PLANKS)));
		GameRecipe.Ingredient coal = GameRecipe.ingredient(new SlotDisplay.TagSlotDisplay(
			HolderSet.direct(Items.COAL.builtInRegistryHolder(), Items.CHARCOAL.builtInRegistryHolder())));

		assertEquals(Optional.of(new Material.AnyOf(PLANKS)), Materials.material(planks));
		assertEquals(Optional.of(item("coal")), Materials.material(coal));
		assertEquals("#minecraft:planks", new Material.AnyOf(PLANKS).toString());
	}

	@Test
	void onlyCraftingRecipesBreakDown() {
		GameRecipe.Ingredient stick = GameRecipe.ingredient(new SlotDisplay.ItemSlotDisplay(Items.STICK));
		GameRecipe.Ingredient coal = GameRecipe.ingredient(new SlotDisplay.ItemSlotDisplay(Items.COAL));
		GameRecipe.Output fourTorches = new GameRecipe.Output.Stack(Items.TORCH.builtInRegistryHolder(), 4);
		GameRecipe shaped = new GameRecipe.Shaped(Identifier.withDefaultNamespace("torch"), 1,
			List.of(Optional.of(coal), Optional.of(stick)), fourTorches);
		GameRecipe smelted = new GameRecipe.Cooking(Identifier.withDefaultNamespace("torch_from_smelting"), coal, fourTorches,
			Optional.empty(), 200, 0);

		Optional<Materials.Recipe> recipe = Materials.craftingRecipe(shaped);

		assertTrue(recipe.isPresent());
		assertEquals(4, recipe.get().count());
		assertEquals(Map.of(item("coal"), 1, item("stick"), 1), recipe.get().ingredients());
		assertEquals(Optional.empty(), Materials.craftingRecipe(smelted));
	}

	private static Material item(String path) {
		return Material.item(Identifier.withDefaultNamespace(path));
	}
}
