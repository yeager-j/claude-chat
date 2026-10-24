package claudechat;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Answers questions by running the local Claude Code CLI ("claude -p"), which uses whatever account it is signed in to.
 * Each question is a {@link ClaudeRun}: this class builds its system prompt and command line, starts claude, reads
 * its output and enforces the timeout; the run holds the question's state and can cancel it.
 * <p>
 * The executable is whatever {@code claudePath} names, so tests can point it at a stand-in that plays scripted
 * output through the same command line, process handling and stream parsing.
 */
public class ClaudeCli {
	private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
	// After claude exits, how long its output may take to drain. A process it started can keep the pipe open.
	private static final long OUTPUT_DRAIN_MILLIS = 10_000;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final ClaudeConfig config;
	private final KnowledgeBase knowledge;
	private final McpServer mcp;
	/** Numbers each question's files, so a cancelled question's cleanup can't delete the next one's. */
	private final AtomicLong runCount = new AtomicLong();

	public ClaudeCli(ClaudeConfig config, KnowledgeBase knowledge, McpServer mcp) {
		this.config = config;
		this.knowledge = knowledge;
		this.mcp = mcp;
		deleteLeftoverFiles();
	}

	/**
	 * Starts answering a question on a thread of its own. {@code onProgress} gets the short status lines Claude writes
	 * between tool calls, and {@code onApprovalStatus} the state of approval requests, both on background threads.
	 */
	public ClaudeRun start(ClaudeRun.Request request, Consumer<String> onProgress, Consumer<Component> onApprovalStatus) {
		ClaudeRun run = new ClaudeRun(request, onApprovalStatus);
		run.start(() -> answer(run, onProgress));
		return run;
	}

	/** The question's thread: always completes the run, or the player could never ask again. */
	private void answer(ClaudeRun run, Consumer<String> onProgress) {
		try (McpServer.Grant grant = run.grant(mcp)) {
			decompileMentionedMods(run.request(), onProgress);
			run.complete(ask(run, grant, onProgress));
		} catch (CancellationException e) {
			ClaudeChatClient.LOGGER.info("Claude question cancelled");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			run.complete(ClaudeRun.Outcome.failure("Stopped before Claude answered."));
		} catch (IOException e) {
			// This class fails with the player's message itself, not a wrapper around it.
			ClaudeChatClient.LOGGER.warn("Claude request failed", e);
			run.complete(ClaudeRun.Outcome.failure(e.getMessage() != null ? e.getMessage() : e.toString()));
		} catch (Throwable e) {
			ClaudeChatClient.LOGGER.warn("Claude request failed", e);
			run.complete(ClaudeRun.Outcome.failure(e.toString()));
		} finally {
			run.approvals().close();
		}
	}

	private void decompileMentionedMods(ClaudeRun.Request request, Consumer<String> onProgress) throws IOException, InterruptedException {
		for (KnowledgeBase.Source mod : knowledge.mentionedWithoutCode(request.question(), request.namespaces())) {
			onProgress.accept("Decompiling " + mod.label());
			knowledge.buildCodeNow(mod, message -> { });
		}
	}

