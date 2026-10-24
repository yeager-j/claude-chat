package claudechat;

import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Template summaries (all palettes, one counting rule) and search ranking, without a server. */
class StructureTemplatesTest {
	private static final StructureTemplates.Origin VANILLA = new StructureTemplates.Pack("vanilla");

	@BeforeAll
	static void bootstrap() {
		GameBootstrap.init();
	}

	@Test
	void summaryCountsEachBlockAtItsMostInAnyPalette() throws IOException {
		// Two positions; palette 0 places stone and a jigsaw, palette 1 mossy stone and a jigsaw.
		CompoundTag tag = new CompoundTag();
		tag.put("size", ints(2, 1, 1));
		ListTag palettes = new ListTag();
		palettes.add(palette("minecraft:stone", "minecraft:jigsaw"));
		palettes.add(palette("minecraft:mossy_cobblestone", "minecraft:jigsaw"));
		tag.put("palettes", palettes);
		CompoundTag jigsaw = new CompoundTag();
		jigsaw.putString("pool", "minecraft:village/plains/streets");
		ListTag blocks = new ListTag();
		blocks.add(entry(0, 0));
		CompoundTag jigsawEntry = entry(1, 1);
		jigsawEntry.put("nbt", jigsaw);
		blocks.add(jigsawEntry);
		tag.put("blocks", blocks);

		StructureTemplates.Summary summary = StructureTemplates.summarize(Identifier.parse("test:piece"), VANILLA, StructureNbt.read(tag));

		assertEquals(Map.of(Blocks.STONE, 1, Blocks.MOSSY_COBBLESTONE, 1), summary.blockCounts());
		assertEquals(1, summary.countedBlocks());
		assertEquals(Set.of(Identifier.parse("minecraft:village/plains/streets")), summary.jigsawPools());
	}

	@Test
	void manyPalettesOverALargeBoxAreSummarizedInOnePass() throws IOException {
		// The most palettes over a 2^22-position box: a volume per palette would fill 64 boxes.
		CompoundTag tag = new CompoundTag();
		tag.put("size", ints(256, 64, 256));
		ListTag palettes = new ListTag();
		for (int i = 0; i < StructureNbt.MAX_PALETTES; i++) {
			palettes.add(i == 5 ? palette("minecraft:stone", "minecraft:gold_block") : palette("minecraft:stone", "minecraft:air"));
		}
		tag.put("palettes", palettes);
		ListTag blocks = new ListTag();
		blocks.add(entry(0, 0));
		blocks.add(entry(1, 1));
		blocks.add(entry(2, 1));
		blocks.add(entry(2, 0));
		tag.put("blocks", blocks);

		StructureTemplates.Summary summary = StructureTemplates.summarize(Identifier.parse("test:wide"), VANILLA, StructureNbt.read(tag));

		// (2 0 0) is listed twice; the last entry wins, as when the game places it.
		assertEquals(Map.of(Blocks.STONE, 2, Blocks.GOLD_BLOCK, 1), summary.blockCounts());
		assertEquals(3, summary.countedBlocks());
		assertEquals(new Vec3i(256, 64, 256), summary.size());
	}

	@Test
	void anEntryMissingFromAnyPaletteIsAnError() {
		CompoundTag tag = new CompoundTag();
		tag.put("size", ints(1, 1, 1));
		ListTag palettes = new ListTag();
		palettes.add(palette("minecraft:stone", "minecraft:dirt"));
		palettes.add(palette("minecraft:stone"));
		tag.put("palettes", palettes);
		ListTag blocks = new ListTag();
		blocks.add(entry(0, 1));
		tag.put("blocks", blocks);

		IOException e = assertThrows(IOException.class, () -> StructureTemplates.summarize(Identifier.parse("test:short"), VANILLA, StructureNbt.read(tag)));
		assertTrue(e.getMessage().contains("has state 1"), e.getMessage());
	}

	@Test
	void summarizingStopsWhenCancelled() {
		CompoundTag tag = new CompoundTag();
		tag.put("size", ints(1, 1, 1));
		tag.put("palette", palette("minecraft:stone"));
		ListTag blocks = new ListTag();
		blocks.add(entry(0, 0));
		tag.put("blocks", blocks);

		assertThrows(CancellationException.class,
			() -> StructureTemplates.summarize(Identifier.parse("test:cancelled"), VANILLA, StructureNbt.read(tag), () -> true));
	}

