package claudechat;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * {@value #NAME} in the world folder: the InhabitedTime of chunks that is newer than their save (see {@link WorldIndex}).
 * One line per chunk: dimension id, chunk x, chunk z, ticks. Files from before dimension ids were written in full
 * ("overworld" for "minecraft:overworld") read the same.
 * <p>
 * A world can come from anywhere, so this file is treated like any other imported file: reading it is bounded
 * ({@link #MAX_CHARACTERS}, {@link #MAX_LINE}, {@link #MAX_RECORDS}) and doesn't follow a link, and writing it never
 * opens an existing file: a link planted in the world can't redirect the write.
 */
final class VisitsFile {
	static final String NAME = "claudechat-visits.txt";

	/**
	 * Chunks the file holds at most; a longer file keeps its first ones, and writing keeps the longest times. A player
	 * would have to walk through half a million chunks the game never saved again to reach it; a record takes about
	 * 100 bytes of memory once read.
	 */
	static final int MAX_RECORDS = 1 << 19;

	/** The longest line read; longer ones are skipped. Lines this mod writes are under 80 characters. */
	static final int MAX_LINE = 256;

	/** The most text read (32 Mi characters): {@link #MAX_RECORDS} lines of up to 64 characters. A longer file isn't read. */
	static final long MAX_CHARACTERS = (long) MAX_RECORDS * 64;

	private static final String HEADER = "# Claude Chat: time players spent near chunks, which Minecraft doesn't always save.\n"
		+ "# dimension chunkX chunkZ ticks\n";
	private static final SecureRandom RANDOM = new SecureRandom();

	private VisitsFile() {
	}

	/**
	 * The recorded times; empty if there's no file yet, or if what's there is a link or not a file.
	 *
	 * @throws TooLargeException if the file is longer than {@link #MAX_CHARACTERS}
	 */
	static Map<WorldIndex.ChunkKey, Long> read(Path worldRoot) throws IOException {
		Path file = worldRoot.resolve(NAME);
		BasicFileAttributes attributes;
		try {
			attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		} catch (IOException e) {
			return Map.of();
		}
		if (!attributes.isRegularFile()) {
			if (attributes.isSymbolicLink()) {
				ClaudeChatClient.LOGGER.info("Not following the link {}", file);
			}
			return Map.of();
		}
		// UTF-8 takes at least one byte per character, so a file within the limit in bytes is within it in characters.
		if (attributes.size() > MAX_CHARACTERS) {
			throw new TooLargeException("it is " + attributes.size() + " bytes long (the limit is " + MAX_CHARACTERS + ")");
		}
		try (Reader in = new InputStreamReader(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), StandardCharsets.UTF_8)) {
			return parse(in);
		}
	}

	/**
	 * Replaces the file in one step, so a crash mid-write leaves the old one. The new text goes to a file with a random
	 * name that only this call can have created ({@link StandardOpenOption#CREATE_NEW} refuses anything already there,
	 * a link included), which is then renamed over {@value #NAME}; renaming replaces a link rather than following it.
	 * At most {@link #MAX_RECORDS} chunks are written, those with the longest times.
	 */
	static void write(Path worldRoot, Map<WorldIndex.ChunkKey, Long> times) throws IOException {
		Path folder = worldRoot.toRealPath();
		Path file = folder.resolve(NAME);
		Path temp = folder.resolve(NAME + "." + HexFormat.of().formatHex(randomBytes()) + ".tmp");
		try {
			try (SeekableByteChannel out = Files.newByteChannel(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
				ByteBuffer bytes = StandardCharsets.UTF_8.encode(format(longest(times)));
				while (bytes.hasRemaining()) {
					out.write(bytes);
				}
			}
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} finally {
			// Ours, so safe to delete; after the move there's nothing there.
			Files.deleteIfExists(temp);
		}
	}

	private static byte[] randomBytes() {
		byte[] bytes = new byte[8];
		RANDOM.nextBytes(bytes);
		return bytes;
	}

	private static Map<WorldIndex.ChunkKey, Long> longest(Map<WorldIndex.ChunkKey, Long> times) {
		if (times.size() <= MAX_RECORDS) {
			return times;
		}
		Map<WorldIndex.ChunkKey, Long> kept = new HashMap<>();
		times.entrySet().stream().sorted(Map.Entry.<WorldIndex.ChunkKey, Long>comparingByValue(Comparator.reverseOrder()))
			.limit(MAX_RECORDS).forEach(entry -> kept.put(entry.getKey(), entry.getValue()));
		return kept;
	}

	/**
	 * Lines that don't parse (hand-edited, say) are skipped, as are lines over {@link #MAX_LINE}; a chunk listed twice
	 * keeps its longer time. Reading stops after {@link #MAX_RECORDS} chunks.
	 *
	 * @throws TooLargeException after {@link #MAX_CHARACTERS} characters
	 */
	static Map<WorldIndex.ChunkKey, Long> parse(Reader in) throws IOException {
		Map<WorldIndex.ChunkKey, Long> times = new HashMap<>();
		StringBuilder line = new StringBuilder();
		boolean overlong = false;
		long read = 0;
		char[] buffer = new char[8192];
		int n;
		while (times.size() < MAX_RECORDS && (n = in.read(buffer)) >= 0) {
			read += n;
			if (read > MAX_CHARACTERS) {
				throw new TooLargeException("it is longer than " + MAX_CHARACTERS + " characters (the limit)");
			}
			for (int i = 0; i < n && times.size() < MAX_RECORDS; i++) {
				char c = buffer[i];
				if (c == '\n') {
					if (!overlong) {
						parseLine(line.toString(), times);
					}
					line.setLength(0);
					overlong = false;
				} else if (line.length() < MAX_LINE) {
					line.append(c);
				} else {
					overlong = true;
				}
			}
		}
		if (!overlong && times.size() < MAX_RECORDS) {
			parseLine(line.toString(), times);
		}
		return times;
	}

	private static void parseLine(String line, Map<WorldIndex.ChunkKey, Long> times) {
		String[] parts = line.trim().split(" ");
		if (line.startsWith("#") || parts.length != 4) {
			return;
		}

		Identifier dimension = Identifier.tryParse(parts[0]);
		if (dimension == null) {
			return;
		}
		try {
			WorldIndex.ChunkKey key = new WorldIndex.ChunkKey(ResourceKey.create(Registries.DIMENSION, dimension),
				Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
			times.merge(key, Long.parseLong(parts[3]), Math::max);
		} catch (NumberFormatException e) {
			// a hand-edited line; skip it
		}
	}

	static String format(Map<WorldIndex.ChunkKey, Long> times) {
		StringBuilder out = new StringBuilder(HEADER);
		times.forEach((key, ticks) -> out.append(key.dimension().identifier()).append(' ').append(key.x()).append(' ')
			.append(key.z()).append(' ').append(ticks).append('\n'));
		return out.toString();
	}
}
