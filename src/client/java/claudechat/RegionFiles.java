package claudechat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Minecraft's region files (r.x.z.mca), read-only: a header with each of 1024 chunks' location and save time, then
 * the compressed chunk NBT. Callers deal in chunk coordinates; nothing else knows the file layout.
 * <p>
 * A world can come from anywhere, so its files are read like other imported ones: no links are followed (a region
 * folder whose real location isn't where its names say, a region or {@code .mcc} file that is a link), and each chunk is
 * read within an {@link NbtBudget}.
 */
final class RegionFiles {
	/**
	 * The budget for one chunk (64 MiB of the game's memory estimate), and the largest external {@code .mcc} file read.
	 * Chunks the game writes are far smaller: one stored in its region file is at most 1 MiB compressed, and only
	 * larger ones go to an external file.
	 */
	static final long CHUNK_BYTES = 64L << 20;

	private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
	private static final int SECTOR = 4096;
	private static final int CHUNKS = 1024;

	private RegionFiles() {
	}

	/** One region file and the 32×32 chunks it covers. */
	record Region(Path file, int regionX, int regionZ) {
		int chunkX(int index) {
			return regionX * 32 + (index & 31);
		}

		int chunkZ(int index) {
			return regionZ * 32 + (index >> 5);
		}

		boolean contains(int chunkX, int chunkZ) {
			return chunkX >> 5 == regionX && chunkZ >> 5 == regionZ;
		}
	}

	/** Where each chunk is stored (0 if it isn't) and when it was last saved. */
	record Header(int[] locations, int[] timestamps) {
	}

	/**
	 * The region files in {@code dir}, a folder of the world at {@code worldRoot}; none if it doesn't exist, or if a link
	 * is on the way to it from the world folder. Region files that are links are left out.
	 */
	static List<Region> list(Path worldRoot, Path dir) throws IOException {
		if (!isUnlinkedFolder(worldRoot, dir)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(dir)) {
			return files.map(RegionFiles::region).flatMap(Optional::stream).filter(region -> isPlainFile(region.file())).toList();
		}
	}

	/** Opens a region file for reading, refusing a link. */
	static FileChannel open(Region region) throws IOException {
		return FileChannel.open(region.file(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
	}

	/**
	 * Whether {@code dir} is a folder whose real location is where its names say: below the real location of
	 * {@code worldRoot}, by the same names. A link anywhere between them (to another world, or to another folder of this
	 * one) makes it false, as does a folder that doesn't exist.
	 */
	static boolean isUnlinkedFolder(Path worldRoot, Path dir) {
		if (!KnowledgeFiles.isPlainDirectory(dir)) {
			return false;
		}
		try {
			Path root = worldRoot.toAbsolutePath().normalize();
			Path relative = root.relativize(dir.toAbsolutePath().normalize());
			boolean unlinked = dir.toRealPath().equals(root.toRealPath().resolve(relative));
			if (!unlinked) {
				ClaudeChatClient.LOGGER.info("Not reading {}: a link leads it elsewhere", dir);
			}
			return unlinked;
		} catch (IOException | IllegalArgumentException e) {
			return false;
		}
	}

	/** A regular file, not a link; false when it can't be checked. */
	private static boolean isPlainFile(Path file) {
		try {
			return Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile();
		} catch (IOException e) {
			return false;
		}
	}

	private static Optional<Region> region(Path file) {
		Matcher m = REGION_NAME.matcher(file.getFileName().toString());
		if (!m.matches()) {
			return Optional.empty();
		}
		return Optional.of(new Region(file, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
	}

	/** The header, or empty for a file too short to have one (just created). */
	static Optional<Header> readHeader(FileChannel channel) throws IOException {
		if (channel.size() < 2L * SECTOR) {
			return Optional.empty();
		}
		ByteBuffer buffer = ByteBuffer.allocate(2 * SECTOR);
		readFully(channel, buffer, 0);
		int[] locations = new int[CHUNKS];
		int[] timestamps = new int[CHUNKS];
		for (int i = 0; i < CHUNKS; i++) {
			locations[i] = buffer.getInt(i * 4);
			timestamps[i] = buffer.getInt(SECTOR + i * 4);
		}
		return Optional.of(new Header(locations, timestamps));
	}

	/**
	 * One chunk's NBT at a header location, or empty if what's there can't be read as a chunk right now (a write in
	 * progress, an unknown compression).
	 *
	 * @throws TooLargeException if the chunk is over {@code budget}, or its external file over {@link #CHUNK_BYTES}
	 * @throws java.util.concurrent.CancellationException if the budget says so while reading
	 */
	static Optional<CompoundTag> readChunk(FileChannel channel, Region region, int location, int chunkX, int chunkZ, NbtBudget budget)
		throws IOException {
		long offset = (long) (location >>> 8) * SECTOR;
		// While the game runs, the region file isn't padded to whole sectors yet (that happens when it's closed),
		// so read the chunk's own length rather than assuming its last sector is complete.
		ByteBuffer prefix = ByteBuffer.allocate(5);
		readFully(channel, prefix, offset);
		int length = prefix.getInt(0);
		byte type = prefix.get(4);
		RegionFileVersion version = RegionFileVersion.fromId(type & 0x7F);
		if (version == null) {
			return Optional.empty();
		}

		InputStream raw;
		if ((type & 0x80) != 0) {
			// An oversized chunk lives next to the region file as c.<x>.<z>.mcc, leaving only its compression byte
			// here (length 1), so this has to be decided before the inline length check below.
			Path external = region.file().resolveSibling("c." + chunkX + "." + chunkZ + ".mcc");
			if (!isPlainFile(external)) {
				return Optional.empty();
			}
			TooLargeException.requireAtMost(Files.size(external), CHUNK_BYTES, "bytes in its external chunk file");
			raw = Files.newInputStream(external, LinkOption.NOFOLLOW_LINKS);
		} else {
			if (length <= 1 || length > (location & 0xFF) * SECTOR) {
				return Optional.empty();
			}
			ByteBuffer data = ByteBuffer.allocate(4 + length);
			readFully(channel, data, offset);
			raw = new ByteArrayInputStream(data.array(), 5, length - 1);
		}
		try (InputStream source = raw; InputStream in = version.wrap(source)) {
			return Optional.of(budget.readUncompressed(in));
		}
	}

	/**
	 * One chunk from {@code dir}, a region folder of the world at {@code worldRoot}, or empty if it isn't on disk (or
	 * can't be read right now, or a link is on the way to it). See {@link #readChunk(FileChannel, Region, int, int, int, NbtBudget)}.
	 */
	static Optional<CompoundTag> readChunk(Path worldRoot, Path dir, int chunkX, int chunkZ, NbtBudget budget) throws IOException {
		Region region = new Region(dir.resolve("r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca"), chunkX >> 5, chunkZ >> 5);
		if (!isUnlinkedFolder(worldRoot, dir) || !isPlainFile(region.file())) {
			return Optional.empty();
		}
		try (FileChannel channel = open(region)) {
			ByteBuffer entry = ByteBuffer.allocate(4);
			readFully(channel, entry, ((chunkX & 31) + (chunkZ & 31) * 32) * 4L);
			int location = entry.getInt(0);
			if (location == 0) {
				return Optional.empty();
			}
			return readChunk(channel, region, location, chunkX, chunkZ, budget);
		}
	}

	private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
		while (buffer.hasRemaining()) {
			if (channel.read(buffer, position + buffer.position()) < 0) {
				throw new IOException("unexpected end of region file at " + (position + buffer.position()) + " of " + channel.size());
			}
		}
	}
}
