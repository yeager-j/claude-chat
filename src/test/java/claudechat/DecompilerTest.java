package claudechat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Decompiler#run} with a stand-in decompiler: a JVM that sleeps, optionally after starting another. */
class DecompilerTest {
	private static final String SLEEPER = """
		public class Sleeper {
			public static void main(String[] args) throws Exception {
				if (args.length > 0) {
					new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(), args[0]).inheritIO().start();
				}

				Thread.sleep(600_000);
			}
		}
		""";

	@Test
	void anInterruptKillsTheDecompilerAndWhatItStarted(@TempDir Path dir) throws Exception {
		Set<ProcessHandle> before = descendants();
		Path sleeper = sleeper(dir);
		AtomicInteger interrupted = new AtomicInteger();
		Thread caller = call(List.of(java(), sleeper.toString(), sleeper.toString()), dir, interrupted);

		try {
			List<ProcessHandle> started = waitForNew(before, 2);
			caller.interrupt();
			caller.join(30_000);

			assertFalse(caller.isAlive());
			assertEquals(1, interrupted.get(), "the interrupt reaches the caller");
			started.forEach(process -> assertFalse(process.isAlive(), "pid " + process.pid() + " still runs"));
		} finally {
			killNew(before);
		}
	}

	@Test
	void onlyMaxRunningDecompilersRunAtOnce(@TempDir Path dir) throws Exception {
		Set<ProcessHandle> before = descendants();
		Path sleeper = sleeper(dir);
		AtomicInteger interrupted = new AtomicInteger();
		List<Thread> callers = new ArrayList<>();

		try {
			for (int i = 0; i < Decompiler.MAX_RUNNING + 2; i++) {
				callers.add(call(List.of(java(), sleeper.toString()), dir, interrupted));
			}

			List<ProcessHandle> started = waitForNew(before, Decompiler.MAX_RUNNING);
			Thread.sleep(2_000);
			assertEquals(Decompiler.MAX_RUNNING, newDescendants(before).size());

			callers.forEach(Thread::interrupt);
			for (Thread caller : callers) {
				caller.join(30_000);
				assertFalse(caller.isAlive());
			}

			assertEquals(callers.size(), interrupted.get());
			started.forEach(process -> assertFalse(process.isAlive(), "pid " + process.pid() + " still runs"));
			assertTrue(newDescendants(before).isEmpty(), "a waiting call started a decompiler after its interrupt");
		} finally {
			callers.forEach(Thread::interrupt);
			killNew(before);
		}
	}

	private static Thread call(List<String> command, Path dir, AtomicInteger interrupted) {
		return Thread.ofPlatform().start(() -> {
			try {
				Decompiler.run(command, dir.resolve("decompile.log"), dir.resolve("input.jar"));
			} catch (InterruptedException e) {
				interrupted.incrementAndGet();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
	}

	private static Path sleeper(Path dir) throws IOException {
		Path source = dir.resolve("Sleeper.java");
		Files.writeString(source, SLEEPER);
		return source;
	}

	private static String java() {
		return ProcessHandle.current().info().command().orElseThrow();
	}

	/** Waits (up to a minute: each JVM compiles its source first) until {@code count} processes have started. */
	private static List<ProcessHandle> waitForNew(Set<ProcessHandle> before, int count) throws InterruptedException {
		long deadline = System.nanoTime() + 60_000_000_000L;

		while (System.nanoTime() < deadline) {
			List<ProcessHandle> started = newDescendants(before);

			if (started.size() >= count) {
				return started;
			}

			Thread.sleep(100);
		}

		throw new AssertionError("only " + newDescendants(before).size() + " of " + count + " processes started");
	}

	private static List<ProcessHandle> newDescendants(Set<ProcessHandle> before) {
		return descendants().stream().filter(process -> !before.contains(process)).toList();
	}

	private static Set<ProcessHandle> descendants() {
		return ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).collect(Collectors.toSet());
	}

	private static void killNew(Set<ProcessHandle> before) {
		newDescendants(before).forEach(ProcessHandle::destroyForcibly);
	}
}
