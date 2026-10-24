package claudechat;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Links for tests of link-safe file handling. A symbolic link needs Developer Mode or administrator rights on Windows,
 * so tests that need one are skipped (not failed) without them; a directory junction needs neither, but exists only
 * on Windows.
 */
final class FileLinks {
	private FileLinks() {
	}

	static boolean onWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
	}

	/** A symbolic link at {@code link} to {@code target}; skips the test when this system won't create one. */
	static Path symbolicLink(Path link, Path target) throws IOException {
		try {
			return Files.createSymbolicLink(link, target);
		} catch (FileSystemException | UnsupportedOperationException e) {
			Assumptions.abort("Can't create symbolic links here (on Windows they need Developer Mode): " + e.getMessage());
			throw new AssertionError(e);
		}
	}

	/** A Windows directory junction at {@code link} to the folder {@code target}; skips the test elsewhere. */
	static Path junction(Path link, Path target) throws IOException, InterruptedException {
		Assumptions.assumeTrue(onWindows(), "Junctions exist only on Windows");
		Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
			.redirectErrorStream(true)
			.start();
		String output = new String(mklink.getInputStream().readAllBytes());

		if (!mklink.waitFor(30, TimeUnit.SECONDS) || mklink.exitValue() != 0) {
			throw new IOException("mklink /J failed: " + output);
		}

		return link;
	}
}
