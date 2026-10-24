package claudechat;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Ending a question's process tree leaves nothing it started running: not a child still attached, and on Windows (the
 * job object) not one whose parent already exited either.
 */
class ProcessTreeTest {
	@Test
	void closingKillsTheProcessAndItsChildren() throws Exception {
		try (ProcessTree tree = ProcessTree.start(helper("stay"))) {
			ProcessHandle child = childOf(tree.process());
			assertTrue(child.isAlive());

			tree.close();

			assertTrue(tree.process().waitFor(5, TimeUnit.SECONDS));
			assertExits(child);
		}
	}

	/** A helper whose parent exited is no longer a descendant; only the job still holds it. */
	@Test
	void closingKillsAChildWhoseParentExited() throws Exception {
		assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows"), "needs a job object");
		try (ProcessTree tree = ProcessTree.start(helper("leave"))) {
			ProcessHandle child = childOf(tree.process());
			assertTrue(tree.process().waitFor(10, TimeUnit.SECONDS));
			assertTrue(child.isAlive());

			tree.close();

			assertExits(child);
		}
	}

	private static void assertExits(ProcessHandle process) throws Exception {
		process.onExit().get(5, TimeUnit.SECONDS);
		assertFalse(process.isAlive());
	}

	/** The child the helper started, by the pid it printed. */
	private static ProcessHandle childOf(Process helper) throws IOException {
		BufferedReader out = new BufferedReader(new InputStreamReader(helper.getInputStream(), StandardCharsets.UTF_8));
		long pid = Long.parseLong(out.readLine().strip());
		return ProcessHandle.of(pid).orElseThrow();
	}

	private static ProcessBuilder helper(String mode) {
		return new ProcessBuilder(javaCommand(Helper.class.getName(), mode)).redirectErrorStream(true);
	}

	private static List<String> javaCommand(String... args) {
		String java = ProcessHandle.current().info().command().orElseThrow();
		List<String> command = new java.util.ArrayList<>(List.of(java, "-cp", System.getProperty("java.class.path")));
		command.addAll(List.of(args));
		return command;
	}

	/**
	 * {@code stay}: starts a child, prints its pid and waits. {@code leave}: the same, then exits, leaving the child.
	 * {@code child}: waits.
	 */
	public static final class Helper {
		public static void main(String[] args) throws Exception {
			if (!args[0].equals("child")) {
				Process child = new ProcessBuilder(javaCommand(Helper.class.getName(), "child"))
					.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectErrorStream(true).start();
				System.out.println(child.pid());
				System.out.flush();
				if (args[0].equals("leave")) {
					System.exit(0);
				}
			}
			Thread.sleep(60_000);
		}
	}
}
