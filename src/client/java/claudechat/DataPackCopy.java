package claudechat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Enumeration;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Copies one folder of a world data pack (its data/) into the knowledge base, from a folder pack or a zip. A pack can
 * be anyone's download, so the copy is bounded ({@link Limits}), counting the bytes actually read rather than the sizes
 * a zip claims; entries whose path would leave the target are skipped, and a folder pack's links aren't followed
 * ({@link KnowledgeFiles#regularFiles}).
 */
final class DataPackCopy {
	/**
	 * What one pack may hold. The default is well above the largest mods' data in a heavily modded game, which is
	 * at most 7,898 files and 38 MB, with no file over about 1 MB.
	 *
	 * @param maxEntries entries in a zip (all of them, not only data/), or files in a folder pack's data/
	 * @param maxFileBytes bytes of one file, uncompressed
	 * @param maxTotalBytes bytes of all the files copied, uncompressed
	 */
	record Limits(int maxEntries, long maxFileBytes, long maxTotalBytes) {
		static final Limits DEFAULT = new Limits(1 << 16, 64L << 20, 512L << 20);
	}

	private DataPackCopy() {
	}

	/**
	 * Copies the files under {@code data} to the same relative paths under {@code target}. Does nothing when {@code data}
	 * isn't a folder.
	 *
	 * @throws TooLargeException if the pack is over {@code limits}
	 */
	static void fromFolder(Path data, Path target, Limits limits) throws IOException {
		if (!Files.isDirectory(data)) {
			return;
		}

		Tally tally = new Tally(limits);

		for (Path file : KnowledgeFiles.regularFiles(data)) {
			String relative = data.relativize(file).toString();
			tally.entry();
			Optional<Path> to = KnowledgeFiles.resolveInside(target, relative);

			if (to.isPresent()) {
				try (InputStream in = Files.newInputStream(file)) {
					copy(in, to.get(), relative, tally);
				}
			}
		}
	}

	/**
	 * Copies the entries under {@code folder}/ in a zip to the same relative paths under {@code target}. Names use '/',
	 * as the game reads them.
	 *
	 * @throws TooLargeException if the pack is over {@code limits}
	 */
	static void fromZip(Path zipFile, String folder, Path target, Limits limits) throws IOException {
		String prefix = folder + "/";
		Tally tally = new Tally(limits);

		try (ZipFile zip = new ZipFile(zipFile.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();

			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				tally.entry();

				if (entry.isDirectory() || !entry.getName().startsWith(prefix)) {
					continue;
				}

				Optional<Path> to = KnowledgeFiles.resolveInside(target, entry.getName().substring(prefix.length()));

				if (to.isPresent()) {
					try (InputStream in = zip.getInputStream(entry)) {
						copy(in, to.get(), entry.getName(), tally);
					}
				}
			}
		}
	}

	/** Copies one file, counting its bytes as they are read. Never writes through a link at {@code to}. */
	private static void copy(InputStream in, Path to, String name, Tally tally) throws IOException {
		Files.createDirectories(to.getParent());

		try (OutputStream out = Files.newOutputStream(to, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
			StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
			byte[] buffer = new byte[64 * 1024];
			long fileBytes = 0;
			int read;

			while ((read = in.read(buffer)) > 0) {
				fileBytes += read;
				tally.bytes(name, fileBytes, read);
				out.write(buffer, 0, read);
			}
		}
	}

	/** What a copy has used so far, checked against its limits. */
	private static final class Tally {
		private final Limits limits;
		private int entries;
		private long totalBytes;

		Tally(Limits limits) {
			this.limits = limits;
		}

		void entry() throws TooLargeException {
			entries++;

			if (entries > limits.maxEntries()) {
				throw new TooLargeException("the pack has more than " + limits.maxEntries() + " entries (the limit)");
			}
		}

		/** {@code read} more bytes of {@code name}, which has {@code fileBytes} so far. */
		void bytes(String name, long fileBytes, int read) throws TooLargeException {
			totalBytes += read;

			if (fileBytes > limits.maxFileBytes()) {
				throw new TooLargeException(name + " is over " + mebibytes(limits.maxFileBytes()) + " uncompressed (the limit)");
			}

			if (totalBytes > limits.maxTotalBytes()) {
				throw new TooLargeException("the pack is over " + mebibytes(limits.maxTotalBytes()) + " uncompressed (the limit)");
			}
		}

		private static String mebibytes(long bytes) {
			return bytes >= 1 << 20 ? (bytes >> 20) + " MiB" : bytes + " bytes";
		}
	}
}
