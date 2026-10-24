package claudechat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The MCP config file, which carries a question's token, is readable by the player's account alone. */
class OwnerOnlyFileTest {
	@Test
	void onlyTheOwnerHasAccess(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("mcp.json");
		Files.writeString(file, "stale");

		ClaudeCli.writeOwnerOnly(file, "{\"token\": \"secret\"}");

		assertEquals("{\"token\": \"secret\"}", Files.readString(file));
		Set<String> views = file.getFileSystem().supportedFileAttributeViews();
		if (views.contains("posix")) {
			assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
		} else {
			AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
			List<AclEntry> acl = view.getAcl();
			assertEquals(1, acl.size(), acl.toString());
			assertEquals(view.getOwner(), acl.getFirst().principal());
		}
	}
}