	private ClaudeRun.Outcome ask(ClaudeRun run, McpServer.Grant grant, Consumer<String> onProgress)
		throws IOException, InterruptedException {
		String exe = findExecutable().orElseThrow(() -> new IOException(
			"Couldn't find Claude Code. Install it, or set claudePath in config/claudechat.properties."));
		int timeoutSeconds = config.timeoutSeconds;
		ClaudeRun.Request request = run.request();

		// Run inside the knowledge folder: Claude can search it, and won't pick up a CLAUDE.md or other files from the game directory.
		Path workDir = knowledge.root();
		Files.createDirectories(workDir);
		// The question's files, in a folder only the player's account can open: the MCP config holds the question's
		// token, and the prompt the game state. They exist only while the question runs.
		Path runDir = PrivateFiles.createDir(runsDir(), runDirName(runCount.incrementAndGet()));
		Path promptFile = runDir.resolve("system-prompt.txt");
		// Only this mod's MCP server: --strict-mcp-config also keeps the player's other MCP servers, configured for
		// Claude Code in general, out of Minecraft questions. A file avoids Windows quoting of JSON.
		Path mcpConfig = runDir.resolve("mcp.json");
		try {
			PrivateFiles.write(promptFile, systemPrompt(request));
			PrivateFiles.write(mcpConfig, grant.claudeConfig());

			ClaudeResult result = invoke(run, command(exe, mcpConfig, promptFile, request.policy(), request.resume()),
				workDir, timeoutSeconds, onProgress);
			if (request.resume().isPresent() && result.missingSession()) {
				// The saved conversation is gone, e.g. Claude Code's history was cleared. Nothing ran, so asking again is safe.
				result = invoke(run, command(exe, mcpConfig, promptFile, request.policy(), Optional.empty()),
					workDir, timeoutSeconds, onProgress);
			}

			if (result.isError()) {
				return new ClaudeRun.Outcome(result.errorMessage(), true, result.sessionId());
			}

			String answer = result.text().isBlank() ? "(no answer)" : result.text();
			return new ClaudeRun.Outcome(answer, false, result.sessionId());
		} finally {
			Files.deleteIfExists(mcpConfig);
			Files.deleteIfExists(promptFile);
			Files.deleteIfExists(runDir);
		}
	}

	private String systemPrompt(ClaudeRun.Request request) {
		SystemPrompt.Parts parts = new SystemPrompt.Parts(request.gameContext(), mcp.worldOpen(), Waypoints.available(),
			request.policy().tier(), knowledge.describeForPrompt(), config.extraInstructions);
		return SystemPrompt.build(parts, mcp.toolNames());
	}

	private List<String> command(String exe, Path mcpConfig, Path promptFile, Policy policy, Optional<String> resume) {
		List<String> command = new ArrayList<>(List.of(exe, "-p",
			"--output-format", "stream-json", "--verbose",
			"--model", config.model,
			"--mcp-config", mcpConfig.toString(),
			"--strict-mcp-config",
			"--append-system-prompt-file", promptFile.toString()));
		command.addAll(policy.tier().cliFlags(mcp.isRunning(), FabricLoader.getInstance().getGameDir().toString()));
		config.effort.flag().ifPresent(effort -> command.addAll(List.of("--effort", effort)));
		resume.ifPresent(session -> command.addAll(List.of("--resume", session)));
		return command;
	}

	/**
	 * Runs claude once for the question: sends the question and waits for claude, reading its output on a thread of its
	 * own. On a timeout, an interrupt or any other failure, claude and every process it started are killed.
	 */
	private ClaudeResult invoke(ClaudeRun run, List<String> command, Path workDir, int timeoutSeconds, Consumer<String> onProgress)
		throws IOException, InterruptedException {
		Path errLog = ClaudeConfig.dir().resolve("last-error.log");
		Process process = run.startProcess(new ProcessBuilder(command)
			.directory(workDir.toFile())
			.redirectError(errLog.toFile()));

		ClaudeStream stream = new ClaudeStream(onProgress);
		Thread reader = new Thread(() -> stream.read(process.getInputStream()), "Claude Chat output");
		reader.setDaemon(true);
		reader.start();

		try {
			// The question goes over stdin so quotes and special characters survive Windows argument parsing.
			try (OutputStream stdin = process.getOutputStream()) {
				stdin.write(run.request().question().getBytes(StandardCharsets.UTF_8));
			}
			waitForExit(process, run.approvals(), timeoutSeconds);
		} finally {
			// Also after a normal exit: whatever claude started must not outlive the question.
			run.endProcess();
		}

		reader.join(OUTPUT_DRAIN_MILLIS);
		run.throwIfCancelled();
		Optional<ClaudeResult> result = stream.result();
		if (result.isPresent()) {
			return result.get();
		}

		String err = Files.exists(errLog) ? Files.readString(errLog).strip() : "";
		String detail = !err.isEmpty() ? err : stream.head();
		throw new IOException("Claude Code failed (exit " + process.exitValue() + "): "
			+ (detail.length() > 300 ? detail.substring(0, 300) + "..." : detail));
	}

