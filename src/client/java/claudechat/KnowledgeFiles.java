package claudechat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * File helpers for the knowledge base. It copies files whose names come from mods and data packs, so every path it
 * builds from such a name goes through {@link #resolveInside} and can't land outside the folder it's meant for.
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
	 * {@code to}, replacing existing files. {@code from} may be on another file system (a zip, a nested jar). Files
	 * whose path would leave {@code to} are skipped. Does nothing when {@code from} isn't a folder.
	 */
	static void copyTree(Path from, Path to, Predicate<Path> include) throws IOException {
		if (!Files.isDirectory(from)) {
			return;
		}

		try (Stream<Path> files = Files.walk(from)) {
			for (Path file : files.filter(Files::isRegularFile).filter(include).toList()) {
				Optional<Path> target = resolveInside(to, from.relativize(file).toString());

				if (target.isPresent()) {
					copyFile(file, target.get());
				}
			}
		}
	}

	/** Copies one file, creating the target's folders and replacing an existing file. */
	static void copyFile(Path from, Path to) throws IOException {
		Files.createDirectories(to.getParent());
		Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
	}

	/** Deletes a folder and everything in it, or a single file. Does nothing when it doesn't exist. */
	static void deleteTree(Path path) throws IOException {
		if (!Files.exists(path)) {
			return;
		}

		// Walking doesn't follow symbolic links, so a link inside is deleted as a link and nothing it points to is touched.
		try (Stream<Path> paths = Files.walk(path)) {
			for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(entry);
			}
		}
	}

	/** Deletes the empty folders below {@code dir}, deepest first, so folders that held only empty folders go too. */
	static void pruneEmptyDirectories(Path dir) throws IOException {
		try (Stream<Path> paths = Files.walk(dir)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				if (!path.equals(dir) && Files.isDirectory(path) && isEmptyDirectory(path)) {
					Files.delete(path);
				}
			}
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
