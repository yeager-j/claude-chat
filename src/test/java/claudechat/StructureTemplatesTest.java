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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
