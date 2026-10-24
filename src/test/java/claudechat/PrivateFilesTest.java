package claudechat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A question's folder and its files, which carry the question's token, are open to the player's account alone. */
class PrivateFilesTest {
	@Test
	void onlyTheOwnerHasAccess(@TempDir Path dir) throws IOException {
		Path runs = dir.resolve("runs");
		// An older version made runs with default permissions.
		Files.createDirectory(runs);

		Path run = PrivateFiles.createDir(runs, "1-1-abc");
		Path file = run.resolve("mcp.json");
		PrivateFiles.write(file, "{\"token\": \"secret\"}");

		assertEquals("{\"token\": \"secret\"}", Files.readString(file));
		assertOwnerOnly(runs, "rwx------");
		assertOwnerOnly(run, "rwx------");
		assertOwnerOnly(file, "rw-------");
	}

	/** A file or folder someone made ahead of time is never written into or reused. */
	@Test
	void neverReusesAnExistingName(@TempDir Path dir) throws IOException {
		Path run = PrivateFiles.createDir(dir.resolve("runs"), "1-1-abc");
		Path file = run.resolve("mcp.json");
		Files.writeString(file, "planted");

		assertThrows(FileAlreadyExistsException.class, () -> PrivateFiles.write(file, "secret"));
		assertEquals("planted", Files.readString(file));
		assertThrows(FileAlreadyExistsException.class, () -> PrivateFiles.createDir(dir.resolve("runs"), "1-1-abc"));
	}

	private static void assertOwnerOnly(Path path, String posix) throws IOException {
		Set<String> views = path.getFileSystem().supportedFileAttributeViews();
		if (views.contains("posix")) {
			assertEquals(posix, PosixFilePermissions.toString(Files.getPosixFilePermissions(path)));
		} else {
			AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
			List<AclEntry> acl = view.getAcl();
			assertEquals(1, acl.size(), acl.toString());
			assertEquals(view.getOwner(), acl.getFirst().principal());
		}
	}
}
