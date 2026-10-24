package claudechat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Region files as the game lays them out: header math, chunks found by coordinates, and files still being written. A
 * world can come from anywhere, so also: links aren't followed, and a chunk is read within a budget.
 */
class RegionFilesTest {
	private static final NbtBudget BUDGET = new NbtBudget(RegionFiles.CHUNK_BYTES, () -> false);
	private static final int SECTOR = 4096;
	private static final int FIRST_CHUNK_SECTOR = 2;

	@TempDir
	Path dir;

	@BeforeAll
	static void bootstrap() {
		GameBootstrap.init();
	}

	@Test
	void listFindsRegionFilesByNameWithNegativeCoordinates() throws IOException {
		for (String name : List.of("r.0.0.mca", "r.-1.2.mca", "r.0.0.mcc", "r.1.mca", "notes.txt")) {
			Files.createFile(dir.resolve(name));
		}

		Set<String> regions = RegionFiles.list(dir, dir).stream()
			.map(region -> region.regionX() + "," + region.regionZ())
			.collect(Collectors.toSet());

		assertEquals(Set.of("0,0", "-1,2"), regions);
	}

	@Test
	void missingFolderHasNoRegions() throws IOException {
		assertEquals(List.of(), RegionFiles.list(dir, dir.resolve("absent")));
	}

	@Test
	void regionMapsHeaderIndexesToChunkCoordinates() {
		RegionFiles.Region region = new RegionFiles.Region(dir.resolve("r.-1.2.mca"), -1, 2);

		assertEquals(-32, region.chunkX(0));
		assertEquals(64, region.chunkZ(0));
		assertEquals(-31, region.chunkX(33));
		assertEquals(65, region.chunkZ(33));
		assertEquals(-1, region.chunkX(1023));
		assertEquals(95, region.chunkZ(1023));
		assertTrue(region.contains(-32, 64));
		assertTrue(region.contains(-1, 95));
		assertFalse(region.contains(-33, 64));
		assertFalse(region.contains(0, 64));
		assertFalse(region.contains(-32, 96));
	}

