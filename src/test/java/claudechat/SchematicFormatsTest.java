package claudechat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each schematic format read into the shared volume model: kinds of positions, upgrades, metadata-only info, and broken data. */
class SchematicFormatsTest {
	@TempDir
	Path dir;

	@BeforeAll
	static void bootstrap() {
		GameBootstrap.init();
	}

	@Test
	void oldStructureFileIsUpgradedBlocksBlockEntitiesAndEntities() throws IOException {
		Schematics.Schematic s = load("old.nbt", SchematicFixtures.oldStructure());
		BlockVolume v = s.volume();

		assertEquals(Blocks.SPAWNER, v.state(0, 0, 0).getBlock());
		assertEquals(Blocks.CHEST, v.state(1, 0, 0).getBlock());
		assertEquals(BlockVolume.Kind.AIR, v.kind(0, 0, 1));
		assertEquals(BlockVolume.Kind.ABSENT, v.kind(1, 0, 1));

		Map<BlockPos, CompoundTag> data = blockEntityData(v);
		String spawns = data.get(new BlockPos(0, 0, 0)).getCompoundOrEmpty("SpawnData").getCompoundOrEmpty("entity").getStringOr("id", "");
		assertEquals("minecraft:skeleton", spawns);
		CompoundTag item = data.get(new BlockPos(1, 0, 0)).getListOrEmpty("Items").getCompoundOrEmpty(0);
		assertEquals(5, item.getIntOr("count", 0));
		assertEquals(List.of(new BlockVolume.Entity(BlockPos.ZERO, "minecraft:pig")), v.entities());
		assertEquals("tester", s.info().author());
	}

	@Test
	void structureFileWithoutDataVersionIsTreatedAsOld() throws IOException {
		CompoundTag noVersion = SchematicFixtures.oldStructure();
		noVersion.remove("DataVersion");

		assertEquals(Blocks.SPAWNER, load("old.nbt", noVersion).volume().state(0, 0, 0).getBlock());
	}

	@Test
	void litematicaRegionsMergeWithAirAndAbsentKeptApart() throws IOException {
		Schematics.Schematic s = load("two.litematic", SchematicFixtures.litematic());
		BlockVolume v = s.volume();

		assertEquals(new Vec3i(5, 1, 1), v.size());
		assertEquals(Blocks.STONE, v.state(0, 0, 0).getBlock());
		assertEquals(BlockVolume.Kind.AIR, v.kind(1, 0, 0));
		assertEquals(BlockVolume.Kind.ABSENT, v.kind(2, 0, 0));
		assertEquals(Blocks.CHEST, v.state(3, 0, 0).getBlock());
		assertEquals(Blocks.REDSTONE_WIRE, v.state(4, 0, 0).getBlock());
		assertEquals(3, blockEntityData(v).get(new BlockPos(3, 0, 0)).getListOrEmpty("Items").getCompoundOrEmpty(0).getIntOr("count", 0));
		assertEquals(List.of(new Schematics.Region("left", BlockPos.ZERO, new Vec3i(2, 1, 1)),
			new Schematics.Region("right", new BlockPos(3, 0, 0), new Vec3i(2, 1, 1))), s.regions());
		assertEquals("Two Regions", s.info().name());
	}

	@Test
	void truncatedLitematicaDataIsAnError() {
		IOException e = assertThrows(IOException.class, () -> load("short.litematic", SchematicFixtures.truncatedLitematic()));

		assertTrue(e.getMessage().contains("truncated"), e.getMessage());
	}

	@Test
	void spongeV3KeepsStructureVoidAndReadsCurrentStateNames() throws IOException {
		Schematics.Schematic s = load("three.schem", SchematicFixtures.spongeV3());
		BlockVolume v = s.volume();

		assertEquals(BlockVolume.Kind.AIR, v.kind(0, 0, 0));
		assertEquals(BlockVolume.Kind.VOID, v.kind(1, 0, 0));
		BlockState stairs = v.state(2, 0, 0);
		assertEquals(Blocks.OAK_STAIRS, stairs.getBlock());
		assertEquals(Direction.EAST, stairs.getValue(BlockStateProperties.HORIZONTAL_FACING));
		assertEquals(Half.TOP, stairs.getValue(BlockStateProperties.HALF));
		assertEquals(2, blockEntityData(v).get(new BlockPos(3, 0, 0)).getListOrEmpty("Items").getCompoundOrEmpty(0).getIntOr("count", 0));
		assertEquals("minecraft:pig", v.entities().getFirst().type());
		assertEquals("Sponge Three", s.info().name());
	}

