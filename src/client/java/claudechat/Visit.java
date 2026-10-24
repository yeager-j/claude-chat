package claudechat;

import java.util.Locale;

/**
 * How long players have spent near a chunk or a structure: never generated, or generated with its InhabitedTime in
 * ticks. Minecraft only counts a chunk's time while a player is within 128 blocks of it.
 */
public record Visit(boolean generated, long ticks) {
	/** How long players must have been within 128 blocks for a place to count as visited: one second. */
	public static final long VISITED_TICKS = 20;
	public static final Visit NOT_GENERATED = new Visit(false, 0);

	public Visit {
		if (ticks < 0 || (!generated && ticks != 0)) {
			throw new IllegalArgumentException("not a visit: generated=" + generated + ", ticks=" + ticks);
		}
	}

	/** A generated chunk with this InhabitedTime. */
	public static Visit of(long ticks) {
		return new Visit(true, ticks);
	}

	public boolean visited() {
		return ticks >= VISITED_TICKS;
	}

	public long seconds() {
		return ticks / 20;
	}

	/** The longer of two visits: a structure's time is the most of any chunk its pieces cover. */
	public Visit max(Visit other) {
		return new Visit(generated || other.generated, Math.max(ticks, other.ticks));
	}

	/** The visit of each chunk of one dimension. */
	@FunctionalInterface
	public interface ByChunk {
		Visit at(int chunkX, int chunkZ);
	}

	/** The {@code visited} argument of the search tools. */
	public enum Filter {
		ANY, VISITED, UNVISITED;

		/** "any", "visited" or "unvisited"; blank is any. */
		public static Filter parse(String argument) {
			String value = argument.trim().toLowerCase(Locale.ROOT);
			return switch (value) {
				case "", "any" -> ANY;
				case "visited" -> VISITED;
				case "unvisited" -> UNVISITED;
				default -> throw new IllegalArgumentException("visited must be any, visited or unvisited, not \"" + argument + "\".");
			};
		}

		public boolean accepts(Visit visit) {
			return switch (this) {
				case ANY -> true;
				case VISITED -> visit.visited();
				case UNVISITED -> !visit.visited();
			};
		}
	}
}