	@Test
	void moreBlockEntriesThanPositionsAreRefusedBeforeUpgrading() {
		// A 2×1×1 box listing the same block three times.
		CompoundTag tag = new CompoundTag();
		tag.put("size", ints(2, 1, 1));
		tag.put("palette", palette("minecraft:stone"));
		ListTag blocks = new ListTag();
		for (int i = 0; i < 3; i++) {
			blocks.add(entry(0, 0));
		}
		tag.put("blocks", blocks);

		TooLargeException e = assertThrows(TooLargeException.class, () -> StructureNbt.upgrade(tag, SchematicFixtures.currentVersion()));
		assertTrue(e.getMessage().contains("3 block entries, more than the 2 positions"), e.getMessage());
	}

	@Test
	void aBuildStopsOnceItsWorkOrTimeRunsOut() {
		StructureTemplates.BuildBudget work = new StructureTemplates.BuildBudget(100, null);
		work.charge(100);
		assertTrue(work.exhausted().isEmpty());
		work.charge(1);
		assertTrue(work.exhausted().orElseThrow().contains("work limit"));

		StructureTemplates.BuildBudget time = new StructureTemplates.BuildBudget(100, Duration.ofSeconds(-1));
		assertTrue(time.exhausted().orElseThrow().contains("time limit"));
	}

	@Test
	void searchRanksMostTermsThenRealBuildingsThenScore() {
		StructureTemplates.Summary tinySpawner = summary("test:spawner_only", Map.of(Blocks.SPAWNER, 1));
		StructureTemplates.Summary tower = summary("test:tower", Map.of(Blocks.MOSSY_STONE_BRICKS, 40, Blocks.SPAWNER, 1, Blocks.STONE, 10));
		StructureTemplates.Summary house = summary("test:house", Map.of(Blocks.OAK_PLANKS, 50, Blocks.MOSSY_STONE_BRICKS, 2));
		StructureTemplates.Summary barn = summary("test:barn", Map.of(Blocks.HAY_BLOCK, 50));
		StructureTemplates.Index index = index(tinySpawner, tower, house, barn);
		Map<String, Set<Block>> wanted = new LinkedHashMap<>();
		wanted.put("mossy", Set.of(Blocks.MOSSY_STONE_BRICKS));
		wanted.put("spawner", Set.of(Blocks.SPAWNER));

		StructureTemplates.SearchResult result = StructureTemplates.search(index, new StructureTemplates.Query(wanted, "", Integer.MAX_VALUE));

		assertEquals(List.of("test:tower", "test:house", "test:spawner_only"), result.hits().stream().map(h -> h.template().id().toString()).toList());
		assertEquals(false, result.noneHasAll());
	}

	@Test
	void searchFiltersByTextAndSize() {
		StructureTemplates.Summary big = new StructureTemplates.Summary(Identifier.parse("mod:castle"), VANILLA, new Vec3i(40, 20, 40),
			Map.of(Blocks.STONE, 100), 100, Set.of(), Map.of());
		StructureTemplates.Summary small = summary("mod:hut", Map.of(Blocks.STONE, 10));
		StructureTemplates.Index index = index(big, small);

		StructureTemplates.SearchResult bySize = StructureTemplates.search(index, new StructureTemplates.Query(Map.of(), "MOD", 16));

		assertEquals(List.of("mod:hut"), bySize.hits().stream().map(h -> h.template().id().toString()).toList());
		assertTrue(StructureTemplates.search(index, new StructureTemplates.Query(Map.of(), "village", 100)).hits().isEmpty());
	}

	private static StructureTemplates.Index index(StructureTemplates.Summary... summaries) {
		Map<Identifier, StructureTemplates.Summary> byId = new HashMap<>();
		for (StructureTemplates.Summary summary : summaries) {
			byId.put(summary.id(), summary);
		}

		return new StructureTemplates.Index(byId, Set.of(), StructureTemplates.Usage.NONE);
	}

	private static StructureTemplates.Summary summary(String id, Map<Block, Integer> counts) {
		int total = counts.values().stream().mapToInt(Integer::intValue).sum();
		return new StructureTemplates.Summary(Identifier.parse(id), VANILLA, new Vec3i(5, 5, 5), counts, total, Set.of(), Map.of());
	}

	private static ListTag palette(String... ids) {
		ListTag list = new ListTag();
		for (String id : ids) {
			list.add(SchematicFixtures.state(id));
		}

		return list;
	}

	private static CompoundTag entry(int x, int state) {
		CompoundTag entry = new CompoundTag();
		entry.put("pos", ints(x, 0, 0));
		entry.putInt("state", state);
		return entry;
	}

	private static ListTag ints(int... values) {
		ListTag list = new ListTag();
		for (int value : values) {
			list.add(IntTag.valueOf(value));
		}

		return list;
	}
}
