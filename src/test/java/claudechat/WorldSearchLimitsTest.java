package claudechat;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The work limits of the world searches and look, against inputs that would otherwise run them without end. */
class WorldSearchLimitsTest {
	private static final Origin ORIGIN = Origin.at(null, 0.5, 64, 0.5);
	private static final long NO_DEADLINE = Long.MAX_VALUE;

	// --- find_biomes ---

	private static BiomeFinder.Request biomes(int maxDistance, int minSpacing, int limit, Visit.Filter visited) {
		return new BiomeFinder.Request(Set.of(), maxDistance, minSpacing, limit, visited);
	}

	/** Every column is the biome, never visited. */
	private static BiomeFinder.Column everywhere(AtomicInteger calls) {
		return (x, z, distance) -> {
			calls.incrementAndGet();
			return new BiomeFinder.Found("minecraft:plains", x, 64, z, distance, Visit.NOT_GENERATED);
		};
	}

	@Test
	void rejectedPatchesStopAtTheirCapInLinearTime() {
		AtomicInteger calls = new AtomicInteger();
		// Every grid point is its own patch at the smallest spacing, and the filter rejects all of them: before, each
		// column scanned every patch so far.
		BiomeFinder.Result result = assertTimeoutPreemptively(Duration.ofSeconds(20),
			() -> BiomeFinder.search(ORIGIN, biomes(30_000, 32, 5, Visit.Filter.VISITED), NO_DEADLINE, everywhere(calls)));

		assertTrue(result.truncated());
		assertTrue(result.found().isEmpty());
		assertEquals(BiomeFinder.MAX_PATCHES, calls.get());
	}

	@Test
	void patchesAreAtLeastTheSpacingApartNearestFirst() throws InterruptedException {
		BiomeFinder.Result result = BiomeFinder.search(ORIGIN, biomes(6400, 256, 8, Visit.Filter.ANY), NO_DEADLINE,
			everywhere(new AtomicInteger()));

		List<BiomeFinder.Found> found = result.found();
		assertFalse(result.truncated());
		assertEquals(8, found.size());
		assertEquals(0, found.getFirst().distance(), 1);
		for (int i = 0; i < found.size(); i++) {
			if (i > 0) {
				assertTrue(found.get(i).distance() >= found.get(i - 1).distance());
			}
			for (int j = 0; j < i; j++) {
				double dx = found.get(i).x() - found.get(j).x();
				double dz = found.get(i).z() - found.get(j).z();
				assertTrue(Math.hypot(dx, dz) >= 256, found.get(i) + " is near " + found.get(j));
			}
		}
	}

	@Test
	void patchGridFindsNeighborsAcrossCells() {
		BiomeFinder.Patches patches = new BiomeFinder.Patches(100);
		patches.add(-1, -1);

		assertTrue(patches.near(-1, -1));
		assertTrue(patches.near(50, 50));
		assertTrue(patches.near(-100, 0));
		assertFalse(patches.near(99, 0));
		assertFalse(patches.near(70, 70));
		assertEquals(1, patches.size());
	}

	@Test
	void aPassedDeadlineStopsTheSearch() throws InterruptedException {
		AtomicInteger calls = new AtomicInteger();
		BiomeFinder.Result result = BiomeFinder.search(ORIGIN, biomes(30_000, 32, 5, Visit.Filter.VISITED), System.nanoTime() - 1,
			everywhere(calls));

		assertTrue(result.truncated());
		assertEquals(0, calls.get());
	}

	@Test
	void anInterruptStopsTheSearch() {
		AtomicInteger calls = new AtomicInteger();
		Thread.currentThread().interrupt();

		assertThrows(InterruptedException.class,
			() -> BiomeFinder.search(ORIGIN, biomes(30_000, 32, 5, Visit.Filter.VISITED), NO_DEADLINE, everywhere(calls)));
		assertFalse(Thread.currentThread().isInterrupted());
		assertEquals(0, calls.get());
	}

	@Test
	void aColumnOutOfBudgetEndsTheSearchTruncated() throws InterruptedException {
		AtomicInteger calls = new AtomicInteger();
		BiomeFinder.Result result = BiomeFinder.search(ORIGIN, biomes(30_000, 32, 5, Visit.Filter.ANY), NO_DEADLINE, (x, z, d) -> {
			if (calls.incrementAndGet() > 100) {
				throw new BiomeFinder.Budget();
			}
			return null;
		});

		assertTrue(result.truncated());
		assertEquals(101, calls.get());
	}

