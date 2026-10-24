package claudechat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * The shared parts of the nearest-first searches (structures, biomes, blocks): walking square rings of grid cells
 * outward, and keeping the k nearest hits with the rule for when the rest of the search can't beat them. Also picks
 * the nearest few of a collection before expensive work on each ({@link #closest}).
 */
final class NearestFirst {
	private NearestFirst() {
	}

	@FunctionalInterface
	interface Cell {
		void visit(int dx, int dz);
	}

	/** Every cell at Chebyshev distance {@code ring} from the center: ring 0 is the center, ring r the border of a (2r+1)² square. */
	static void forEachInRing(int ring, Cell cell) {
		for (int dx = -ring; dx <= ring; dx++) {
			for (int dz = -ring; dz <= ring; dz++) {
				if (Math.abs(dx) == ring || Math.abs(dz) == ring) {
					cell.visit(dx, dz);
				}
			}
		}
	}

	/** The {@code limit} nearest items offered so far, within {@code maxDistance}. */
	static final class TopK<T> {
		private record Entry<T>(T item, double distance) {
		}

		private final PriorityQueue<Entry<T>> farthestFirst = new PriorityQueue<>(Comparator.comparingDouble((Entry<T> e) -> e.distance()).reversed());
		private final int limit;
		private final double maxDistance;

		TopK(int limit, double maxDistance) {
			this.limit = limit;
			this.maxDistance = maxDistance;
		}

		/** Whether an item this far could still make the list. Check it before expensive work on a candidate. */
		boolean couldAccept(double distance) {
			return distance <= maxDistance && (farthestFirst.size() < limit || distance < farthestFirst.peek().distance());
		}

		void offer(T item, double distance) {
			if (!couldAccept(distance)) {
				return;
			}
			farthestFirst.add(new Entry<>(item, distance));
			if (farthestFirst.size() > limit) {
				farthestFirst.poll();
			}
		}

		/**
		 * Whether the list is final given that everything not yet searched is at least {@code nearestRemaining} away:
		 * it's full and nothing in it is farther than that.
		 */
		boolean settled(double nearestRemaining) {
			return farthestFirst.size() >= limit && farthestFirst.peek().distance() <= nearestRemaining;
		}

		/** Nearest first. */
		List<T> sorted() {
			List<Entry<T>> entries = new ArrayList<>(farthestFirst);
			entries.sort(Comparator.comparingDouble(Entry::distance));
			List<T> items = new ArrayList<>();
			entries.forEach(entry -> items.add(entry.item()));
			return items;
		}
	}

	/** The {@code limit} first items in {@code order}, and how many more passed the filter. */
	record Closest<T>(List<T> items, int more) {
	}

	/**
	 * The {@code limit} first of the items that pass {@code cheap}, in {@code order}, without keeping or sorting the
	 * rest: the cap on the expensive work that follows, for a collection someone else decides the size of. Checks
	 * {@code stopped} as it goes and throws {@link CancellationException} once it's true.
	 */
	static <T> Closest<T> closest(Iterable<T> all, Predicate<T> cheap, Comparator<T> order, int limit, BooleanSupplier stopped) {
		PriorityQueue<T> lastFirst = new PriorityQueue<>(order.reversed());
		int seen = 0;
		int passed = 0;
		for (T item : all) {
			if ((seen++ & 255) == 0 && stopped.getAsBoolean()) {
				throw new CancellationException("Stopped.");
			}
			if (!cheap.test(item)) {
				continue;
			}
			passed++;
			lastFirst.add(item);
			if (lastFirst.size() > limit) {
				lastFirst.poll();
			}
		}
		List<T> items = new ArrayList<>(lastFirst);
		items.sort(order);
		return new Closest<>(items, passed - items.size());
	}
}
