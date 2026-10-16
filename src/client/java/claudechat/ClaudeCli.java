package claudechat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Answers questions by running the local Claude Code CLI ("claude -p"), which uses whatever account it is signed in to. */
public class ClaudeCli {
	private static final Pattern DECOMPILE_REQUEST = Pattern.compile("\\[\\[decompile:([^\\]\\s]+)\\]\\]");
	private static final int MAX_DECOMPILE_ROUNDS = 2;
	// Read-only: Claude can search the knowledge folder and the web, but never edit files or run commands.
	private static final String TOOLS = "Read,Grep,Glob,WebSearch,WebFetch";
	private static final String SYSTEM_PROMPT = """
		You are answering a question typed into Minecraft chat by the player. Your reply is shown in the game's chat box.
		- Answer for the exact game version and mods listed below. Mechanics change between versions, so don't trust memory
		  for specifics: check the reference files below, or search the web if they don't cover it.
		- "This", "here" or "it" usually means the held item or the block the player is looking at.
		- Simple questions (recipes, drops, what something does): 1-3 short sentences.
		- Mechanics questions (spawning, AI, redstone timing, formulas): research the source until you are confident,
		  then explain in up to about 10 short lines and name the key class or data file so the player can check it.
		- If answering needs a mod's code and the list below says it isn't decompiled yet, reply with only
		  [[decompile:<mod id>]] (one per line if you need several) and nothing else. It takes a few seconds; then you'll be
		  asked to continue. Only request code that will actually help; recipes and other data are already available.
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
		- Commands go in backticks and become click-to-insert, e.g. `/gamerule keepInventory true`.
		""";

