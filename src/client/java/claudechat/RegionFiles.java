package claudechat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Minecraft's region files (r.x.z.mca), read-only: a header with each of 1024 chunks' location and save time, then
 * the compressed chunk NBT. Callers deal in chunk coordinates; nothing else knows the file layout.
 */
final class RegionFiles {
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

	/** The region files in a folder; none if it doesn't exist. */
	static List<Region> list(Path dir) throws IOException {
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(dir)) {
			return files.map(RegionFiles::region).flatMap(Optional::stream).toList();
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
	 */
	static Optional<CompoundTag> readChunk(FileChannel channel, Region region, int location, int chunkX, int chunkZ) throws IOException {
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
			if (!Files.isRegularFile(external)) {
				return Optional.empty();
			}
			raw = Files.newInputStream(external);
		} else {
			if (length <= 1 || length > (location & 0xFF) * SECTOR) {
				return Optional.empty();
			}
			ByteBuffer data = ByteBuffer.allocate(4 + length);
			readFully(channel, data, offset);
			raw = new ByteArrayInputStream(data.array(), 5, length - 1);
		}
		try (DataInputStream in = new DataInputStream(version.wrap(raw))) {
			return Optional.of(NbtIo.read(in));
		}
	}

	/** One chunk from a region folder, or empty if it isn't on disk (or can't be read right now). */
	static Optional<CompoundTag> readChunk(Path dir, int chunkX, int chunkZ) throws IOException {
		Region region = new Region(dir.resolve("r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca"), chunkX >> 5, chunkZ >> 5);
		if (!Files.isRegularFile(region.file())) {
			return Optional.empty();
		}
		try (FileChannel channel = FileChannel.open(region.file(), StandardOpenOption.READ)) {
			ByteBuffer entry = ByteBuffer.allocate(4);
			readFully(channel, entry, ((chunkX & 31) + (chunkZ & 31) * 32) * 4L);
			int location = entry.getInt(0);
			if (location == 0) {
				return Optional.empty();
			}
			return readChunk(channel, region, location, chunkX, chunkZ);
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
