package claudechat;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * One folder of the knowledge base that's built in the background and then only read: Minecraft's or a mod's files,
 * or a world data pack's. It owns the folder's layout ({@link #SRC}, {@link #DATA}, {@link #LANG}), the marker that
 * records what was built from which input, the build's progress, and publishing a finished build with a rename, so
 * readers never see half a folder.
 * <p>
 * A folder counts as built only when its marker holds the same key the folder was created with. The key describes
 * the input (versions, file fingerprints), so a changed input is rebuilt even when its folder name stays the same.
 */
final class BuiltFolder {
	/** What a published build holds, in increasing order: code includes data. */
	enum Content {
		NONE, DATA, CODE;

		boolean covers(Content needed) {
			return compareTo(needed) >= 0;
		}
	}

	/** The latest build's progress. FAILED stays until the next build starts. */
	enum Progress {
		IDLE, QUEUED, BUILDING, FAILED;

		boolean busy() {
			return this == QUEUED || this == BUILDING;
		}
	}

	/**
	 * Fills a new build. {@code out} becomes the published folder, and is deleted if the build fails. {@code work} is
	 * empty scratch space, deleted after publishing and kept after a failure, so logs written there can be read.
	 */
	@FunctionalInterface
	interface Contents {
		void write(Path out, Path work) throws IOException, InterruptedException;
	}

	/** Decompiled Java. */
	static final String SRC = "src";
	/** Data pack JSON, as under a jar's data/ folder. */
	static final String DATA = "data";
	/** English translations, one file per namespace. */
	static final String LANG = "lang";

	private static final String CODE_MARKER = ".complete";
	private static final String DATA_MARKER = ".data-complete";
	private static final String PARTIAL_SUFFIX = ".partial";
	private static final String OLD_SUFFIX = ".old";
	private static final String WORK_SUFFIX = ".work";
	/** Folders a build uses beside its own while it runs. */
	private static final List<String> RUNNING_BUILD_SUFFIXES = List.of(PARTIAL_SUFFIX, WORK_SUFFIX);
	/** Folders of ours that hold no finished build. */
	private static final List<String> LEFTOVER_SUFFIXES = List.of(PARTIAL_SUFFIX, WORK_SUFFIX, OLD_SUFFIX);
	private static final int MOVE_ATTEMPTS = 10;
	/** Changing how folders are built changes this, so every existing folder is rebuilt once. */
	private static final String FORMAT = "2";
	/** Deep enough for mods/&lt;id&gt;/&lt;version&gt; and datapacks/&lt;world&gt;/&lt;pack&gt;. */
	private static final int MAX_SWEEP_DEPTH = 3;

	private final Path root;
	private final Path dir;
	private final String relativeDir;
	private final String key;
	private final String marker;
	private final ReentrantLock lock = new ReentrantLock();
	private final AtomicReference<Progress> progress = new AtomicReference<>(Progress.IDLE);
	private volatile Content content;
	private volatile String failure;

	/** Reads what's already built in {@code root/relativeDir}; nothing counts unless its marker holds {@code key}. */
	BuiltFolder(Path root, String relativeDir, String key) {
		this.root = root;
		this.dir = root.resolve(relativeDir).normalize();
		this.relativeDir = relativeDir;
		this.key = key;
		this.marker = FORMAT + ";" + key;
		this.content = readContent();
	}

	Path dir() {
		return dir;
	}

	/** The folder relative to the knowledge root, with forward slashes, as Claude sees it. */
	String relativeDir() {
		return relativeDir;
	}

	/** The input description this folder is built from. */
	String key() {
		return key;
	}

	/** The data/ folder; it exists only when the build found data files. */
	Path data() {
		return dir.resolve(DATA);
	}

	Content content() {
		return content;
	}

	Progress progress() {
		return progress.get();
	}

	/** Why the latest build failed, or null. */
	String failure() {
		return failure;
	}

	/** Marks the folder as waiting for a build. False when a build is already queued or running. */
	boolean queue() {
		Progress before = progress.getAndUpdate(current -> current.busy() ? current : Progress.QUEUED);
		return !before.busy();
	}

	/** A queued build turned out to have nothing to do. */
	void settle() {
		progress.compareAndSet(Progress.QUEUED, Progress.IDLE);
	}

	/** Runs {@code work} holding this folder's lock: one build or change per folder at a time. */
	void locked(Runnable work) {
		lock.lock();

		try {
			work.run();
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Builds the folder from scratch into a partial folder, then publishes it in place of the current one. Must hold
	 * {@link #locked}. On failure the published folder is left as it was, the partial folder is deleted, progress
	 * becomes FAILED, and the exception is rethrown. Refuses to build when a link leads the folder outside the knowledge
	 * root, before deleting or creating anything: everything {@code contents} writes is below the partial and work
	 * folders beside it, in folders this build creates.
	 */
	void build(Content target, Contents contents) throws IOException, InterruptedException {
		requireLock();

		if (target == Content.NONE) {
			throw new IllegalArgumentException("A build must produce data or code");
		}

		progress.set(Progress.BUILDING);
		failure = null;
		Path partial = sibling(PARTIAL_SUFFIX);
		// Beside the partial folder, not in it: Windows won't rename a folder holding files that are still being deleted.
		Path work = sibling(WORK_SUFFIX);
		boolean checked = false;

		try {
			Files.createDirectories(root);
			// The partial and work folders are siblings of the published one, so checking its parent covers all three.
			KnowledgeFiles.requireRealLocationInside(root, dir.getParent());
			checked = true;
			KnowledgeFiles.deleteTree(partial);
			KnowledgeFiles.deleteTree(work);
			Files.createDirectories(partial);
			Files.createDirectories(work);

			contents.write(partial, work);

			Files.writeString(partial.resolve(markerName(target)), marker);
			publish(partial);
			content = target;
			progress.set(Progress.IDLE);
		} catch (IOException | InterruptedException | RuntimeException e) {
			failure = e.getMessage() != null ? e.getMessage() : e.toString();
			progress.set(Progress.FAILED);
			// Whatever was copied before a limit stopped the build: a failed build mustn't keep taking disk space. Only
			// once the location is known to be inside the root, so a refused build deletes nothing.
			if (checked) {
				deleteLeftover(partial);
			}

			throw e;
		}

		deleteLeftover(work);
	}

	/** Removes decompiled code and keeps the data. Must hold {@link #locked}. */
	void dropCode() throws IOException {
		requireLock();

		if (content != Content.CODE) {
			return;
		}

		KnowledgeFiles.requireRealLocationInside(root, dir);
		// Data marker first: an interruption in between leaves both markers, which still reads as the code that's there.
		Files.writeString(dir.resolve(DATA_MARKER), marker);
		Files.delete(dir.resolve(CODE_MARKER));
		content = Content.DATA;
		KnowledgeFiles.deleteTree(dir.resolve(SRC));
	}

	/** Forgets the build so the next request rebuilds it. Skipped while a build is running (it produces a fresh one). */
	void discardUnlessBuilding() {
		if (!lock.tryLock()) {
			return;
		}

		try {
			Files.deleteIfExists(dir.resolve(CODE_MARKER));
			Files.deleteIfExists(dir.resolve(DATA_MARKER));
			content = Content.NONE;
		} catch (IOException e) {
			ClaudeChatClient.LOGGER.warn("Could not reset {}", relativeDir, e);
		} finally {
			lock.unlock();
		}
	}

	private void requireLock() {
		if (!lock.isHeldByCurrentThread()) {
			throw new IllegalStateException("Changing " + relativeDir + " without holding its lock");
		}
	}

	private Content readContent() {
		if (markerMatches(CODE_MARKER)) {
			return Content.CODE;
		}

		return markerMatches(DATA_MARKER) ? Content.DATA : Content.NONE;
	}

	private boolean markerMatches(String name) {
		try {
			Path file = dir.resolve(name);
			return Files.isRegularFile(file) && Files.readString(file).equals(marker);
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * Moves the old folder aside, moves the new one in, then deletes the old one. Renames are atomic, so a reader sees
	 * the old folder, briefly nothing, or the new one; never a mix.
	 */
	private void publish(Path partial) throws IOException, InterruptedException {
		Path old = sibling(OLD_SUFFIX);
		KnowledgeFiles.deleteTree(old);
		Files.createDirectories(dir.getParent());

		if (Files.exists(dir)) {
			rename(dir, old);
		}

		try {
			rename(partial, dir);
		} catch (IOException | InterruptedException e) {
			if (Files.exists(old)) {
				Files.move(old, dir, StandardCopyOption.ATOMIC_MOVE);
			}

			throw e;
		}

		deleteLeftover(old);
	}

	/**
	 * An atomic rename, retried for a moment when access is denied: on Windows an antivirus scanner or the search
	 * indexer briefly opening a new file inside a folder is enough to block renaming it.
	 */
	private static void rename(Path from, Path to) throws IOException, InterruptedException {
		for (int attempt = 1; ; attempt++) {
			try {
				Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
				return;
			} catch (AccessDeniedException e) {
				if (attempt == MOVE_ATTEMPTS) {
					throw e;
				}

				Thread.sleep(100L * attempt);
			}
		}
	}

	private void deleteLeftover(Path folder) {
		try {
			KnowledgeFiles.deleteTree(folder);
		} catch (IOException e) {
			// Usually a file Claude or a scanner has open. The next build or sweep removes it.
			ClaudeChatClient.LOGGER.warn("Could not delete {}", folder, e);
		}
	}

	private Path sibling(String suffix) {
		return dir.resolveSibling(dir.getFileName() + suffix);
	}

	private static String markerName(Content content) {
		return content == Content.CODE ? CODE_MARKER : DATA_MARKER;
	}

	/**
	 * Deletes built folders under {@code root} that aren't in {@code wanted}: those with a marker, and leftovers of
	 * builds (a wanted folder's partial and work folders are kept, since its build may be running). Folders without a marker
	 * aren't ours and are left alone, as is anything deeper than a built folder can be. Folders emptied this way are
	 * removed too. Links, Windows junctions included, are neither entered nor deleted, so nothing outside {@code root}
	 * is touched.
	 */
	static void removeUnwanted(Path root, Set<Path> wanted) {
		Path normalizedRoot = root.normalize();

		if (!Files.isDirectory(normalizedRoot)) {
			return;
		}

		List<Path> unwanted = new ArrayList<>();
		collectUnwanted(normalizedRoot, 1, wanted, unwanted);

		for (Path folder : unwanted) {
			try {
				KnowledgeFiles.deleteTree(folder);
				deleteEmptyParents(folder.getParent(), normalizedRoot);
				ClaudeChatClient.LOGGER.info("Claude knowledge base: removed superseded {}", normalizedRoot.relativize(folder));
			} catch (IOException e) {
				ClaudeChatClient.LOGGER.warn("Could not remove superseded {}", folder, e);
			}
		}
	}

	private static void collectUnwanted(Path dir, int depth, Set<Path> wanted, List<Path> unwanted) {
		List<Path> children;

		try (Stream<Path> list = Files.list(dir)) {
			children = list.filter(KnowledgeFiles::isPlainDirectory).toList();
		} catch (IOException e) {
			ClaudeChatClient.LOGGER.warn("Could not list {}", dir, e);
			return;
		}

		for (Path child : children) {
			if (isWanted(child, wanted)) {
				continue;
			}

			if (isBuiltFolder(child)) {
				unwanted.add(child);
			} else if (depth < MAX_SWEEP_DEPTH) {
				collectUnwanted(child, depth + 1, wanted, unwanted);
			}
		}
	}

	/** A wanted folder, or the partial or work folder of a wanted folder's build, which may be running. */
	private static boolean isWanted(Path folder, Set<Path> wanted) {
		String name = folder.getFileName().toString();

		for (String suffix : RUNNING_BUILD_SUFFIXES) {
			if (name.endsWith(suffix)) {
				String owner = name.substring(0, name.length() - suffix.length());
				return wanted.contains(folder.resolveSibling(owner));
			}
		}

		return wanted.contains(folder);
	}

	private static boolean isBuiltFolder(Path folder) {
		String name = folder.getFileName().toString();
		boolean leftover = LEFTOVER_SUFFIXES.stream().anyMatch(name::endsWith);

		return leftover || Files.exists(folder.resolve(CODE_MARKER)) || Files.exists(folder.resolve(DATA_MARKER));
	}

	private static void deleteEmptyParents(Path dir, Path root) throws IOException {
		Path current = dir;

		while (current != null && !current.equals(root) && current.startsWith(root) && KnowledgeFiles.isEmptyDirectory(current)) {
			Files.delete(current);
			current = current.getParent();
		}
	}
}
