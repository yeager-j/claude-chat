package claudechat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * File helpers for the knowledge base. It copies files whose names come from mods and data packs, so every path it
 * builds from such a name goes through {@link #resolveInside} and can't land outside the folder it's meant for. Its
 * walks never enter links ({@link #isLink}), so copying can't pull in files from elsewhere and deleting can't reach
 * through to them.
 */
final class KnowledgeFiles {
	private static final Pattern UNSAFE_CHARACTERS = Pattern.compile("[^A-Za-z0-9._+-]");

	private KnowledgeFiles() {
	}

	/** {@code base/relative}, or empty when {@code relative} would leave {@code base} (or name {@code base} itself). */
	static Optional<Path> resolveInside(Path base, String relative) {
		Path normalizedBase = base.normalize();
		Path target = normalizedBase.resolve(relative).normalize();
		boolean inside = target.startsWith(normalizedBase) && !target.equals(normalizedBase);

		return inside ? Optional.of(target) : Optional.empty();
	}

	/**
	 * Copies the regular files under {@code from} that {@code include} accepts to the same relative paths under
	 * {@code to}, replacing existing files. {@code from} may be on another file system (a zip, a nested jar). Links
	 * aren't followed (see {@link #regularFiles}), and files whose path would leave {@code to} are skipped. Does nothing
	 * when {@code from} isn't a folder.
	 */
	static void copyTree(Path from, Path to, Predicate<Path> include) throws IOException {
		if (!Files.isDirectory(from)) {
			return;
		}

		for (Path file : regularFiles(from)) {
			if (!include.test(file)) {
				continue;
			}

			Optional<Path> target = resolveInside(to, from.relativize(file).toString());

			if (target.isPresent()) {
				copyFile(file, target.get());
			}
		}
	}

	/** Copies one file, creating the target's folders and replacing an existing file (a link there is replaced, not followed). */
	static void copyFile(Path from, Path to) throws IOException {
		Files.createDirectories(to.getParent());
		Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
	}

	/**
	 * The regular files under {@code root}, without entering links (see {@link #isLink}): a link could lead anywhere,
	 * and these files are copied into the knowledge base for Claude to read. Links skipped are logged, once per call.
	 */
	static List<Path> regularFiles(Path root) throws IOException {
		List<Path> files = new ArrayList<>();
		List<Path> links = new ArrayList<>();

		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
				if (isLink(dir, attributes)) {
					links.add(dir);
					return FileVisitResult.SKIP_SUBTREE;
				}

				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
				if (isLink(file, attributes)) {
					links.add(file);
				} else if (attributes.isRegularFile()) {
					files.add(file);
				}

				return FileVisitResult.CONTINUE;
			}
		});

		if (!links.isEmpty()) {
			ClaudeChatClient.LOGGER.info("Not following {} link(s) under {}, such as {}", links.size(), root, links.getFirst());
		}

		return files;
	}

	/**
	 * Deletes a folder and everything in it, or a single file. Does nothing when it doesn't exist. A link, inside or
	 * given, is deleted as a link: nothing it leads to is touched.
	 */
	static void deleteTree(Path path) throws IOException {
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}

		Files.walkFileTree(path, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
				if (isLink(dir, attributes)) {
					Files.delete(dir);
					return FileVisitResult.SKIP_SUBTREE;
				}

				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
				if (failure != null) {
					throw failure;
				}

				Files.delete(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/**
	 * Deletes the empty folders below {@code dir}, deepest first, so folders that held only empty folders go too. Links
	 * aren't entered.
	 */
	static void pruneEmptyDirectories(Path dir) throws IOException {
		Files.walkFileTree(dir, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path folder, BasicFileAttributes attributes) {
				return isLink(folder, attributes) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path folder, IOException failure) throws IOException {
				if (failure != null) {
					throw failure;
				}

				if (!folder.equals(dir) && isEmptyDirectory(folder)) {
					Files.delete(folder);
				}

				return FileVisitResult.CONTINUE;
			}
		});
	}

	/**
	 * Whether a path, with attributes read without following links, leads elsewhere: a symbolic link, or a Windows
	 * junction. Java reports a junction (like every other Windows reparse point, such as a cloud-sync placeholder) as
	 * "other" rather than as a link, and walks into it; a junction's real location is elsewhere, while a placeholder's
	 * is itself. Hard links can't be told apart from ordinary files.
	 */
	static boolean isLink(Path path, BasicFileAttributes attributes) {
		if (attributes.isSymbolicLink()) {
			return true;
		}

		if (!attributes.isOther()) {
			return false;
		}

		try {
			Path absolute = path.toAbsolutePath();
			return !absolute.toRealPath().equals(absolute.getParent().toRealPath().resolve(absolute.getFileName()));
		} catch (IOException e) {
			// A junction whose target is gone.
			return true;
		}
	}

	/** A folder that isn't a link (see {@link #isLink}); false when it can't be checked. */
	static boolean isPlainDirectory(Path path) {
		try {
			BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			return attributes.isDirectory() && !isLink(path, attributes);
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * Checks, before writing or deleting at {@code path}, that its real location is inside {@code root}'s: lexical
	 * checks such as {@link #resolveInside} can't see a link on the way. {@code path} need not exist yet; its nearest
	 * existing folder is checked, so creating the rest can't leave the root. {@code root} must exist.
	 *
	 * @throws IOException if a link leads {@code path} outside {@code root}
	 */
	static void requireRealLocationInside(Path root, Path path) throws IOException {
		Path realRoot = root.toRealPath();
		Path existing = path.toAbsolutePath().normalize();

		while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
			existing = existing.getParent();
		}

		Path real = existing == null ? null : existing.toRealPath();

		if (real == null || !real.startsWith(realRoot)) {
			throw new IOException("Refusing to write to " + path + ": a link leads it outside " + root + (real == null ? "" : ", to " + real));
		}
	}

	static boolean isEmptyDirectory(Path dir) throws IOException {
		try (Stream<Path> children = Files.list(dir)) {
			return children.findAny().isEmpty();
		}
	}

	/**
	 * A folder name for {@code raw} (a mod id, version, pack or world name). Different values always get different
	 * names: a value that's already a plain name is used as is, and any other gets its unsafe characters replaced plus
	 * a hash of the original after a {@code ~}, which plain names can't contain.
	 */
	static String folderName(String raw) {
		String safe = UNSAFE_CHARACTERS.matcher(raw).replaceAll("_");
		// A leading dot would make "." or "..", and Windows drops a trailing dot, so neither is plain.
		boolean plain = !raw.isEmpty() && safe.equals(raw) && !raw.startsWith(".") && !raw.endsWith(".");

		return plain ? raw : safe + "~" + sha256(raw).substring(0, 8);
	}

	/** Hex SHA-256 of a string's UTF-8 bytes. */
	static String sha256(String text) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("Every Java runtime has SHA-256", e);
		}
	}
}
