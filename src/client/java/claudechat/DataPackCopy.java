package claudechat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Copies files someone else made into the knowledge base: a world data pack's data/ (from a folder pack or a zip), and
 * a mod's data and English names. Either can be anyone's download, so every copy is charged to a {@link Budget}: each
 * entry it meets is counted before it is kept or read, bytes are counted as they are read rather than taken from the
 * sizes a zip claims, and the copy stops when the disk is nearly full or its thread is interrupted. Entries whose path
 * would leave the target are skipped, and a folder's links aren't followed ({@link KnowledgeFiles#walkRegularFiles}).
 */
final class DataPackCopy {
	/**
	 * What one copy may hold. The default is well above the largest mods' data in a heavily modded game, which is at
	 * most 7,898 files and 38 MB, with no file over about 1 MB. Its per-file limit also bounds what later readers of a
	 * copied file (recipe and tag parsing) can be handed.
	 *
	 * @param maxEntries entries met: in a zip all of them, not only data/; in a folder its files, folders and links
	 * @param maxFileBytes bytes of one file, uncompressed
	 * @param maxTotalBytes bytes of all the files copied, uncompressed
	 */
	record Limits(int maxEntries, long maxFileBytes, long maxTotalBytes) {
		/** One world pack, or one mod with its nested jars. */
		static final Limits DEFAULT = new Limits(1 << 16, 16L << 20, 512L << 20);
	}

	/** What a copy took: the entries it met and the bytes it copied. */
	record Usage(int entries, long bytes) {
		static final Usage NONE = new Usage(0, 0);

		Usage plus(Usage other) {
			return new Usage(entries + other.entries, bytes + other.bytes);
		}
	}

	/** How deep a copied file may be below the folder copied; data packs nest a handful of folders deep. */
	static final int MAX_DEPTH = 32;
	/** A copy stops when the disk has less than this left, so the knowledge base can't fill it. */
	static final long MIN_FREE_BYTES = 1L << 30;
	/** Free space is checked before the first file and again after each this many bytes. */
	private static final long FREE_SPACE_CHECK_BYTES = 16L << 20;

	private DataPackCopy() {
	}

	/**
	 * Copies the files under {@code data} to the same relative paths under {@code target}. Does nothing when {@code data}
	 * isn't a folder.
	 *
	 * @throws TooLargeException if the folder is over the budget
	 */
	static void fromFolder(Path data, Path target, Budget budget) throws IOException {
		if (!Files.isDirectory(data)) {
			return;
		}

		KnowledgeFiles.walkRegularFiles(data, MAX_DEPTH, budget::entry, (file, attributes) -> {
			String relative = data.relativize(file).toString();
			Optional<Path> to = KnowledgeFiles.resolveInside(target, relative);

			if (to.isPresent()) {
				try (InputStream in = Files.newInputStream(file)) {
					copy(in, to.get(), relative, budget);
				}
			}
		});
	}

	/**
	 * Copies the entries under {@code folder}/ in a zip to the same relative paths under {@code target}. Names use '/',
	 * as the game reads them.
	 *
	 * @throws TooLargeException if the zip is over the budget
	 */
	static void fromZip(Path zipFile, String folder, Path target, Budget budget) throws IOException {
		String prefix = folder + "/";

		try (ZipFile zip = new ZipFile(zipFile.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();

			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				budget.entry();

				if (entry.isDirectory() || !entry.getName().startsWith(prefix)) {
					continue;
				}

				String relative = entry.getName().substring(prefix.length());

				if (relative.chars().filter(c -> c == '/').count() >= MAX_DEPTH) {
					throw new TooLargeException(entry.getName() + " is more than " + MAX_DEPTH + " folders deep (the limit)");
				}

				Optional<Path> to = KnowledgeFiles.resolveInside(target, relative);

				if (to.isPresent()) {
					try (InputStream in = zip.getInputStream(entry)) {
						copy(in, to.get(), entry.getName(), budget);
					}
				}
			}
		}
	}

	/**
	 * Copies one regular file, charging its bytes but not an entry: the caller counted it when it found it. {@code name}
	 * names it in a limit's message. Not for a link: check first.
	 */
	static void copyFile(Path from, Path to, String name, Budget budget) throws IOException {
		try (InputStream in = Files.newInputStream(from)) {
			copy(in, to, name, budget);
		}
	}

	/**
	 * Describes a folder pack's files, each one's path, size and time, so an edited pack is copied again. It meets the
	 * entries the copy meets, charged to {@code budget} the same way, so it keeps at most the budget's entries.
	 *
	 * @throws TooLargeException if the folder is over the budget's entries
	 */
	static String fingerprint(Path data, Budget budget) throws IOException {
		List<String> listing = new ArrayList<>();

		KnowledgeFiles.walkRegularFiles(data, MAX_DEPTH, budget::entry, (file, attributes) ->
			listing.add(data.relativize(file) + "\t" + attributes.size() + "\t" + attributes.lastModifiedTime().toMillis()));

		Collections.sort(listing);
		return KnowledgeFiles.sha256(String.join("\n", listing));
	}

	/** What an earlier copy left under {@code folder} takes. The folder is our own output, so this isn't limited. */
	static Usage measure(Path folder) throws IOException {
		if (!Files.isDirectory(folder)) {
			return Usage.NONE;
		}

		long[] totals = new long[2];
		KnowledgeFiles.walkRegularFiles(folder, Integer.MAX_VALUE, () -> totals[0]++, (file, attributes) -> totals[1] += attributes.size());
		return new Usage((int) Math.min(totals[0], Integer.MAX_VALUE), totals[1]);
	}

	/** Copies one file, counting its bytes as they are read. Never writes through a link at {@code to}. */
	private static void copy(InputStream in, Path to, String name, Budget budget) throws IOException {
		Files.createDirectories(to.getParent());
		budget.requireSpace(to.getParent());

		try (OutputStream out = Files.newOutputStream(to, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
			StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
			byte[] buffer = new byte[64 * 1024];
			long fileBytes = 0;
			int read;

			while ((read = in.read(buffer)) > 0) {
				fileBytes += read;
				budget.bytes(name, fileBytes, read);
				out.write(buffer, 0, read);
			}
		}
	}

	/**
	 * What copies have used so far, checked against their limits. A budget with a parent charges the parent too, so
	 * several copies share the parent's limits: a mod's nested jars share one budget, a world's packs another. Not
	 * thread-safe: one copy at a time.
	 */
	static final class Budget {
		private final Limits limits;
		/** What the limits apply to, for messages: "the pack". */
		private final String what;
		private final Budget parent;
		private int entries;
		private long totalBytes;
		private long nextSpaceCheck;

		Budget(Limits limits, String what) {
			this(limits, what, null);
		}

		Budget(Limits limits, String what, Budget parent) {
			this.limits = limits;
			this.what = what;
			this.parent = parent;
		}

		/** Counts what earlier copies already took against this budget's limits (a world's packs copied before). */
		void charge(Usage used) {
			entries += used.entries();
			totalBytes += used.bytes();
		}

		/** What this budget has been charged, including {@link #charge}. */
		Usage usage() {
			return new Usage(entries, totalBytes);
		}

		/** One more entry met. Also where a copy notices it was cancelled. */
		void entry() throws IOException {
			requireNotInterrupted();
			entries++;

			if (entries > limits.maxEntries()) {
				throw new TooLargeException("more than " + limits.maxEntries() + " entries in " + what + " (the limit)");
			}

			if (parent != null) {
				parent.entry();
			}
		}

		/** {@code read} more bytes of {@code name}, which has {@code fileBytes} so far. */
		void bytes(String name, long fileBytes, int read) throws IOException {
			requireNotInterrupted();
			totalBytes += read;

			if (fileBytes > limits.maxFileBytes()) {
				throw new TooLargeException(name + " is over " + mebibytes(limits.maxFileBytes()) + " uncompressed (the limit)");
			}

			if (totalBytes > limits.maxTotalBytes()) {
				throw new TooLargeException("over " + mebibytes(limits.maxTotalBytes()) + " uncompressed in " + what + " (the limit)");
			}

			if (parent != null) {
				parent.bytes(name, fileBytes, read);
			}
		}

		/** Before writing into {@code dir}: refuses when its disk has less than {@link #MIN_FREE_BYTES} left. */
		void requireSpace(Path dir) throws IOException {
			if (totalBytes < nextSpaceCheck) {
				return;
			}

			nextSpaceCheck = totalBytes + FREE_SPACE_CHECK_BYTES;

			if (Files.getFileStore(dir).getUsableSpace() < MIN_FREE_BYTES) {
				throw new TooLargeException("less than " + mebibytes(MIN_FREE_BYTES) + " is free on the disk");
			}
		}

		private static void requireNotInterrupted() throws InterruptedIOException {
			if (Thread.currentThread().isInterrupted()) {
				throw new InterruptedIOException("the copy was cancelled");
			}
		}

		private static String mebibytes(long bytes) {
			return bytes >= 1 << 20 ? (bytes >> 20) + " MiB" : bytes + " bytes";
		}
	}
}
