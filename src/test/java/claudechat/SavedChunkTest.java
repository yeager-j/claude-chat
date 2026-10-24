package claudechat;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A chunk's saved NBT: its generation status, section palettes in both entry layouts, and the lists it keeps. */
class SavedChunkTest {
	@Test
	void onlyAFullStatusCountsAsGenerated() {
		assertTrue(withStatus("minecraft:full").fullyGenerated());
		assertTrue(withStatus("full").fullyGenerated());
		assertFalse(withStatus("minecraft:structure_starts").fullyGenerated());
		assertFalse(new SavedChunk(new CompoundTag()).fullyGenerated());
	}

	@Test
	void inhabitedTimeIsZeroWhenNeverSaved() {
		CompoundTag tag = new CompoundTag();
		assertEquals(0, new SavedChunk(tag).inhabitedTicks());

		tag.putLong("InhabitedTime", 2400);
		assertEquals(2400, new SavedChunk(tag).inhabitedTicks());
	}

	@Test
	void paletteNamesCoverBareStringsAndBothCompoundLayouts() {
		CompoundTag tag = new CompoundTag();
		tag.put("sections", list(section(0,
			StringTag.valueOf("minecraft:spawner"),
			stateWithId("minecraft:oak_stairs"),
			stateWithOldName("minecraft:chest"))));

		assertEquals(Set.of("minecraft:spawner", "minecraft:oak_stairs", "minecraft:chest"), new SavedChunk(tag).paletteBlockIds());
	}

	@Test
	void sectionsListingNamesTheSectionsWhosePaletteHasAnyWantedBlock() {
		CompoundTag tag = new CompoundTag();
		tag.put("sections", list(
			section(-4, StringTag.valueOf("minecraft:deepslate"), StringTag.valueOf("minecraft:diamond_ore")),
			section(0, StringTag.valueOf("minecraft:stone")),
			section(3, stateWithId("minecraft:diamond_ore"))));

		List<Integer> sections = new SavedChunk(tag).sectionsListing(Set.of("minecraft:diamond_ore", "minecraft:spawner"));

		assertEquals(new TreeSet<>(Set.of(-4, 3)), new TreeSet<>(sections));
	}

	@Test
	void chunkWithoutSectionsListsNoBlocks() {
		SavedChunk chunk = new SavedChunk(new CompoundTag());

		assertEquals(Set.of(), chunk.paletteBlockIds());
		assertEquals(List.of(), chunk.sectionsListing(Set.of("minecraft:stone")));
	}

	@Test
	void blockEntitiesAreKeyedByPosition() {
		CompoundTag chest = new CompoundTag();
		chest.putInt("x", -5);
		chest.putInt("y", 64);
		chest.putInt("z", 20);
		chest.putString("id", "minecraft:chest");
		CompoundTag tag = new CompoundTag();
		tag.put("block_entities", list(chest));

		Map<BlockPos, CompoundTag> blockEntities = new SavedChunk(tag).blockEntities();

		assertEquals(Set.of(new BlockPos(-5, 64, 20)), blockEntities.keySet());
		assertEquals("minecraft:chest", blockEntities.get(new BlockPos(-5, 64, 20)).getStringOr("id", ""));
	}

	@Test
	void structureStartIsFoundByItsId() {
		CompoundTag village = new CompoundTag();
		village.putString("id", "minecraft:village");
		CompoundTag starts = new CompoundTag();
		starts.put("minecraft:village", village);
		CompoundTag structures = new CompoundTag();
		structures.put("starts", starts);
		CompoundTag tag = new CompoundTag();
		tag.put("structures", structures);
		SavedChunk chunk = new SavedChunk(tag);

		assertEquals(village, chunk.structureStart(Identifier.parse("minecraft:village")).orElseThrow());
		assertEquals(Optional.empty(), chunk.structureStart(Identifier.parse("minecraft:mansion")));
		assertEquals(Optional.empty(), new SavedChunk(new CompoundTag()).structureStart(Identifier.parse("minecraft:village")));
	}

	private static SavedChunk withStatus(String status) {
		CompoundTag tag = new CompoundTag();
		tag.putString("Status", status);
		return new SavedChunk(tag);
	}

	private static ListTag list(Tag... elements) {
		ListTag list = new ListTag();
		list.addAll(Arrays.asList(elements));
		return list;
	}

	private static CompoundTag section(int y, Tag... palette) {
		CompoundTag blockStates = new CompoundTag();
		blockStates.put("palette", list(palette));

		CompoundTag section = new CompoundTag();
		section.putByte("Y", (byte) y);
		section.put("block_states", blockStates);
		return section;
	}

	/** A block in a non-default state, as 26.x saves it. */
	private static CompoundTag stateWithId(String id) {
		CompoundTag state = new CompoundTag();
		state.putString("id", id);
		state.put("properties", new CompoundTag());
		return state;
	}

	/** The same, as it was saved before 26.x. */
	private static CompoundTag stateWithOldName(String name) {
		CompoundTag state = new CompoundTag();
		state.putString("Name", name);
		state.put("Properties", new CompoundTag());
		return state;
	}
}
