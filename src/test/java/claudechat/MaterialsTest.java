package claudechat;

import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MaterialsTest {
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
		Map<String, Integer> noItem = new TreeMap<>();

		Map<String, Integer> placed = Materials.asPlaced(volume, noItem);

		assertEquals(Map.of("minecraft:stone_slab", 2, "minecraft:oak_door", 1), placed);
		assertEquals(Map.of("minecraft:fire", 1), noItem);
	}

	@Test
	void comparisonSplitsStockIntoUsefulAndNotNeeded() {
		Materials.Recipe piston = new Materials.Recipe("minecraft/recipe/piston.json", 1,
			Map.of("#minecraft:planks", 3, "minecraft:cobblestone", 4, "minecraft:iron_ingot", 1, "minecraft:redstone", 1));
		Map<String, Integer> placed = Map.of("minecraft:piston", 2, "minecraft:redstone", 5);
		Map<String, Integer> stock = Map.of("minecraft:redstone", 4, "minecraft:iron_ingot", 2, "minecraft:dirt", 64);
		Materials.Breakdown full = new Materials.Breakdown(Map.of(), List.of(new Materials.Craft("minecraft:piston", 2, 2, 2, piston)));
		Materials.Breakdown withStock = new Materials.Breakdown(Map.of("minecraft:redstone", 3), List.of());

		Materials.Comparison comparison = Materials.compare(placed, stock, full, withStock);

		assertEquals(Map.of("minecraft:piston", 2, "minecraft:redstone", 1), comparison.missingAsPlaced());
		assertEquals(Map.of("minecraft:redstone", 4, "minecraft:iron_ingot", 2), comparison.useful());
		assertEquals(Map.of("minecraft:dirt", 64), comparison.notNeeded());
		assertFalse(comparison.complete());
	}
}
