package claudechat;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * A process and everything it starts, ended together by {@link #close}: a question's claude and the shell commands it
 * runs. Killing claude alone would leave those running with the player's authority after the question ended.
 * <p>
 * On Windows the process goes into a job object that kills every process in it when it's closed. Processes started
 * inside a job stay in it (breaking away isn't allowed), even once their parent exits, so a helper that detached is
 * killed too, and so is everything if the game itself dies. Only something started in the moment between the process
 * starting and joining the job escapes, which is before claude has read its question.
 * <p>
 * Elsewhere {@link #close} stops the tree from growing before killing it: it suspends each descendant it finds (a
 * suspended process can't start another), looks again for children started meanwhile, and only kills once a pass
 * finds nothing new. A process that already left the tree (a daemon that forked twice) is no longer a descendant and
 * isn't found. On Windows without a job (it couldn't be made), the tree is listed once and killed.
 */
final class ProcessTree implements AutoCloseable {
	private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
	/** How many times to look for children started while suspending; each pass suspends everything it found. */
	private static final int MAX_PASSES = 20;
	/** How long {@code kill -STOP} may take. */
	private static final long SUSPEND_WAIT_MILLIS = 2_000;

	private final Process process;
	private final Job job;
	private boolean closed;

	private ProcessTree(Process process, Job job) {
		this.process = process;
		this.job = job;
	}

	/** Starts {@code builder}'s process as the root of a tree. */
	static ProcessTree start(ProcessBuilder builder) throws IOException {
		Process process = builder.start();
		Job job = null;
		if (WINDOWS) {
			try {
				job = Job.containing(process);
			} catch (Throwable e) {
				ClaudeChatClient.LOGGER.warn("Couldn't put Claude Code in a job object; its processes will be killed one by one", e);
			}
		}
		return new ProcessTree(process, job);
	}

	Process process() {
		return process;
	}

	/** Kills the process and everything it started. Safe to call more than once, from any thread. */
	@Override
	public synchronized void close() {
		if (closed) {
			return;
		}

		closed = true;
		if (job != null) {
			job.close();
		}
		// Also without a job: the process may have started something before it joined.
		killQuiescent(process.toHandle());
	}

	/** Suspends the tree under {@code root} until it stops growing, then kills all of it. */
	static void killQuiescent(ProcessHandle root) {
		Set<ProcessHandle> found = new LinkedHashSet<>();
		Set<ProcessHandle> suspended = new HashSet<>();
		// The root first, so it starts nothing more while its descendants are listed.
		suspend(List.of(root), suspended);
		found.add(root);
		for (int pass = 0; pass < MAX_PASSES; pass++) {
			List<ProcessHandle> fresh = new ArrayList<>();
			for (ProcessHandle known : List.copyOf(found)) {
				known.descendants().filter(found::add).forEach(fresh::add);
			}
			if (fresh.isEmpty()) {
				break;
			}

			suspend(fresh, suspended);
		}

		// Not waited for: a cancel comes from the client thread, and a killed process can't start anything.
		found.forEach(ProcessHandle::destroyForcibly);
	}

	/**
	 * Stops processes from running, so they can't start more, where the platform allows it without native code
	 * (POSIX {@code kill -STOP}). On Windows the job does that work.
	 */
	private static void suspend(List<ProcessHandle> processes, Set<ProcessHandle> suspended) {
		List<ProcessHandle> alive = processes.stream().filter(ProcessHandle::isAlive).filter(suspended::add).toList();
		if (alive.isEmpty()) {
			return;
		}

		if (WINDOWS) {
			// Without the job there's no way to suspend; the passes just list the tree as it is, which is then killed.
			return;
		}

		List<String> command = new ArrayList<>(List.of("kill", "-STOP"));
		alive.forEach(handle -> command.add(Long.toString(handle.pid())));
		try {
			new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.start().waitFor(SUSPEND_WAIT_MILLIS, TimeUnit.MILLISECONDS);
		} catch (IOException e) {
			ClaudeChatClient.LOGGER.warn("Couldn't suspend processes {}", command, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * A Windows job object that kills its processes when its last handle closes, through kernel32 with the Foreign
	 * Function API (no native library of our own).
	 */
	private static final class Job {
		private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9;
		private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000;
		/** JOBOBJECT_EXTENDED_LIMIT_INFORMATION on 64-bit Windows, the only Windows Java 25 runs on. */
		private static final long LIMIT_INFORMATION_SIZE = 144;
		/** Its BasicLimitInformation.LimitFlags. */
		private static final long LIMIT_FLAGS_OFFSET = 16;
		private static final int PROCESS_TERMINATE = 0x0001;
		private static final int PROCESS_SET_QUOTA = 0x0100;

		private static MethodHandle createJobObject;
		private static MethodHandle setInformationJobObject;
		private static MethodHandle assignProcessToJobObject;
		private static MethodHandle terminateJobObject;
		private static MethodHandle openProcess;
		private static MethodHandle closeHandle;

		private final MemorySegment handle;
		private boolean closed;

		private Job(MemorySegment handle) {
			this.handle = handle;
		}

		private static synchronized void link() {
			if (closeHandle != null) {
				return;
			}

			Linker linker = Linker.nativeLinker();
			SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
			ValueLayout pointer = ValueLayout.ADDRESS;
			ValueLayout dword = ValueLayout.JAVA_INT;
			createJobObject = linker.downcallHandle(kernel32.find("CreateJobObjectW").orElseThrow(),
				FunctionDescriptor.of(pointer, pointer, pointer));
			setInformationJobObject = linker.downcallHandle(kernel32.find("SetInformationJobObject").orElseThrow(),
				FunctionDescriptor.of(dword, pointer, dword, pointer, dword));
			assignProcessToJobObject = linker.downcallHandle(kernel32.find("AssignProcessToJobObject").orElseThrow(),
				FunctionDescriptor.of(dword, pointer, pointer));
			terminateJobObject = linker.downcallHandle(kernel32.find("TerminateJobObject").orElseThrow(),
				FunctionDescriptor.of(dword, pointer, dword));
			openProcess = linker.downcallHandle(kernel32.find("OpenProcess").orElseThrow(),
				FunctionDescriptor.of(pointer, dword, dword, dword));
			closeHandle = linker.downcallHandle(kernel32.find("CloseHandle").orElseThrow(),
				FunctionDescriptor.of(dword, pointer));
		}

		/** A new job holding {@code process}; anything it starts from now on joins too. */
		static Job containing(Process process) throws Throwable {
			link();
			MemorySegment job = (MemorySegment) createJobObject.invokeExact(MemorySegment.NULL, MemorySegment.NULL);
			if (job.address() == 0) {
				throw new IOException("CreateJobObject failed");
			}

			try (Arena arena = Arena.ofConfined()) {
				MemorySegment limits = arena.allocate(LIMIT_INFORMATION_SIZE, 8);
				limits.set(ValueLayout.JAVA_INT, LIMIT_FLAGS_OFFSET, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE);
				if ((int) setInformationJobObject.invokeExact(job, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, limits,
					(int) LIMIT_INFORMATION_SIZE) == 0) {
					closeQuietly(job);
					throw new IOException("SetInformationJobObject failed");
				}
			}

			MemorySegment target = (MemorySegment) openProcess.invokeExact(PROCESS_SET_QUOTA | PROCESS_TERMINATE, 0, (int) process.pid());
			if (target.address() == 0) {
				closeQuietly(job);
				throw new IOException("OpenProcess failed");
			}

			try {
				if ((int) assignProcessToJobObject.invokeExact(job, target) == 0) {
					closeQuietly(job);
					throw new IOException("AssignProcessToJobObject failed");
				}
			} finally {
				closeQuietly(target);
			}

			return new Job(job);
		}

		/** Kills every process in the job, then closes it. */
		synchronized void close() {
			if (closed) {
				return;
			}

			closed = true;
			try {
				int ignored = (int) terminateJobObject.invokeExact(handle, 1);
			} catch (Throwable e) {
				ClaudeChatClient.LOGGER.warn("TerminateJobObject failed", e);
			}
			// Closing the last handle kills anything left, too (KILL_ON_JOB_CLOSE).
			closeQuietly(handle);
		}

		private static void closeQuietly(MemorySegment handle) {
			try {
				int ignored = (int) closeHandle.invokeExact(handle);
			} catch (Throwable e) {
				ClaudeChatClient.LOGGER.warn("CloseHandle failed", e);
			}
		}
	}
}