	private static final String WORLD_TOOLS_PROMPT = """

		The player's own world (single-player) is available through the mcp__minecraft__* tools: search_containers
		("where are my diamonds?", "which chest has..."), search_entities (pets, named mobs, item frames, dropped items),
		get_player (inventory, ender chest, position), find_blocks (nearest placed blocks: spawners, beds, respawn anchors,
		portals, with block state and block entity filters), find_structures (nearest structures, optionally only ones the
		player hasn't visited yet or ones reaching into a biome), find_biomes (nearest patches of a biome) and
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

	private final ClaudeConfig config;
	private final KnowledgeBase knowledge;
	private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Claude Chat");
		thread.setDaemon(true);
		return thread;
	});
	private volatile String sessionId;

	private final McpServer worldTools;

	public ClaudeCli(ClaudeConfig config, KnowledgeBase knowledge, McpServer worldTools) {
		this.config = config;
		this.knowledge = knowledge;
		this.worldTools = worldTools;
	}

	public void resetSession() {
		sessionId = null;
	}

	/**
	 * {@code onProgress} gets the short status lines Claude writes between tool calls, on a background thread.
	 * {@code mentionText} is the question plus the namespaces of whatever the player is holding or looking at; any
	 * data-only mod it names gets decompiled first.
	 */
	public CompletableFuture<String> ask(String question, String gameContext, String mentionText, Consumer<String> onProgress) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				for (KnowledgeBase.Source mod : knowledge.mentionedWithoutCode(mentionText)) {
					onProgress.accept("Decompiling " + mod.label());
					knowledge.buildCodeNow(mod, message -> { });
				}
				return run(question, gameContext, onProgress);
			} catch (IOException e) {
				throw new RuntimeException(e.getMessage(), e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RuntimeException("Interrupted", e);
			}
		}, executor);
	}

	private String run(String question, String gameContext, Consumer<String> onProgress) throws IOException, InterruptedException {
		String exe = findExecutable().orElseThrow(() -> new IOException(
			"Couldn't find Claude Code. Install it, or set claudePath in config/claudechat.properties."));

		// Run inside the knowledge folder: Claude can search it, and won't pick up a CLAUDE.md or other files from the game directory.
		Path workDir = knowledge.root();
		Files.createDirectories(workDir);
		Path promptFile = writeSystemPrompt(gameContext);

		JsonObject result = invoke(exe, workDir, promptFile, question, sessionId, onProgress);
		if (result.has("is_error") && result.get("is_error").getAsBoolean() && sessionId != null) {
			// The saved conversation may be gone; try once more as a new conversation.
			sessionId = null;
			result = invoke(exe, workDir, promptFile, question, null, onProgress);
		}
		String text = resultText(result);

		// Claude can ask for a mod's code with [[decompile:<id>]]. Decompile it, then continue the same conversation.
		for (int round = 0; round < MAX_DECOMPILE_ROUNDS; round++) {
			Matcher m = DECOMPILE_REQUEST.matcher(text);
			List<String> done = new ArrayList<>();
			List<String> refused = new ArrayList<>();
			while (m.find()) {
				String id = m.group(1);
				Optional<KnowledgeBase.Source> mod = knowledge.source(id);
				if (mod.isPresent() && knowledge.canDecompile(mod.get())) {
					onProgress.accept("Decompiling " + mod.get().label());
					knowledge.buildCodeNow(mod.get(), message -> { });
				}
				if (mod.isPresent() && knowledge.hasCode(mod.get())) {
					done.add(id + " (" + mod.get().dir() + "/src)");
				} else {
					refused.add(id);
				}
			}
			if (done.isEmpty() && refused.isEmpty()) {
				break;
			}
			promptFile = writeSystemPrompt(gameContext);
			String followUp = (done.isEmpty() ? "" : "Decompiled: " + String.join(", ", done) + ". ")
				+ (refused.isEmpty() ? "" : "Not available (no code, excluded by the player, or unknown id): " + String.join(", ", refused) + ". ")
				+ "Now answer my question.";
			text = resultText(invoke(exe, workDir, promptFile, followUp, sessionId, onProgress));
		}
		return text.isBlank() ? "(no answer)" : text;
	}

	private Path writeSystemPrompt(String gameContext) throws IOException {
		Path promptFile = ClaudeConfig.dir().resolve("system-prompt.txt");
		String systemPrompt = SYSTEM_PROMPT
			+ "\nCurrent game state:\n" + gameContext
			+ (worldTools.isRunning() ? WORLD_TOOLS_PROMPT : NO_WORLD_TOOLS_PROMPT)
			+ "\n" + knowledge.describeForPrompt()
			+ (config.extraInstructions.isEmpty() ? "" : "\n" + config.extraInstructions + "\n");
		Files.writeString(promptFile, systemPrompt, StandardCharsets.UTF_8);
		return promptFile;
	}

	/** The answer text; remembers the session so follow-ups continue the conversation. */
	private String resultText(JsonObject result) throws IOException {
		if (result.has("session_id")) {
			sessionId = result.get("session_id").getAsString();
		}
		String text = result.has("result") ? result.get("result").getAsString() : "";
		if (result.has("is_error") && result.get("is_error").getAsBoolean()) {
			throw new IOException(text.isBlank() ? "Claude Code reported an error." : text);
		}
		return text;
	}

	private JsonObject invoke(String exe, Path workDir, Path promptFile, String question, String resume,
		Consumer<String> onProgress) throws IOException, InterruptedException {
		// Only this mod's MCP server (none on multiplayer): --strict-mcp-config also keeps the player's other MCP servers,
		// configured for Claude Code in general, out of Minecraft questions. A file avoids Windows quoting of JSON.
		Path mcpConfig = ClaudeConfig.dir().resolve("mcp.json");
		Files.writeString(mcpConfig, worldTools.claudeConfig(), StandardCharsets.UTF_8);
		String allowed = worldTools.isRunning() ? TOOLS + ",mcp__minecraft" : TOOLS;
		List<String> command = new ArrayList<>(List.of(exe, "-p",
			"--output-format", "stream-json", "--verbose",
			"--model", config.model,
			"--tools", TOOLS,
			"--allowedTools", allowed,
			"--mcp-config", mcpConfig.toString(),
			"--strict-mcp-config",
			"--append-system-prompt-file", promptFile.toString()));
		if (!config.effort.isEmpty()) {
			command.add("--effort");
			command.add(config.effort);
		}
		if (resume != null) {
			command.add("--resume");
			command.add(resume);
		}

		Path errLog = ClaudeConfig.dir().resolve("last-error.log");
		Process process = new ProcessBuilder(command)
			.directory(workDir.toFile())
			.redirectError(errLog.toFile())
			.start();

		// The question goes over stdin so quotes and special characters survive Windows argument parsing.
		try (OutputStream stdin = process.getOutputStream()) {
			stdin.write(question.getBytes(StandardCharsets.UTF_8));
		}
		StreamResult stream = new StreamResult();
		CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> readStream(process.getInputStream(), onProgress, stream));

		if (!process.waitFor(config.timeoutSeconds, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new IOException("Claude took longer than " + config.timeoutSeconds + "s and was stopped.");
		}
		reader.join();
		if (stream.result != null) {
			return stream.result;
		}
		String err = Files.exists(errLog) ? Files.readString(errLog).strip() : "";
		String detail = !err.isEmpty() ? err : stream.raw.toString().strip();
		throw new IOException("Claude Code failed (exit " + process.exitValue() + "): "
			+ (detail.length() > 300 ? detail.substring(0, 300) + "..." : detail));
	}

	private static final class StreamResult {
		JsonObject result;
		final StringBuilder raw = new StringBuilder();
	}

	/**
	 * Reads stream-json events. Each text block arrives as its own event, and only afterwards do we learn whether a tool
	 * call follows it. So text is held back: if more activity follows, it was a progress note; the last one is the answer
	 * (which also arrives in the final "result" event).
	 */
	private static void readStream(InputStream in, Consumer<String> onProgress, StreamResult out) {
		String pending = null;
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (out.raw.length() < 2000) {
					out.raw.append(line).append('\n');
				}
				JsonObject event;
				try {
					event = JsonParser.parseString(line).getAsJsonObject();
				} catch (RuntimeException e) {
					continue;
				}
				String type = event.has("type") ? event.get("type").getAsString() : "";
				if (type.equals("result")) {
					out.result = event;
				} else if (type.equals("assistant") || type.equals("user")) {
					String text = type.equals("assistant") ? textOf(event) : null;
					boolean toolActivity = type.equals("user") || hasBlock(event, "tool_use");
					if (pending != null && (text != null || toolActivity)) {
						onProgress.accept(pending);
						pending = null;
					}
					if (text != null) {
						pending = text;
					}
				}
			}
		} catch (IOException e) {
			ClaudeChatClient.LOGGER.debug("Claude output stream closed", e);
		}
	}

	private static String textOf(JsonObject event) {
		StringBuilder sb = new StringBuilder();
		for (JsonElement block : contentBlocks(event)) {
			JsonObject obj = block.getAsJsonObject();
			if ("text".equals(obj.get("type").getAsString()) && obj.has("text")) {
				sb.append(obj.get("text").getAsString());
			}
		}
		String text = sb.toString().strip();
		return text.isEmpty() ? null : text;
	}

	private static boolean hasBlock(JsonObject event, String blockType) {
		for (JsonElement block : contentBlocks(event)) {
			if (blockType.equals(block.getAsJsonObject().get("type").getAsString())) {
				return true;
			}
		}
		return false;
	}

	private static JsonArray contentBlocks(JsonObject event) {
		if (event.has("message") && event.getAsJsonObject("message").has("content")
			&& event.getAsJsonObject("message").get("content").isJsonArray()) {
			return event.getAsJsonObject("message").getAsJsonArray("content");
		}
		return new JsonArray();
	}

	private Optional<String> findExecutable() {
		if (!config.claudePath.isEmpty()) {
			return Optional.of(config.claudePath);
		}

		boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
		String name = windows ? "claude.exe" : "claude";
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
		if (windows && appData != null) {
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
