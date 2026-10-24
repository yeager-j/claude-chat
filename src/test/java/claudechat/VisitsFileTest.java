package claudechat;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@value VisitsFile#NAME} comes with the world, from anywhere: writing it can't be redirected by a link planted in
 * the world, and reading it is bounded.
 */
class VisitsFileTest {
	private static final ResourceKey<Level> OVERWORLD = ResourceKey.create(Registries.DIMENSION, Identifier.parse("minecraft:overworld"));
	private static final Map<WorldIndex.ChunkKey, Long> TIMES = Map.of(new WorldIndex.ChunkKey(OVERWORLD, 1, 2), 50L);

	@TempDir
	Path temp;

	@Test
	void writtenTimesReadBackAndNoTemporaryFileStays() throws IOException {
		Path world = world();

		VisitsFile.write(world, TIMES);
		VisitsFile.write(world, TIMES);

		assertEquals(TIMES, VisitsFile.read(world));
		assertEquals(List.of(VisitsFile.NAME), names(world));
	}

	@Test
	void linkAtTheOldTemporaryNameIsNotFollowed() throws IOException {
		Path world = world();
		Path sentinel = sentinel();
		Path link = FileLinks.symbolicLink(world.resolve(VisitsFile.NAME + ".tmp"), sentinel);

		VisitsFile.write(world, TIMES);

		assertEquals("keep me", Files.readString(sentinel));
		assertTrue(Files.isSymbolicLink(link));
		assertEquals(TIMES, VisitsFile.read(world));
	}

	@Test
	void linkAtTheFileItselfIsReplacedNotFollowed() throws IOException {
		Path world = world();
		Path sentinel = sentinel();
		Path link = FileLinks.symbolicLink(world.resolve(VisitsFile.NAME), sentinel);

		VisitsFile.write(world, TIMES);

		assertEquals("keep me", Files.readString(sentinel));
		assertFalse(Files.isSymbolicLink(link));
		assertTrue(Files.isRegularFile(link, LinkOption.NOFOLLOW_LINKS));
		assertEquals(TIMES, VisitsFile.read(world));
	}

	@Test
	void linkedFileIsNotRead() throws IOException {
		Path world = world();
		Path elsewhere = temp.resolve("elsewhere.txt");
		Files.writeString(elsewhere, VisitsFile.format(TIMES));
		FileLinks.symbolicLink(world.resolve(VisitsFile.NAME), elsewhere);

		assertEquals(Map.of(), VisitsFile.read(world));
	}

	@Test
	void missingFileIsEmpty() throws IOException {
		assertEquals(Map.of(), VisitsFile.read(world()));
	}

	@Test
	void fileOverTheLimitIsRefusedBeforeReading() throws IOException {
		Path world = world();
		try (RandomAccessFile file = new RandomAccessFile(world.resolve(VisitsFile.NAME).toFile(), "rw")) {
			file.setLength(VisitsFile.MAX_CHARACTERS + 1);
		}

		assertThrows(TooLargeException.class, () -> VisitsFile.read(world));
	}

	@Test
	void textOverTheLimitStopsTheParse() {
		// One endless line: skipped as too long, so only the character limit can stop it.
		assertThrows(TooLargeException.class, () -> VisitsFile.parse(endless(i -> "#")));
	}

	@Test
	void overlongLinesAreSkipped() throws IOException {
		String text = "minecraft:overworld 9 9 " + "1".repeat(VisitsFile.MAX_LINE) + "\nminecraft:overworld 1 2 50";

		assertEquals(TIMES, VisitsFile.parse(new StringReader(text)));
	}

	@Test
	void parsingStopsAtTheRecordLimit() throws IOException {
		Map<WorldIndex.ChunkKey, Long> times = VisitsFile.parse(endless(i -> "minecraft:overworld " + i + " 0 20\n"));

		assertEquals(VisitsFile.MAX_RECORDS, times.size());
		assertTrue(times.containsKey(new WorldIndex.ChunkKey(OVERWORLD, 0, 0)));
		assertFalse(times.containsKey(new WorldIndex.ChunkKey(OVERWORLD, VisitsFile.MAX_RECORDS, 0)));
	}

	@Test
	void writingKeepsTheLongestTimesWithinTheLimit() throws IOException {
		Path world = world();
		Map<WorldIndex.ChunkKey, Long> times = new HashMap<>();
		for (int i = 0; i <= VisitsFile.MAX_RECORDS; i++) {
			times.put(new WorldIndex.ChunkKey(OVERWORLD, i, 0), 100L + i);
		}

		VisitsFile.write(world, times);
		Map<WorldIndex.ChunkKey, Long> read = VisitsFile.read(world);

		assertEquals(VisitsFile.MAX_RECORDS, read.size());
		assertFalse(read.containsKey(new WorldIndex.ChunkKey(OVERWORLD, 0, 0)));
		assertEquals(100L + VisitsFile.MAX_RECORDS, read.get(new WorldIndex.ChunkKey(OVERWORLD, VisitsFile.MAX_RECORDS, 0)));
	}

	private Path world() throws IOException {
		return Files.createDirectories(temp.resolve("world"));
	}

	private Path sentinel() throws IOException {
		Path sentinel = temp.resolve("sentinel.txt");
		Files.writeString(sentinel, "keep me");
		return sentinel;
	}

	private static List<String> names(Path dir) throws IOException {
		try (Stream<Path> files = Files.list(dir)) {
			return files.map(file -> file.getFileName().toString()).toList();
		}
	}

	/** Text that never ends: {@code piece} of 0, 1, 2… one after another. */
	private static Reader endless(IntFunction<String> piece) {
		return new Reader() {
			private String current = "";
			private int position;
			private int next;

			@Override
			public int read(char[] buffer, int offset, int length) {
				int written = 0;
				while (written < length) {
					if (position == current.length()) {
						current = piece.apply(next++);
						position = 0;
					}
					int n = Math.min(length - written, current.length() - position);
					current.getChars(position, position + n, buffer, offset + written);
					position += n;
					written += n;
				}
				return written;
			}

			@Override
			public void close() {
			}
		};
	}
}
