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
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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
	private static final String SYSTEM_PROMPT = """
		You are answering a question typed into Minecraft chat by the player. Your reply is shown in the game's chat box.
		- Answer for the exact game version and mods listed below. Mechanics change between versions, so don't trust memory
		  for specifics: check the reference files below, or search the web if they don't cover it.
		- "This", "here" or "it" usually means the held item or the block the player is looking at.
		- Simple questions (recipes, drops, what something does): 1-3 short sentences.
		- Mechanics questions (spawning, AI, redstone timing, formulas): research the source until you are confident,
		  then explain in up to about 10 short lines and name the key class or data file so the player can check it.
		- If answering needs a mod's code and the list below says it isn't decompiled yet, call
		  mcp__minecraft__decompile_mod with its id, then search the source it returns. It takes a few seconds. Only
		  decompile code that will actually help; recipes and other data are already available.
		- The player waits while you research and sees only your text, not your tool calls. So every time you are about
		  to call tools (including your first call), first write one status line under 8 words saying what you're doing,
		  e.g. "Searching the crafter code" or "Found it, checking hopper logic". It is shown live in chat.
		  Your final message is the answer; don't begin it with a status line.

		Formatting (the mod renders this in chat):
		- Markdown subset only: **bold**, *italic*, ~~strike~~, `code`, [text](https://url), "# " headings, "- " bullets,
		  "1. " lists. No tables and no code blocks; chat is narrow (about 50 characters per line before wrapping).
		- Name items and blocks as [[id]] (e.g. [[iron_ingot]], [[minecraft:crafter]]). It shows the item's icon and name,
		  with the real tooltip on hover. Use exact ids (check lang/*.json if unsure). Counts: "3× [[iron_ingot]]".
		- To show how to make something, put [[recipe:<id>]] on its own line. <id> is a recipe file name under
		  data/<namespace>/recipe/ (e.g. [[recipe:minecraft:crafter]]), or the item it makes. It is drawn as an icon grid
		  from the game's data, so don't also list the ingredients, counts or layout in text. The grid can be the whole
		  answer; add a sentence only if it answers something the grid doesn't, and only if you've verified it.
		- Controls: {{key.inventory}}, {{key.use}}, {{key.attack}}, {{key.jump}}, {{key.sneak}}, {{key.sprint}}, {{key.drop}},
		  {{key.swapOffhand}}, {{key.pickItem}} show the player's own key binding.
		- Commands go in backticks and become click-to-insert, e.g. `/gamerule keep_inventory true`.
		""";

	private static final String WAYPOINT_PROMPT = """
		- Xaero's Minimap is installed. When the player asks for a waypoint, or you give them a place to travel to, put
		  [[waypoint:<name>|<x> <y> <z>|<dimension>]] right after the coordinates, e.g.
		  `1240 ~ -3380` [[waypoint:End City|1240 ~ -3380|the_end]]. Use ~ for an unknown y, a short name (32 characters
		  at most) and the dimension the place is in. It shows a button that opens Xaero's Add Waypoint screen filled in.
		  You can't add waypoints yourself, so never say one was added; tell the player to click [+ Waypoint].
		""";

	private static final String WORLD_TOOLS_PROMPT = """

		The player's own world (single-player) is available through the mcp__minecraft__* tools: search_containers
		("where are my diamonds?", "which chest has..."), search_entities (pets, named mobs, item frames, dropped items),
		get_player (inventory, ender chest, position), find_blocks (nearest placed blocks: spawners, beds, respawn anchors,
		portals, with block state and block entity filters), find_structures (nearest structures, optionally only ones the
		player hasn't visited yet, ones reaching into a biome, or ones with certain rooms, like an end city with a ship), find_biomes (nearest patches of a biome) and
		inspect_location (block and biome at a position, predicted from the seed where nothing is generated yet). They
		return exact distances and compass directions; report those instead of working them out yourself. Show
		coordinates as `x y z` (or `x ~ z` for structures and biomes).
		Structure template files (data/*/structure/*.nbt) are gzipped NBT, so Read can't open them. To identify a
		structure the player describes by its blocks or shape, use search_structure_templates with those blocks; use
		inspect_structure_template for one template's blocks, chests, spawners and layer-by-layer shape.
		The player's schematics (Litematica .litematic, WorldEdit .schem) are also gzipped NBT: use list_schematics and
		inspect_schematic. For "how much X do I need", use its materials, crafting and base_materials rather than
		working out recipes yourself; to check a chest of supplies against a schematic, pass compare_with.
		""";
	private static final String NO_WORLD_TOOLS_PROMPT = """

		The player is on a multiplayer server, so their world's contents and structure locations aren't available.
		""";
	private static final String LOOK_PROMPT = """
		mcp__minecraft__look shows what the player sees right now: a screenshot, plus the block or entity under the
		crosshair and the visible blocks and entities with their positions and where they are in the image. Use it when
		the player says "this", "that" or "here", asks what they're looking at, or wants help with what's in front of
		them. Shaders and resource packs can make the screenshot misleading, so name things from the data. It reports
		only what's in view, so for anything else use the other tools.
		""";

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
			decompileMentionedMods(run.request().mentionText(), onProgress);
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

	private void decompileMentionedMods(String mentionText, Consumer<String> onProgress) throws IOException, InterruptedException {
		for (KnowledgeBase.Source mod : knowledge.mentionedWithoutCode(mentionText)) {
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
		Files.createDirectories(runsDir());
		long id = runCount.incrementAndGet();
		Path promptFile = runFile(id, "system-prompt.txt");
		// Only this mod's MCP server: --strict-mcp-config also keeps the player's other MCP servers, configured for
		// Claude Code in general, out of Minecraft questions. A file avoids Windows quoting of JSON. It holds the
		// question's token, so it exists only while the question runs.
		Path mcpConfig = runFile(id, "mcp.json");
		try {
			Files.writeString(promptFile, systemPrompt(request), StandardCharsets.UTF_8);
			writeOwnerOnly(mcpConfig, grant.claudeConfig());

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
		}
	}

	private String systemPrompt(ClaudeRun.Request request) {
		boolean worldOpen = mcp.worldOpen();
		return SYSTEM_PROMPT
			+ (Waypoints.available() ? WAYPOINT_PROMPT : "")
			+ "\nCurrent game state:\n" + request.gameContext()
			+ (worldOpen ? WORLD_TOOLS_PROMPT : NO_WORLD_TOOLS_PROMPT)
			+ LOOK_PROMPT
			+ request.policy().tier().prompt(worldOpen)
			+ "\n" + knowledge.describeForPrompt()
			+ (config.extraInstructions.isEmpty() ? "" : "\n" + config.extraInstructions + "\n");
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
			if (process.isAlive()) {
				ClaudeRun.killWithDescendants(process);
			}
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

	/**
	 * Creates {@code file} readable only by its owner before writing {@code text} to it: POSIX permissions where the
	 * file system has them, otherwise (Windows) an access list with a single entry, for the file's owner.
	 */
	static void writeOwnerOnly(Path file, String text) throws IOException {
		Files.deleteIfExists(file);
		Set<String> views = file.getFileSystem().supportedFileAttributeViews();
		if (views.contains("posix")) {
			Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
		} else {
			Files.createFile(file);
			if (views.contains("acl")) {
				AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
				acl.setAcl(List.of(AclEntry.newBuilder()
					.setType(AclEntryType.ALLOW)
					.setPrincipal(acl.getOwner())
					.setPermissions(EnumSet.allOf(AclEntryPermission.class))
					.build()));
			}
		}

		Files.writeString(file, text, StandardCharsets.UTF_8);
	}

	/** Where each question's prompt and MCP config live while it runs. */
	private static Path runsDir() {
		return ClaudeConfig.dir().resolve("runs");
	}

	/**
	 * A question's file: named after this game's process as well as the question, since two game instances can share
	 * a config folder.
	 */
	private static Path runFile(long run, String name) {
		return runsDir().resolve(ProcessHandle.current().pid() + "-" + run + "-" + name);
	}

	/**
	 * Files of questions that were running when a game crashed or was killed, and the single mcp.json older versions
	 * used. Their tokens died with that game, but they needn't stay on disk. Another running game's files stay.
	 */
	private static void deleteLeftoverFiles() {
		try {
			Files.deleteIfExists(ClaudeConfig.dir().resolve("mcp.json"));
			try (Stream<Path> files = Files.list(runsDir())) {
				for (Path file : files.toList()) {
					if (!ownerAlive(file)) {
						Files.deleteIfExists(file);
					}
				}
			}
		} catch (NoSuchFileException e) {
			// No question has run yet.
		} catch (IOException e) {
			ClaudeChatClient.LOGGER.warn("Could not delete old question files in {}", runsDir(), e);
		}
	}

	/** Is the game that wrote this {@link #runFile} still running? */
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