	// --- saved structure starts ---

	private static final ChunkPos START = new ChunkPos(10, -4);

	private static String problem(List<BoundingBox> boxes) {
		return StructureFinder.savedGeometryProblem(boxes, START, -64, 319);
	}

	@Test
	void realStructurePiecesPass() {
		int x = START.getMinBlockX();
		int z = START.getMinBlockZ();
		// A jigsaw structure's widest reach, 128 blocks each way, and a deep piece.
		assertNull(problem(List.of(new BoundingBox(x - 128, -60, z - 128, x + 143, 120, z + 143),
			new BoundingBox(x, -64, z, x + 15, 319, z + 15))));
	}

	@Test
	void aPieceFarFromItsStartIsRefused() {
		int x = START.getMinBlockX();
		int z = START.getMinBlockZ();

		assertNotNull(problem(List.of(new BoundingBox(x, 0, z, x + StructureFinder.MAX_SAVED_REACH + 16, 10, z + 10))));
		assertNotNull(problem(List.of(new BoundingBox(x, 0, z - StructureFinder.MAX_SAVED_REACH - 1, x + 10, 10, z))));
		assertNotNull(problem(List.of(new BoundingBox(x, -64 - StructureFinder.MAX_SAVED_REACH - 1, z, x + 10, 10, z + 10))));
		assertNotNull(problem(List.of(new BoundingBox(x, 0, z, x + 10, 319 + StructureFinder.MAX_SAVED_REACH + 1, z + 10))));
	}

	@Test
	void extremeCoordinatesAreRefusedWithoutOverflow() {
		assertNotNull(problem(List.of(new BoundingBox(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
			Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE))));
		assertNotNull(problem(List.of(new BoundingBox(Integer.MAX_VALUE - 1, 0, Integer.MAX_VALUE - 1, Integer.MAX_VALUE, 1, Integer.MAX_VALUE))));
	}

	@Test
	void tooManyPiecesAreRefused() {
		int x = START.getMinBlockX();
		int z = START.getMinBlockZ();
		BoundingBox small = new BoundingBox(x, 0, z, x + 1, 1, z + 1);

		assertNull(problem(Collections.nCopies(StructureFinder.MAX_SAVED_PIECES, small)));
		assertNotNull(problem(Collections.nCopies(StructureFinder.MAX_SAVED_PIECES + 1, small)));
	}

	@Test
	void piecesCoveringTooManyChunksTogetherAreRefused() {
		int x = START.getMinBlockX();
		int z = START.getMinBlockZ();
		int reach = StructureFinder.MAX_SAVED_REACH;
		// Each piece alone is within reach (about 65 x 65 chunks); together they cover more than the budget.
		BoundingBox wide = new BoundingBox(x - reach, 0, z - reach, x + 15 + reach, 1, z + 15 + reach);
		long perPiece = (long) (Math.floorDiv(wide.maxX(), 16) - Math.floorDiv(wide.minX(), 16) + 1) * (Math.floorDiv(wide.maxZ(), 16) - Math.floorDiv(wide.minZ(), 16) + 1);
		int pieces = (int) (StructureFinder.MAX_SAVED_CHUNK_COVER / perPiece) + 1;

		assertNull(problem(List.of(wide)));
		assertNotNull(problem(Collections.nCopies(pieces, wide)));
	}

	// --- look's entity pass ---

	@Test
	void closestKeepsOnlyTheNearestFew() {
		List<Integer> distances = new ArrayList<>(IntStream.range(0, 100_000).boxed().toList());
		Collections.shuffle(distances, new Random(1));
		AtomicInteger checked = new AtomicInteger();

		NearestFirst.Closest<Integer> closest = NearestFirst.closest(distances, d -> {
			checked.incrementAndGet();
			return d % 2 == 0;
		}, Integer::compare, 256, () -> false);

		assertEquals(IntStream.range(0, 256).map(i -> i * 2).boxed().toList(), closest.items());
		assertEquals(50_000 - 256, closest.more());
		assertEquals(100_000, checked.get());
	}

	@Test
	void closestStopsWhenAsked() {
		List<Integer> many = Collections.nCopies(100_000, 1);
		AtomicInteger checked = new AtomicInteger();

		assertThrows(CancellationException.class, () -> NearestFirst.closest(many, d -> checked.incrementAndGet() > 0,
			Integer::compare, 10, () -> checked.get() >= 1000));
		assertTrue(checked.get() < 1300, checked + " checked after the stop");
	}
}
