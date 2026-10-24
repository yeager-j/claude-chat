package claudechat;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@value #NAME} in the world folder: the InhabitedTime of chunks that is newer than their save (see {@link WorldIndex}).
 * One line per chunk: dimension id, chunk x, chunk z, ticks. Files from before dimension ids were written in full
 * ("overworld" for "minecraft:overworld") read the same.
 */
final class VisitsFile {
	static final String NAME = "claudechat-visits.txt";
	private static final String HEADER = "# Claude Chat: time players spent near chunks, which Minecraft doesn't always save.\n"
		+ "# dimension chunkX chunkZ ticks\n";

	private VisitsFile() {
	}

	/** The recorded times; empty if there's no file yet. */
	static Map<WorldIndex.ChunkKey, Long> read(Path worldRoot) throws IOException {
		Path file = worldRoot.resolve(NAME);
		if (!Files.isRegularFile(file)) {
			return Map.of();
		}
		try (Stream<String> lines = Files.lines(file)) {
			return parse(lines.toList());
		}
	}

	/** Replaces the file in one step, so a crash mid-write leaves the old one. */
	static void write(Path worldRoot, Map<WorldIndex.ChunkKey, Long> times) throws IOException {
		Path file = worldRoot.resolve(NAME);
		Path temp = file.resolveSibling(NAME + ".tmp");
		Files.writeString(temp, format(times));
		Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/** Lines that don't parse (hand-edited, say) are skipped; a chunk listed twice keeps its longer time. */
	static Map<WorldIndex.ChunkKey, Long> parse(List<String> lines) {
		Map<WorldIndex.ChunkKey, Long> times = new HashMap<>();
		for (String line : lines) {
			String[] parts = line.trim().split(" ");
			if (line.startsWith("#") || parts.length != 4) {
				continue;
			}

			Identifier dimension = Identifier.tryParse(parts[0]);
			if (dimension == null) {
				continue;
			}
			try {
				WorldIndex.ChunkKey key = new WorldIndex.ChunkKey(ResourceKey.create(Registries.DIMENSION, dimension),
					Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
				times.merge(key, Long.parseLong(parts[3]), Math::max);
			} catch (NumberFormatException e) {
				// a hand-edited line; skip it
			}
		}
		return times;
	}

	static String format(Map<WorldIndex.ChunkKey, Long> times) {
		StringBuilder out = new StringBuilder(HEADER);
		times.forEach((key, ticks) -> out.append(key.dimension().identifier()).append(' ').append(key.x()).append(' ')
			.append(key.z()).append(' ').append(ticks).append('\n'));
		return out.toString();
	}
}