	@Test
	void spongeV2BlockEntitiesAreUpgraded() throws IOException {
		BlockVolume v = load("two.schem", SchematicFixtures.spongeV2()).volume();

		assertEquals(Blocks.CHEST, v.state(0, 0, 0).getBlock());
		assertEquals(Blocks.LEVER, v.state(1, 0, 0).getBlock());
		CompoundTag item = blockEntityData(v).get(BlockPos.ZERO).getListOrEmpty("Items").getCompoundOrEmpty(0);
		assertEquals(4, item.getIntOr("count", 0));
	}

	@Test
	void truncatedOrCorruptSpongeDataIsAnError() {
		IOException shortData = assertThrows(IOException.class, () -> load("short.schem", SchematicFixtures.spongeWithData(new byte[] {1, 1})));
		IOException unknownIndex = assertThrows(IOException.class, () -> load("bad.schem", SchematicFixtures.spongeWithData(new byte[] {1, 1, 9, 1})));

		assertTrue(shortData.getMessage().contains("truncated"), shortData.getMessage());
		assertTrue(unknownIndex.getMessage().contains("palette entry 9"), unknownIndex.getMessage());
	}

	@Test
	void infoReadsOnlyTheDescription() throws IOException {
		SchematicFixtures.write(dir.resolve("a.litematic"), SchematicFixtures.litematic());
		SchematicFixtures.write(dir.resolve("b.schem"), SchematicFixtures.spongeV3());
		SchematicFixtures.write(dir.resolve("c.nbt"), SchematicFixtures.oldStructure());

		Schematics.Info litematic = Schematics.info(dir.resolve("a.litematic"));
		Schematics.Info sponge = Schematics.info(dir.resolve("b.schem"));
		Schematics.Info structure = Schematics.info(dir.resolve("c.nbt"));

		assertEquals(new Schematics.Info("Two Regions", "tester", "a test", new Vec3i(5, 1, 1), OptionalLong.of(3)), litematic);
		assertEquals(new Schematics.Info("Sponge Three", "tester", "", new Vec3i(4, 1, 1), OptionalLong.empty()), sponge);
		assertEquals(new Schematics.Info("c", "tester", "", new Vec3i(2, 1, 2), OptionalLong.empty()), structure);
	}

	@Test
	void folderListsUnreadableFilesWithTheReason() throws IOException {
		SchematicFixtures.writeAll(dir);

		List<Schematics.Entry> entries = new Schematics.Folder(dir).entries();

		assertEquals(List.of("Redstone Test.litematic", "farms/Witch Farm.litematic", "farms/Witch Hut.schem", "old/Broken.litematic",
			"old/Old Spawner.nbt", "old/Sponge Two.schem"), entries.stream().map(Schematics.Entry::path).toList());
		assertTrue(entries.get(3) instanceof Schematics.Unreadable);
	}

	@Test
	void countedBlocksLeaveOutAirVoidAndJigsaw() throws IOException {
		BlockVolume v = new BlockVolume.Builder(new Vec3i(4, 1, 1))
			.set(0, 0, 0, Blocks.STONE.defaultBlockState())
			.set(1, 0, 0, Blocks.AIR.defaultBlockState())
			.set(2, 0, 0, Blocks.STRUCTURE_VOID.defaultBlockState())
			.set(3, 0, 0, Blocks.JIGSAW.defaultBlockState())
			.build();

		assertEquals(Map.of(Blocks.STONE, 1), v.countedBlocks());
		assertNull(v.state(5, 0, 0));
	}

	@Test
	void oversizedVolumeIsRefused() {
		assertThrows(IOException.class, () -> new BlockVolume.Builder(new Vec3i(65536, 65536, 1)));
	}

	private Schematics.Schematic load(String name, CompoundTag root) throws IOException {
		Path file = dir.resolve(name);
		SchematicFixtures.write(file, root);
		return Schematics.load(file);
	}

	private static Map<BlockPos, CompoundTag> blockEntityData(BlockVolume v) {
		Map<BlockPos, CompoundTag> data = new HashMap<>();
		v.blockEntities().forEach(be -> data.put(be.pos(), be.data()));
		return data;
	}
}
