package claudechat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Copying a world data pack's data/ folder (and a mod's files, the same way): what is copied, what is skipped, and the
 * limits, counted from entries met and bytes read.
 */
class DataPackCopyTest {
	private static final DataPackCopy.Limits SMALL = new DataPackCopy.Limits(4, 1024, 2048);

	@TempDir
	Path temp;

	@Test
	void zipDataIsCopiedAndOtherFoldersAreNot() throws IOException {
		Path zip = zip(Map.of("pack.mcmeta", "{}", "data/minecraft/loot_table/a.json", "{\"a\":1}", "assets/x.png", "png"));

		DataPackCopy.fromZip(zip, "data", target(), budget(DataPackCopy.Limits.DEFAULT));

		assertEquals("{\"a\":1}", Files.readString(target().resolve("minecraft/loot_table/a.json")));
		assertFalse(Files.exists(target().resolve("x.png")));
	}

	@Test
	void zipEntriesThatWouldLeaveTheTargetAreSkipped() throws IOException {
		Path zip = zip(Map.of("data/../../escaped.txt", "x", "data/ok.json", "{}"));

		DataPackCopy.fromZip(zip, "data", target(), budget(DataPackCopy.Limits.DEFAULT));

		assertTrue(Files.isRegularFile(target().resolve("ok.json")));
		assertFalse(Files.exists(temp.resolve("escaped.txt")));
	}

	@Test
	void tooManyZipEntriesAreRefused() throws IOException {
		Path zip = zip(Map.of("a", "", "b", "", "c", "", "d", "", "data/e.json", ""));

		TooLargeException e = assertThrows(TooLargeException.class, () -> DataPackCopy.fromZip(zip, "data", target(), budget(SMALL)));

		assertTrue(e.getMessage().contains("more than 4 entries"), e.getMessage());
	}

	@Test
	void aLargeZipEntryIsRefusedByTheBytesRead() throws IOException {
		// Zeros compress to a few bytes, so only counting what is read finds the size.
		Path zip = zip(Map.of("data/big.json", "0".repeat(1025)));

		TooLargeException e = assertThrows(TooLargeException.class, () -> DataPackCopy.fromZip(zip, "data", target(), budget(SMALL)));

		assertTrue(e.getMessage().contains("data/big.json is over 1024 bytes"), e.getMessage());
	}

	@Test
	void theTotalOfAllEntriesIsLimited() throws IOException {
		Path zip = zip(Map.of("data/a.json", "0".repeat(1000), "data/b.json", "0".repeat(1000), "data/c.json", "0".repeat(1000)));

		TooLargeException e = assertThrows(TooLargeException.class, () -> DataPackCopy.fromZip(zip, "data", target(), budget(SMALL)));

		assertTrue(e.getMessage().contains("over 2048 bytes uncompressed in the pack"), e.getMessage());
	}

	@Test
	void folderPackIsCopiedWithinLimits() throws IOException {
		Path data = Files.createDirectories(temp.resolve("pack/data/minecraft"));
		Files.writeString(data.resolve("a.json"), "{}");

		DataPackCopy.fromFolder(temp.resolve("pack/data"), target(), budget(SMALL));

		assertEquals("{}", Files.readString(target().resolve("minecraft/a.json")));
		Files.writeString(data.resolve("big.json"), "0".repeat(1025));
		assertThrows(TooLargeException.class, () -> DataPackCopy.fromFolder(temp.resolve("pack/data"), temp.resolve("again"), budget(SMALL)));
	}

	@Test
	void folderPackJunctionsAreNotFollowed() throws Exception {
		Path outside = Files.createDirectories(temp.resolve("outside"));
		Files.writeString(outside.resolve("secret.json"), "secret");
		Path data = Files.createDirectories(temp.resolve("pack/data"));
		Files.writeString(data.resolve("own.json"), "{}");
		FileLinks.junction(data.resolve("linked"), outside);

		DataPackCopy.fromFolder(data, target(), budget(DataPackCopy.Limits.DEFAULT));

		assertTrue(Files.isRegularFile(target().resolve("own.json")));
		assertFalse(Files.exists(target().resolve("linked")));
	}

	@Test
	void aFolderOverTheEntryLimitStopsAtTheLimit() throws IOException {
		Path data = Files.createDirectories(temp.resolve("pack/data"));

		for (int i = 0; i < 50; i++) {
			Files.writeString(data.resolve(i + ".json"), "{}");
		}

		TooLargeException e = assertThrows(TooLargeException.class, () -> DataPackCopy.fromFolder(data, target(), budget(SMALL)));

		assertTrue(e.getMessage().contains("more than 4 entries in the pack"), e.getMessage());
		// The folder itself is an entry, so at most three files were read before the fifth entry stopped the walk.
		assertTrue(fileCount(target()) <= 3, "copied " + fileCount(target()));
	}

