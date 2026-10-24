package claudechat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Folders and files only the player's account can open, for what a question must put on disk: its MCP config, which
 * holds the question's token, and its system prompt, which holds the game state. Each question gets a folder of its
 * own, inside a runs folder that is private too, so no other account can open a file in it, swap the folder for one of
 * its own, or guess a name ahead of time.
 * <p>
 * POSIX file systems get owner-only modes, set when each folder and file is created. Windows gets an access list with a
 * single entry, for the owner, set on each folder while it's still empty (files created inside inherit it) and on each
 * file before anything is written to it. Every permission is read back before a byte is written, and a file system
 * with neither kind of permission is refused: a token mustn't be written somewhere another account may read it.
 */
final class PrivateFiles {
	private static final Set<PosixFilePermission> OWNER_ONLY_DIR = PosixFilePermissions.fromString("rwx------");
	private static final Set<PosixFilePermission> OWNER_ONLY_FILE = PosixFilePermissions.fromString("rw-------");

	private PrivateFiles() {
	}

	/**
	 * Creates the folder {@code name} inside {@code parent}, both private to the player's account. {@code parent} may
	 * already exist (an older version made it with default permissions) but must belong to the same account; the new
	 * folder mustn't exist yet.
	 */
	static Path createDir(Path parent, String name) throws IOException {
		requireOwnerOnlyPermissions(parent);
		Files.createDirectories(parent);
		Path dir = parent.resolve(name);
		if (posix(dir)) {
			Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIR));
		} else {
			Files.createDirectory(dir);
		}

		// Whoever creates a file is its owner, so the new folder says which account "the player's" is.
		UserPrincipal player = Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS);
		for (Path folder : List.of(parent, dir)) {
			BasicFileAttributes attributes = Files.readAttributes(folder, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			if (KnowledgeFiles.isLink(folder, attributes) || !attributes.isDirectory()) {
				throw new IOException(folder + " isn't a folder.");
			}
			if (!Files.getOwner(folder, LinkOption.NOFOLLOW_LINKS).equals(player)) {
				throw new IOException(folder + " belongs to another account, so Claude Chat won't keep a question's files there.");
			}

			restrict(folder, true);
		}

		return dir;
	}

	/**
	 * Writes {@code text} to a new file, readable only by its owner, in a folder from {@link #createDir}. The file must
	 * not exist yet; its permissions are checked before the text is written.
	 */
	static void write(Path file, String text) throws IOException {
		requireOwnerOnlyPermissions(file);
		Set<StandardOpenOption> options = EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		FileAttribute<?>[] attributes = posix(file)
			? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE)}
			: new FileAttribute<?>[0];
		try (SeekableByteChannel channel = Files.newByteChannel(file, options, attributes)) {
			restrict(file, false);
			ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
			while (bytes.hasRemaining()) {
				channel.write(bytes);
			}
		}
	}

	/** Leaves only the owner able to open {@code path}, and checks that it did. */
	private static void restrict(Path path, boolean folder) throws IOException {
		if (posix(path)) {
			Set<PosixFilePermission> ownerOnly = folder ? OWNER_ONLY_DIR : OWNER_ONLY_FILE;
			Files.setPosixFilePermissions(path, ownerOnly);
			if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(ownerOnly)) {
				throw notPrivate(path);
			}
			return;
		}

		AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		UserPrincipal owner = view.getOwner();
		AclEntry.Builder entry = AclEntry.newBuilder()
			.setType(AclEntryType.ALLOW)
			.setPrincipal(owner)
			.setPermissions(EnumSet.allOf(AclEntryPermission.class));
		if (folder) {
			// Files created inside start out private too.
			entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
		}
		view.setAcl(List.of(entry.build()));

		List<AclEntry> acl = view.getAcl();
		if (acl.size() != 1 || acl.getFirst().type() != AclEntryType.ALLOW || !acl.getFirst().principal().equals(owner)) {
			throw notPrivate(path);
		}
	}

	private static boolean posix(Path path) {
		return path.getFileSystem().supportedFileAttributeViews().contains("posix");
	}

	private static void requireOwnerOnlyPermissions(Path path) throws IOException {
		Set<String> views = path.getFileSystem().supportedFileAttributeViews();
		if (!views.contains("posix") && !views.contains("acl")) {
			throw new IOException("The file system of " + path + " can't keep a file private to your account,"
				+ " so Claude Chat won't write a question's files there.");
		}
	}

	private static IOException notPrivate(Path path) {
		return new IOException("Couldn't make " + path + " private to your account.");
	}
}
