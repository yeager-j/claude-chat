package claudechat;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The values the world tools parse their arguments into, and the rules attached to them. */
class WorldValuesTest {
	private static final ResourceKey<Level> OVERWORLD = dimension("minecraft:overworld");
	private static final ResourceKey<Level> NETHER = dimension("minecraft:the_nether");
	private static final ResourceKey<Level> END = dimension("minecraft:the_end");
	private static final ResourceKey<Level> AETHER = dimension("aether:the_aether");
	private static final List<ResourceKey<Level>> ALL = List.of(OVERWORLD, NETHER, END, AETHER);

	private static ResourceKey<Level> dimension(String id) {
		return ResourceKey.create(Registries.DIMENSION, Identifier.parse(id));
	}

	// --- Visit ---

	@Test
	void visitedNeedsASecond() {
		assertFalse(Visit.NOT_GENERATED.visited());
		assertFalse(Visit.of(Visit.VISITED_TICKS - 1).visited());
		assertTrue(Visit.of(Visit.VISITED_TICKS).visited());
		assertTrue(Visit.of(0).generated());
	}

	@Test
	void notGeneratedHasNoTime() {
		assertThrows(IllegalArgumentException.class, () -> new Visit(false, 5));
		assertThrows(IllegalArgumentException.class, () -> new Visit(true, -1));
	}

	@Test
	void structureVisitIsTheLongestOfItsChunks() {
		assertEquals(Visit.of(40), Visit.NOT_GENERATED.max(Visit.of(40)));
		assertEquals(Visit.of(40), Visit.of(40).max(Visit.of(3)));
		assertEquals(Visit.NOT_GENERATED, Visit.NOT_GENERATED.max(Visit.NOT_GENERATED));
	}

	@Test
	void visitedFilterParsesOnceAndRejectsUnknownValues() {
		assertSame(Visit.Filter.ANY, Visit.Filter.parse(""));
		assertSame(Visit.Filter.UNVISITED, Visit.Filter.parse(" Unvisited "));
		assertThrows(IllegalArgumentException.class, () -> Visit.Filter.parse("explored"));

		assertTrue(Visit.Filter.UNVISITED.accepts(Visit.NOT_GENERATED));
		assertFalse(Visit.Filter.UNVISITED.accepts(Visit.of(100)));
		assertTrue(Visit.Filter.VISITED.accepts(Visit.of(100)));
		assertTrue(Visit.Filter.ANY.accepts(Visit.of(1)));
	}

	// --- Dimensions ---

	@Test
	void dimensionNamesAreShortForMinecraft() {
		assertEquals("the_nether", Dimensions.name(NETHER));
		assertEquals("aether:the_aether", Dimensions.name(AETHER));
	}

