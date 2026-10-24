package claudechat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Copying a world data pack's data/ folder: what is copied, what is skipped, and the limits, counted from bytes read. */
class DataPackCopyTest {
	private static final DataPackCopy.Limits SMALL = new DataPackCopy.Limits(4, 1024, 2048);

	@TempDir
	Path temp;

	@Test
	void zipDataIsCopiedAndOtherFoldersAreNot() throws IOException {
		Path zip = zip(Map.of("pack.mcmeta", "{}", "data/minecraft/loot_table/a.json", "{\"a\":1}", "assets/x.png", "png"));

		DataPackCopy.fromZip(zip, "data", target(), DataPackCopy.Limits.DEFAULT);

		assertEquals("{\"a\":1}", Files.readString(target().resolve("minecraft/loot_table/a.json")));
		assertFalse(Files.exists(target().resolve("x.png")));
	}

	@Test
	void zipEntriesThatWouldLeaveTheTargetAreSkipped() throws IOException {
		Path zip = zip(Map.of("data/../../escaped.txt", "x", "data/ok.json", "{}"));

		DataPackCopy.fromZip(zip, "data", target(), DataPackCopy.Limits.DEFAULT);

		assertTrue(Files.isRegularFile(target().resolve("ok.json")));
		assertFalse(Files.exists(temp.resolve("escaped.txt")));
	}

	@Test
	void tooManyZipEntriesAreRefused() throws IOException {
		Path zip = zip(Map.of("a", "", "b", "", "c", "", "d", "", "data/e.json", ""));

		TooLargeException e = assertThrows(TooLargeException.class, () -> DataPackCopy.fromZip(zip, "data", target(), SMALL));

		assertTrue(e.getMessage().contains("more than 4 entries"), e.getMessage());
	}

	@Test
	void aLargeZipEntryIsRefusedByTheBytesRead() throws IOException {
		// Zeros compress to a few bytes, so only counting what is read finds the size.
		Path zip = zip(Map.of("data/big.json", "0".repeat(1025)));

		TooLargeException e = assertThrows(TooLargeException.class, () -> DataPackCopy.fromZip(zip, "data", target(), SMALL));

		assertTrue(e.getMessage().contains("data/big.json is over 1024 bytes"), e.getMessage());
	}

	@Test
	void theTotalOfAllEntriesIsLimited() throws IOException {
		Path zip = zip(Map.of("data/a.json", "0".repeat(1000), "data/b.json", "0".repeat(1000), "data/c.json", "0".repeat(1000)));

		TooLargeException e = assertThrows(TooLargeException.class, () -> DataPackCopy.fromZip(zip, "data", target(), SMALL));

		assertTrue(e.getMessage().contains("the pack is over 2048 bytes"), e.getMessage());
	}

	@Test
	void folderPackIsCopiedWithinLimits() throws IOException {
		Path data = Files.createDirectories(temp.resolve("pack/data/minecraft"));
		Files.writeString(data.resolve("a.json"), "{}");

		DataPackCopy.fromFolder(temp.resolve("pack/data"), target(), SMALL);

		assertEquals("{}", Files.readString(target().resolve("minecraft/a.json")));
		Files.writeString(data.resolve("big.json"), "0".repeat(1025));
		assertThrows(TooLargeException.class, () -> DataPackCopy.fromFolder(temp.resolve("pack/data"), temp.resolve("again"), SMALL));
	}

	@Test
	void folderPackJunctionsAreNotFollowed() throws Exception {
		Path outside = Files.createDirectories(temp.resolve("outside"));
		Files.writeString(outside.resolve("secret.json"), "secret");
		Path data = Files.createDirectories(temp.resolve("pack/data"));
		Files.writeString(data.resolve("own.json"), "{}");
		FileLinks.junction(data.resolve("linked"), outside);

		DataPackCopy.fromFolder(data, target(), DataPackCopy.Limits.DEFAULT);

		assertTrue(Files.isRegularFile(target().resolve("own.json")));
		assertFalse(Files.exists(target().resolve("linked")));
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