	/** Waits for claude to exit; throws once it has worked longer than the timeout. */
	private static void waitForExit(Process process, Approvals approvals, int timeoutSeconds) throws IOException, InterruptedException {
		// Time spent waiting for the player to Allow or Deny doesn't count towards the timeout.
		long started = System.currentTimeMillis();
		long waitedBefore = approvals.waitedMillis();
		while (!process.waitFor(1, TimeUnit.SECONDS)) {
			long working = System.currentTimeMillis() - started - (approvals.waitedMillis() - waitedBefore);
			if (!approvals.isWaiting() && working > timeoutSeconds * 1000L) {
				throw new IOException("Claude took longer than " + timeoutSeconds + "s and was stopped.");
			}
		}
	}

	/** Where each question's folder lives while it runs. */
	private static Path runsDir() {
		return ClaudeConfig.dir().resolve("runs");
	}

	/**
	 * A question's folder: named after this game's process as well as the question, since two game instances can share
	 * a config folder, and random after that, so nobody can make it ahead of time.
	 */
	private static String runDirName(long run) {
		byte[] random = new byte[16];
		RANDOM.nextBytes(random);
		return ProcessHandle.current().pid() + "-" + run + "-" + HexFormat.of().formatHex(random);
	}

	/**
	 * Folders of questions that were running when a game crashed or was killed, files older versions kept directly in
	 * runs, and the single mcp.json before that. Their tokens died with that game, but they needn't stay on disk.
	 * Another running game's files stay.
	 */
	private static void deleteLeftoverFiles() {
		try {
			Files.deleteIfExists(ClaudeConfig.dir().resolve("mcp.json"));
			try (Stream<Path> entries = Files.list(runsDir())) {
				for (Path entry : entries.toList()) {
					if (!ownerAlive(entry)) {
						KnowledgeFiles.deleteTree(entry);
					}
				}
			}
		} catch (NoSuchFileException e) {
			// No question has run yet.
		} catch (IOException e) {
			ClaudeChatClient.LOGGER.warn("Could not delete old question files in {}", runsDir(), e);
		}
	}

	/** Is the game that made this {@link #runDirName} folder (or an older version's file) still running? */
	private static boolean ownerAlive(Path file) {
		String name = file.getFileName().toString();
		int dash = name.indexOf('-');
		try {
			long pid = Long.parseLong(dash < 0 ? name : name.substring(0, dash));
			return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private Optional<String> findExecutable() {
		if (config.claudePath.isPresent()) {
			return config.claudePath.map(Path::toString);
		}

		String name = WINDOWS ? "claude.exe" : "claude";
		String path = System.getenv("PATH");
		if (path != null) {
			for (String entry : path.split(File.pathSeparator)) {
				Path candidate = Path.of(entry.trim()).resolve(name);
				if (Files.isRegularFile(candidate)) {
					return Optional.of(candidate.toString());
				}
			}
		}

		// The native installer puts it in ~/.local/bin, which may not be on the game's PATH.
		Path local = Path.of(System.getProperty("user.home"), ".local", "bin", name);
		if (Files.isRegularFile(local)) {
			return Optional.of(local.toString());
		}

		// Fallback: the copy bundled with the Claude desktop app (%APPDATA%\Claude\claude-code\<version>\<hash>\claude.exe).
		String appData = System.getenv("APPDATA");
		if (WINDOWS && appData != null) {
			Path bundled = Path.of(appData, "Claude", "claude-code");
			if (Files.isDirectory(bundled)) {
				try (Stream<Path> files = Files.find(bundled, 3, (p, attrs) -> attrs.isRegularFile() && p.getFileName().toString().equals(name))) {
					return files.max(Comparator.comparing(p -> {
						try {
							return Files.getLastModifiedTime(p);
						} catch (IOException e) {
							return java.nio.file.attribute.FileTime.fromMillis(0);
						}
					})).map(Path::toString);
				} catch (IOException ignored) {
				}
			}
		}
		return Optional.empty();
	}
}
