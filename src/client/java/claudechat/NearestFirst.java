package claudechat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * The shared parts of the nearest-first searches (structures, biomes, blocks): walking square rings of grid cells
 * outward, and keeping the k nearest hits with the rule for when the rest of the search can't beat them.
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
}
