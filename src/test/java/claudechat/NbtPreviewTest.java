package claudechat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Block entity previews stop at their length while walking the tag, whatever its size. */
class NbtPreviewTest {
	@BeforeAll
	static void bootstrap() {
		GameBootstrap.init();
	}

	@Test
	void smallTagIsWrittenWhole() {
		CompoundTag tag = new CompoundTag();
		tag.putString("CustomName", "Loot");
		tag.putInt("Count", 3);
		tag.putIntArray("Pos", new int[] {1, 2});

		assertEquals("{Count:3,CustomName:\"Loot\",Pos:[I;1,2]}", NbtPreview.of(tag, Set.of(), 400));
	}

	@Test
	void hugeArrayStopsAtTheLimit() {
		CompoundTag tag = new CompoundTag();
		tag.putIntArray("big", new int[10_000_000]);

		String preview = NbtPreview.of(tag, Set.of(), 400);

		assertEquals(401, preview.length());
		assertTrue(preview.startsWith("{big:[I;0,0,") && preview.endsWith("…"), preview);
	}

	@Test
	void omittedKeysAreLeftOutWithoutCopying() {
		CompoundTag tag = new CompoundTag();
		tag.putString("id", "minecraft:chest");
		tag.putInt("x", 4);
		tag.putString("LootTable", "minecraft:chests/simple_dungeon");

		assertEquals("{LootTable:\"minecraft:chests/simple_dungeon\"}", NbtPreview.of(tag, Set.of("id", "x"), 400));
		assertEquals("{}", NbtPreview.of(tag, Set.of("id", "x", "LootTable"), 400));
		assertTrue(tag.contains("id"), "the tag itself is unchanged");
	}

	@Test
	void deepNestingIsCutShort() {
		CompoundTag root = new CompoundTag();
		ListTag current = new ListTag();
		root.put("l", current);
		for (int i = 0; i < 200; i++) {
			ListTag child = new ListTag();
			current.add(child);
			current = child;
		}

		String preview = NbtPreview.of(root, Set.of(), 10_000);

		assertTrue(preview.contains("[…]"), preview);
	}

	@Test
	void searchReadsOnlyTheStart() {
		CompoundTag tag = new CompoundTag();
		tag.putIntArray("a", new int[1_000_000]);
		tag.putString("z", "Skeleton");

		assertTrue(NbtPreview.contains(tag, "skeleton", 10_000_000));
		assertFalse(NbtPreview.contains(tag, "skeleton", 1000));
	}
}