	@Test
	void fingerprintingMeetsTheSameEntryLimit() throws IOException {
		Path data = Files.createDirectories(temp.resolve("pack/data"));

		for (int i = 0; i < 5; i++) {
			Files.writeString(data.resolve(i + ".json"), "{}");
		}

		assertThrows(TooLargeException.class, () -> DataPackCopy.fingerprint(data, budget(SMALL)));
		String first = DataPackCopy.fingerprint(data, budget(DataPackCopy.Limits.DEFAULT));
		assertEquals(first, DataPackCopy.fingerprint(data, budget(DataPackCopy.Limits.DEFAULT)));
		Files.writeString(data.resolve("0.json"), "{\"changed\":1}");
		assertNotEquals(first, DataPackCopy.fingerprint(data, budget(DataPackCopy.Limits.DEFAULT)));
	}

	@Test
	void copiesSharingAParentShareItsLimits() throws IOException {
		// Each copy is within its own limits; together they're over the parent's, as a world's packs or a mod's jars are.
		DataPackCopy.Budget world = new DataPackCopy.Budget(SMALL, "the world's data packs together");
		Path first = zip(Map.of("data/a.json", "0".repeat(1000)));
		DataPackCopy.fromZip(first, "data", temp.resolve("one"), new DataPackCopy.Budget(DataPackCopy.Limits.DEFAULT, "the pack", world));
		Path second = zip(Map.of("data/b.json", "0".repeat(1100)));

		TooLargeException e = assertThrows(TooLargeException.class, () -> DataPackCopy.fromZip(second, "data", temp.resolve("two"),
			new DataPackCopy.Budget(DataPackCopy.Limits.DEFAULT, "the pack", world)));

		assertTrue(e.getMessage().contains("over 2048 bytes uncompressed in the world's data packs together"), e.getMessage());
	}

	@Test
	void whatEarlierCopiesTookCountsAgainstTheLimits() throws IOException {
		DataPackCopy.Budget world = new DataPackCopy.Budget(SMALL, "the world's data packs together");
		world.charge(new DataPackCopy.Usage(1, 2000));
		Path zip = zip(Map.of("data/a.json", "0".repeat(100)));

		assertThrows(TooLargeException.class, () -> DataPackCopy.fromZip(zip, "data", target(),
			new DataPackCopy.Budget(DataPackCopy.Limits.DEFAULT, "the pack", world)));
	}

	@Test
	void aCopyMeasuresWhatItLeft() throws IOException {
		Path zip = zip(Map.of("data/a/b.json", "12345", "data/c.json", "123"));
		DataPackCopy.Budget budget = budget(DataPackCopy.Limits.DEFAULT);

		DataPackCopy.fromZip(zip, "data", target(), budget);

		assertEquals(8, budget.usage().bytes());
		assertEquals(8, DataPackCopy.measure(target()).bytes());
		assertEquals(DataPackCopy.Usage.NONE, DataPackCopy.measure(temp.resolve("missing")));
	}

	@Test
	void foldersTooDeepAreRefused() throws IOException {
		Path data = Files.createDirectories(temp.resolve("pack/data"));
		Path deep = data;

		for (int i = 0; i < DataPackCopy.MAX_DEPTH; i++) {
			deep = deep.resolve("d");
		}

		Files.createDirectories(deep);
		Files.writeString(deep.resolve("deep.json"), "{}");

		TooLargeException e = assertThrows(TooLargeException.class,
			() -> DataPackCopy.fromFolder(data, target(), budget(DataPackCopy.Limits.DEFAULT)));

		assertTrue(e.getMessage().contains("folders deep"), e.getMessage());
		Path zip = zip(Map.of("data/" + "d/".repeat(DataPackCopy.MAX_DEPTH) + "deep.json", "{}"));
		assertThrows(TooLargeException.class, () -> DataPackCopy.fromZip(zip, "data", temp.resolve("z"), budget(DataPackCopy.Limits.DEFAULT)));
	}

	@Test
	void anInterruptedCopyStops() throws IOException {
		Path zip = zip(Map.of("data/a.json", "{}"));
		Thread.currentThread().interrupt();

		try {
			assertThrows(InterruptedIOException.class, () -> DataPackCopy.fromZip(zip, "data", target(), budget(DataPackCopy.Limits.DEFAULT)));
		} finally {
			Thread.interrupted();
		}
	}

	private static DataPackCopy.Budget budget(DataPackCopy.Limits limits) {
		return new DataPackCopy.Budget(limits, "the pack");
	}

	private static long fileCount(Path folder) throws IOException {
		if (!Files.isDirectory(folder)) {
			return 0;
		}

		try (Stream<Path> files = Files.walk(folder)) {
			return files.filter(Files::isRegularFile).count();
		}
	}

	private Path target() {
		return temp.resolve("target");
	}

	private Path zip(Map<String, String> entries) throws IOException {
		Path zip = temp.resolve("pack.zip");

		try (OutputStream file = Files.newOutputStream(zip); ZipOutputStream out = new ZipOutputStream(file)) {
			for (Map.Entry<String, String> entry : entries.entrySet()) {
				out.putNextEntry(new ZipEntry(entry.getKey()));
				out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
				out.closeEntry();
			}
		}

		return zip;
	}
}