	@Test
	void dimensionArgumentsAcceptShortAndFullIds() {
		assertEquals(Optional.empty(), Dimensions.parse(" ", ALL));
		assertEquals(Optional.of(NETHER), Dimensions.parse("the_nether", ALL));
		assertEquals(Optional.of(NETHER), Dimensions.parse("minecraft:the_nether", ALL));
		assertEquals(Optional.of(AETHER), Dimensions.parse("aether:the_aether", ALL));

		IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> Dimensions.parse("nether", ALL));
		assertTrue(unknown.getMessage().contains("the_nether"), unknown.getMessage());
	}

	@Test
	void searchesCoverOtherDimensionsOnlyWithoutALimitOrByName() {
		assertEquals(ALL, Dimensions.searchOrder(OVERWORLD, Optional.empty(), false, ALL));
		assertEquals(List.of(NETHER, OVERWORLD, END, AETHER), Dimensions.searchOrder(NETHER, Optional.empty(), false, ALL));
		assertEquals(List.of(OVERWORLD), Dimensions.searchOrder(OVERWORLD, Optional.empty(), true, ALL));
		assertEquals(List.of(NETHER), Dimensions.searchOrder(OVERWORLD, Optional.of(NETHER), true, ALL));
	}

	@Test
	void generatedThingsAreSearchedWhereTheyExist() {
		assertEquals(OVERWORLD, Dimensions.whereItExists(OVERWORLD, Optional.empty(), List.of(OVERWORLD, NETHER), "x"));
		assertEquals(NETHER, Dimensions.whereItExists(OVERWORLD, Optional.empty(), List.of(NETHER, END), "x"));
		assertEquals(END, Dimensions.whereItExists(OVERWORLD, Optional.of(END), List.of(NETHER), "x"));
		assertThrows(IllegalArgumentException.class, () -> Dimensions.whereItExists(OVERWORLD, Optional.empty(), List.of(), "x"));
	}

	// --- Origin ---

	@Test
	void originTranslatesToOtherDimensions() {
		Origin home = Origin.at(OVERWORLD, 800, 64, -160);
		assertSame(home, home.translatedTo(OVERWORLD, 0.125));

		Origin nether = home.translatedTo(NETHER, 0.125);
		assertEquals(new Origin(NETHER, 100, 64, -20, true), nether);
		assertEquals(5, nether.horizontalDistanceTo(103, -16), 1e-9);
		assertEquals(13, Origin.at(OVERWORLD, 0, 0, 0).distanceTo(3, 4, 12), 1e-9);
	}

	// --- NearestFirst ---

	@Test
	void ringsAreSquareBorders() {
		int[] count = {0};
		NearestFirst.forEachInRing(0, (dx, dz) -> {
			assertEquals(0, dx);
			assertEquals(0, dz);
			count[0]++;
		});
		assertEquals(1, count[0]);

		count[0] = 0;
		NearestFirst.forEachInRing(2, (dx, dz) -> {
			assertEquals(2, Math.max(Math.abs(dx), Math.abs(dz)));
			count[0]++;
		});
		assertEquals(16, count[0]);
	}

	@Test
	void topKKeepsTheNearestAndKnowsWhenItIsSettled() {
		NearestFirst.TopK<String> nearest = new NearestFirst.TopK<>(2, 100);
		nearest.offer("far", 50);
		nearest.offer("too far", 150);
		assertFalse(nearest.settled(1000));
		nearest.offer("near", 10);
		nearest.offer("middle", 20);

		assertEquals(List.of("near", "middle"), nearest.sorted());
		assertFalse(nearest.couldAccept(30));
		assertTrue(nearest.couldAccept(15));
		assertFalse(nearest.settled(19));
		assertTrue(nearest.settled(20));
	}

	// --- Visits file and InhabitedTime ---

	@Test
	void visitsFileReadsOldAndNewDimensionNames() {
		Map<WorldIndex.ChunkKey, Long> times = VisitsFile.parse(List.of(
			"# comment",
			"overworld 1 2 30",
			"minecraft:overworld 1 2 50",
			"minecraft:the_nether -3 4 7",
			"overworld x 2 30",
			"short line"));
		assertEquals(Map.of(new WorldIndex.ChunkKey(OVERWORLD, 1, 2), 50L, new WorldIndex.ChunkKey(NETHER, -3, 4), 7L), times);
		assertEquals(times, VisitsFile.parse(List.of(VisitsFile.format(times).split("\n"))));
	}

	@Test
	void recordedTimeCountsOnlyForChunksStillThere() {
		WorldIndex.ChunkKey saved = new WorldIndex.ChunkKey(OVERWORLD, 0, 0);
		WorldIndex.ChunkKey trimmed = new WorldIndex.ChunkKey(OVERWORLD, 5, 5);
		WorldIndex.ChunkKey live = new WorldIndex.ChunkKey(NETHER, 1, 1);

		Map<WorldIndex.ChunkKey, Long> times = WorldIndex.inhabitedTimes(
			Map.of(saved, 10L),
			Map.of(saved, 400L, trimmed, 900L, live, 30L),
			Map.of(live, 20L));
		assertEquals(Map.of(saved, 400L, live, 30L), times);
	}
}
