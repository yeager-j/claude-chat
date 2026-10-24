package claudechat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** What an approval request says about a path, worked out from its text alone. */
class ApprovalsTest {
	@Test
	void networkAndDevicePathsAreFlagged() {
		for (String path : new String[] {"\\\\attacker\\share\\x.txt", "//attacker/share/x.txt", "\\\\?\\C:\\x.txt",
			"\\\\.\\pipe\\x", " \\\\host\\s\\x", "\\/host/s/x", "/\\host\\s\\x"}) {
			assertNotNull(Approvals.pathWarning(path), path);
		}
	}

	@Test
	void localPathsAreNot() {
		for (String path : new String[] {"C:\\Users\\me\\x.txt", "config/claudechat.properties", "/home/me/x.txt", "x.txt"}) {
			assertNull(Approvals.pathWarning(path), path);
		}
	}
}