	@Test
	void headerHoldsEachChunksLocationAndSaveTime() throws IOException {
		Path file = dir.resolve("r.0.0.mca");
		writeRegion(file, 5, record(chunk("a"), RegionFileVersion.VERSION_DEFLATE), 1, true);

		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
			RegionFiles.Header header = RegionFiles.readHeader(channel).orElseThrow();

			assertEquals(FIRST_CHUNK_SECTOR << 8 | 1, header.locations()[5]);
			assertEquals(1005, header.timestamps()[5]);
			assertEquals(0, header.locations()[6]);
		}
	}

	@Test
	void fileTooShortForAHeaderHasNone() throws IOException {
		Path file = dir.resolve("r.0.0.mca");
		Files.write(file, new byte[SECTOR]);

		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
			assertEquals(Optional.empty(), RegionFiles.readHeader(channel));
		}
	}

	@Test
	void chunkIsFoundByItsCoordinatesInANegativeRegion() throws IOException {
		// Chunk (-1, -33) is in region (-1, -2), at column 31 and row 31.
		int index = 31 + 31 * 32;
		writeRegion(dir.resolve("r.-1.-2.mca"), index, record(chunk("here"), RegionFileVersion.VERSION_DEFLATE), 1, true);

		assertEquals("here", nameOf(RegionFiles.readChunk(dir, dir, -1, -33, BUDGET)));
		assertEquals(Optional.empty(), RegionFiles.readChunk(dir, dir, -2, -33, BUDGET));
	}

	@Test
	void everyCompressionTheGameWritesIsRead() throws IOException {
		for (RegionFileVersion version : List.of(RegionFileVersion.VERSION_GZIP, RegionFileVersion.VERSION_DEFLATE,
			RegionFileVersion.VERSION_NONE, RegionFileVersion.VERSION_LZ4)) {
			String name = "compression " + version.getId();
			writeRegion(dir.resolve("r.0.0.mca"), 0, record(chunk(name), version), 1, true);

			assertEquals(name, nameOf(RegionFiles.readChunk(dir, dir, 0, 0, BUDGET)));
		}
	}

	@Test
	void chunkInAFileNotYetPaddedToWholeSectorsIsReadByItsOwnLength() throws IOException {
		// The game rounds the file up to whole sectors only when it closes the region; the header already claims two.
		Path file = dir.resolve("r.0.0.mca");
		writeRegion(file, 0, record(chunk("live"), RegionFileVersion.VERSION_DEFLATE), 2, false);

		assertTrue(Files.size(file) < (FIRST_CHUNK_SECTOR + 1) * SECTOR);
		assertEquals("live", nameOf(RegionFiles.readChunk(dir, dir, 0, 0, BUDGET)));
	}

	@Test
	void chunkWhoseLengthExceedsItsSectorsIsNotRead() throws IOException {
		byte[] record = record(chunk("x".repeat(2 * SECTOR)), RegionFileVersion.VERSION_NONE);
		writeRegion(dir.resolve("r.0.0.mca"), 0, record, 1, false);

		assertEquals(Optional.empty(), RegionFiles.readChunk(dir, dir, 0, 0, BUDGET));
	}

	@Test
	void absentChunkAndAbsentFileAreEmpty() throws IOException {
		writeRegion(dir.resolve("r.0.0.mca"), 0, record(chunk("a"), RegionFileVersion.VERSION_DEFLATE), 1, true);

		assertEquals(Optional.empty(), RegionFiles.readChunk(dir, dir, 1, 0, BUDGET));
		assertEquals(Optional.empty(), RegionFiles.readChunk(dir, dir, 32, 0, BUDGET));
	}

	@Test
	void unknownCompressionIsEmptyNotAnError() throws IOException {
		byte[] record = record(chunk("a"), RegionFileVersion.VERSION_DEFLATE);
		record[4] = 99;
		writeRegion(dir.resolve("r.0.0.mca"), 0, record, 1, true);

		assertEquals(Optional.empty(), RegionFiles.readChunk(dir, dir, 0, 0, BUDGET));
	}

	/**
	 * The game stores an oversized chunk in c.&lt;x&gt;.&lt;z&gt;.mcc and leaves a stub in the region: length 1 and the
	 * compression id with its high bit set. readChunk refuses every record of length 1 before it looks at that bit.
	 */
	@Test
	void oversizedChunkIsReadFromItsExternalFile() throws IOException {
		writeRegion(dir.resolve("r.0.0.mca"), 0, externalStub(), 1, true);
		Files.write(dir.resolve("c.0.0.mcc"), compressed(chunk("big"), RegionFileVersion.VERSION_DEFLATE));

		assertEquals("big", nameOf(RegionFiles.readChunk(dir, dir, 0, 0, BUDGET)));
	}

	@Test
	void chunkOverItsBudgetIsRefused() throws IOException {
		writeRegion(dir.resolve("r.0.0.mca"), 0, record(chunk("x".repeat(64 << 10)), RegionFileVersion.VERSION_DEFLATE), 1, true);
		NbtBudget small = new NbtBudget(16 << 10, () -> false);

		assertThrows(TooLargeException.class, () -> RegionFiles.readChunk(dir, dir, 0, 0, small));
	}

	@Test
	void externalChunkFileOverTheLimitIsRefusedBeforeReading() throws IOException {
		writeRegion(dir.resolve("r.0.0.mca"), 0, externalStub(), 1, true);
		try (RandomAccessFile external = new RandomAccessFile(dir.resolve("c.0.0.mcc").toFile(), "rw")) {
			external.setLength(RegionFiles.CHUNK_BYTES + 1);
		}

		assertThrows(TooLargeException.class, () -> RegionFiles.readChunk(dir, dir, 0, 0, BUDGET));
	}

	@Test
	void cancelledReadStops() throws IOException {
		writeRegion(dir.resolve("r.0.0.mca"), 0, record(chunk("a"), RegionFileVersion.VERSION_DEFLATE), 1, true);
		NbtBudget cancelled = new NbtBudget(RegionFiles.CHUNK_BYTES, () -> true);

		assertThrows(CancellationException.class, () -> RegionFiles.readChunk(dir, dir, 0, 0, cancelled));
	}

	@Test
	void linkedRegionFolderIsNotRead() throws IOException {
		Path world = Files.createDirectories(dir.resolve("world"));
		Path otherRegions = otherWorldRegions();
		FileLinks.symbolicLink(world.resolve("region"), otherRegions);

		assertNotRead(world, world.resolve("region"));
	}

	@Test
	void folderOnTheWayLinkedElsewhereIsNotRead() throws IOException {
		Path world = Files.createDirectories(dir.resolve("world"));
		Path otherDimension = otherWorldRegions().getParent();
		Files.createDirectories(world.resolve("dimensions/minecraft"));
		FileLinks.symbolicLink(world.resolve("dimensions/minecraft/the_nether"), otherDimension);

		assertNotRead(world, world.resolve("dimensions/minecraft/the_nether/region"));
	}

	@Test
	void regionFolderJunctionIsNotRead() throws IOException, InterruptedException {
		Path world = Files.createDirectories(dir.resolve("world"));
		Path otherRegions = otherWorldRegions();
		FileLinks.junction(world.resolve("region"), otherRegions);

		assertNotRead(world, world.resolve("region"));
	}

	@Test
	void linkedRegionFileIsNotRead() throws IOException {
		Path world = Files.createDirectories(dir.resolve("world/region"));
		FileLinks.symbolicLink(world.resolve("r.0.0.mca"), otherWorldRegions().resolve("r.0.0.mca"));

		assertNotRead(world.getParent(), world);
	}

	@Test
	void linkedExternalChunkFileIsNotRead() throws IOException {
		Path regions = Files.createDirectories(dir.resolve("world/region"));
		writeRegion(regions.resolve("r.0.0.mca"), 0, externalStub(), 1, true);
		Path elsewhere = Files.createDirectories(dir.resolve("elsewhere")).resolve("c.0.0.mcc");
		Files.write(elsewhere, compressed(chunk("elsewhere"), RegionFileVersion.VERSION_DEFLATE));
		FileLinks.symbolicLink(regions.resolve("c.0.0.mcc"), elsewhere);

		assertEquals(Optional.empty(), RegionFiles.readChunk(regions.getParent(), regions, 0, 0, BUDGET));
	}

	@Test
	void plainWorldFoldersAreRead() throws IOException {
		Path world = dir.resolve("world");
		Path regions = Files.createDirectories(world.resolve("dimensions/minecraft/the_nether/region"));
		writeRegion(regions.resolve("r.0.0.mca"), 0, record(chunk("nether"), RegionFileVersion.VERSION_DEFLATE), 1, true);

		assertEquals(1, RegionFiles.list(world, regions).size());
		assertEquals("nether", nameOf(RegionFiles.readChunk(world, regions, 0, 0, BUDGET)));
	}

	/** {@code regions} of {@code world} is neither listed nor read, though what it leads to holds a valid chunk. */
	private static void assertNotRead(Path world, Path regions) throws IOException {
		assertEquals(List.of(), RegionFiles.list(world, regions));
		assertEquals(Optional.empty(), RegionFiles.readChunk(world, regions, 0, 0, BUDGET));
	}

	/** Another world's region folder, with a valid chunk at 0, 0. */
	private Path otherWorldRegions() throws IOException {
		Path regions = Files.createDirectories(dir.resolve("other/region"));
		writeRegion(regions.resolve("r.0.0.mca"), 0, record(chunk("other world"), RegionFileVersion.VERSION_DEFLATE), 1, true);
		return regions;
	}

	/** The stub the game leaves in the region for a chunk stored in its .mcc file. */
	private static byte[] externalStub() {
		return ByteBuffer.allocate(5).putInt(1).put((byte) (RegionFileVersion.VERSION_DEFLATE.getId() | 0x80)).array();
	}

	private static String nameOf(Optional<CompoundTag> chunk) {
		return chunk.orElseThrow().getStringOr("name", "");
	}

	private static CompoundTag chunk(String name) {
		CompoundTag tag = new CompoundTag();
		tag.putString("name", name);
		return tag;
	}

	private static byte[] compressed(CompoundTag tag, RegionFileVersion version) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(version.wrap(bytes))) {
			NbtIo.write(tag, out);
		}
		return bytes.toByteArray();
	}

	/** A chunk as stored in a sector: its length (counting the compression byte), the compression id, the data. */
	private static byte[] record(CompoundTag tag, RegionFileVersion version) throws IOException {
		byte[] data = compressed(tag, version);
		return ByteBuffer.allocate(5 + data.length).putInt(1 + data.length).put((byte) version.getId()).put(data).array();
	}

	/**
	 * A region file whose header places one chunk at the first sector after the header, claiming {@code sectors}
	 * sectors; {@code padded} rounds the file up to them the way a closed region is.
	 */
	private static void writeRegion(Path file, int index, byte[] record, int sectors, boolean padded) throws IOException {
		ByteBuffer header = ByteBuffer.allocate(FIRST_CHUNK_SECTOR * SECTOR);
		header.putInt(index * 4, FIRST_CHUNK_SECTOR << 8 | sectors);
		header.putInt(SECTOR + index * 4, 1000 + index);

		int bodyLength = padded ? sectors * SECTOR : record.length;
		ByteBuffer body = ByteBuffer.allocate(bodyLength).put(record);

		Files.write(file, ByteBuffer.allocate(header.capacity() + bodyLength).put(header.array()).put(body.array()).array());
	}
}
