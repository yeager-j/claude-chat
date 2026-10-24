package claudechat.test;

import claudechat.Approvals;
import claudechat.ClaudeChatClient;
import claudechat.PermissionTier;
import claudechat.Policy;
import claudechat.Tool;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * What every step works with: the world it runs in, chat (typing, reading history, clicking buttons), questions
 * answered by {@link FakeClaude} or the real Claude, and MCP tool calls as Claude makes them.
 * <p>
 * Tool calls come in two kinds. Calls whose result doesn't depend on a question's permissions use the test's own MCP
 * access ({@link #tool}); calls that do ({@code run_command}, {@code approve}) go through a question the fake holds
 * open ({@link #hold}), so they get the tier and cheats {@code /claude permissions} and {@code /claude cheats} set.
 */
final class Game {
	static final Logger LOGGER = LoggerFactory.getLogger("claudechat-test");
	/** Ticks per second. */
	static final int SECONDS = 20;
	/** 26.x uses SDL scancodes for input, not GLFW key codes. */
	private static final int KEY_ENTER = InputConstants.KEY_RETURN;
	private static final int KEY_ESCAPE = InputConstants.KEY_ESCAPE;
	/** Beyond a question's own timeout, for the mod to report it. */
	private static final int ANSWER_MARGIN_SECONDS = 60;
	/** World tools wait on the server thread, which can take a while for structure searches. */
	private static final int TOOL_TIMEOUT_SECONDS = 120;

	final ClientGameTestContext context;
	final TestOptions options;
	/** Shared with the fake claude. */
	final Path fake;
	/** The test's own MCP access: Read only, cheats off, for calls that don't depend on a question's permissions. */
	private final McpClient mcp;
	private TestSingleplayerContext world;
	/** Numbers held questions, which must be unique within a world. */
	private int held;

	Game(ClientGameTestContext context, TestOptions options) {
		this.context = context;
		this.options = options;
		this.fake = FakeClaude.folder(FabricLoader.getInstance().getGameDir());
		// The one test hook left in the mod: MCP access without a question, for calls with no world open (no chat to
		// ask in) and for steps whose tool calls outlast any question timeout short enough to test.
		Tool.Context readOnly = new Tool.Context(new Policy(PermissionTier.READ_ONLY, false), new Approvals(note -> { }));
		this.mcp = McpClient.fromClaudeConfig(ClaudeChatClient.mcp().grant(readOnly).claudeConfig());
	}

	// --- worlds ---------------------------------------------------------------------------------------------------

	void openWorld(Step.World spec) {
		check(world == null, "a world is already open");
		world = spec.create(context);
	}

	/** Leaves the world, if one is open; the question running in it, if any, is cancelled. */
	void leaveWorld() {
		if (world != null) {
			TestSingleplayerContext leaving = world;
			world = null;
			leaving.close();
		}
	}

	TestSingleplayerContext world() {
		check(world != null, "no world is open");
		return world;
	}

	TestServerContext server() {
		return world().getServer();
	}

	/** Runs a command as the server, which can do anything whatever the world's cheats setting. */
	void command(String command) {
		server().runCommand(command);
	}

	BlockPos playerPosition() {
		return server().computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().blockPosition());
	}

	/** Runs a command that reloads data packs (/reload, /datapack) and waits until the server has its new resources. */
	void reload(String command) {
		Object before = server().computeOnServer(MinecraftServer::getResourceManager);
		command(command);
		// Polled from the test thread: waitFor's predicate runs on the client thread, which can't ask the server.
		for (int tick = 0; tick < 60 * SECONDS; tick++) {
			if (server().computeOnServer(MinecraftServer::getResourceManager) != before) {
				return;
			}
			context.waitTick();
		}

		throw new AssertionError(command + " didn't finish reloading");
	}

	/** Saves the world as autosave does; save-all is a dedicated-server command. */
	void save() {
		server().runOnServer(s -> s.saveEverything(true, true, true));
	}

	// --- chat -----------------------------------------------------------------------------------------------------

	void openChat() {
		context.getInput().pressKey(options -> options.keyChat);
		context.waitTicks(2);
	}

	/** Types {@code message} into chat and sends it, as the player does. */
	void chat(String message) {
		openChat();
		context.getInput().typeChars(message);
		context.getInput().pressKey(KEY_ENTER);
		context.waitTicks(2);
	}

	/** A screenshot with chat open, so its history shows; build/run/clientGameTest/screenshots/{@code name}.png. */
	void screenshot(String name) {
		openChat();
		context.takeScreenshot(name);
		closeChat();
	}

	/** The first Escape only closes command suggestions, if they're open; the second closes chat. */
	void closeChat() {
		context.getInput().pressKey(KEY_ESCAPE);
		context.waitTicks(1);
		if (context.computeOnClient(mc -> mc.gui.screen() != null)) {
			context.getInput().pressKey(KEY_ESCAPE);
		}
		context.waitTicks(2);
	}

	/** Every chat line's text, oldest first, for failure messages. */
	String chatText() {
		return context.computeOnClient(mc -> String.join("\n", ChatHistory.lines(mc).reversed().stream()
			.map(line -> line.content().getString()).toList()));
	}

	boolean chatHas(String text) {
		return context.computeOnClient(mc -> ChatHistory.has(mc, text));
	}

	/** The command behind the newest "[label]" button, or null. */
	String newestButton(String label) {
		return context.computeOnClient(mc -> ChatHistory.newestButton(mc, label));
	}

	/** Clicks a chat button's command as chat does. Without Fabric's client command hook this would go to the server. */
	void click(String command) {
		check(command != null, "no button to click");
		context.runOnClient(mc -> mc.player.connection.sendUnattendedCommand(Commands.trimOptionalPrefix(command), null));
		context.waitTicks(2);
	}

	/** Waits for a "[label]" button newer than {@code before} (the newest one's command then, or null). */
	String awaitNewButton(String label, String before) {
		context.waitFor(mc -> !Objects.equals(ChatHistory.newestButton(mc, label), before), 10 * SECONDS);
		return newestButton(label);
	}

	// --- settings, as the player changes them -----------------------------------------------------------------------

	/** /claude permissions, clicking the confirm button when the tier has one; waits for the mod to confirm. */
	void setPermissions(PermissionTier tier) {
		changeSetting("/claude permissions " + tier.id, tier.confirmation().map(warning -> "Turn on " + tier.label),
			"Permissions set to " + tier.label + ".", "Permissions are already " + tier.label + ".");
	}

	/** /claude cheats, clicking the confirm button turning them on needs; waits for the mod to confirm. */
	void setCheats(boolean on) {
		changeSetting("/claude cheats " + (on ? "on" : "off"), on ? Optional.of("Turn on cheats") : Optional.empty(),
			"Cheats " + (on ? "on:" : "off:"), "Cheats are already " + (on ? "on" : "off") + ".");
	}

	/** Client commands run as they're sent, so whether the setting was already so is in chat right after. */
	private void changeSetting(String command, Optional<String> confirmButton, String changed, String unchanged) {
		long changedBefore = context.computeOnClient(mc -> ChatHistory.count(mc, changed));
		long unchangedBefore = context.computeOnClient(mc -> ChatHistory.count(mc, unchanged));
		String button = confirmButton.map(this::newestButton).orElse(null);
		chat(command);
		if (context.computeOnClient(mc -> ChatHistory.count(mc, unchanged)) > unchangedBefore) {
			return;
		}

		if (confirmButton.isPresent()) {
			click(awaitNewButton(confirmButton.get(), button));
		}
		context.waitFor(mc -> ChatHistory.count(mc, changed) > changedBefore, 5 * SECONDS);
	}

	// --- questions ------------------------------------------------------------------------------------------------

	/**
	 * A question's outcome as chat shows it.
	 *
	 * @param lines    the lines after the question's echo, oldest first
	 * @param errors   the lines among them that report a failure
	 * @param answered whether an answer (a last line with its copy button) arrived
	 */
	record Answer(List<String> lines, List<String> errors, boolean answered) {
		String text() {
			return String.join("\n", lines);
		}

		/** Fails unless an answer arrived and nothing failed. */
		Answer requireAnswer() {
			check(answered && errors.isEmpty(), "no answer, or an error: " + lines);
			return this;
		}

		/** Fails unless the question failed with {@code message} and no answer. */
		Answer requireError(String message) {
			check(!answered && errors.stream().anyMatch(error -> error.contains(message)), "expected the error \"" + message + "\": " + lines);
			return this;
		}
	}

	/** Asks {@code question}, which the fake answers by playing {@code scenario}, and waits for the outcome. */
	Answer ask(String scenario, String question) {
		start(scenario, question);
		return await(question);
	}

	/** Asks the real Claude, through the fake's pass-through; spends the signed-in account. */
	Answer askClaude(String question, String screenshot) {
		startClaude(question);
		Answer answer = await(question);
		screenshot(screenshot);
		return answer;
	}

	/** Sends {@code question} to the real Claude, through the fake's pass-through, without waiting. */
	void startClaude(String question) {
		start("live " + realClaude(), question);
	}

	/** Sends {@code question} for the fake to answer by playing {@code scenario}, without waiting. */
	void start(String scenario, String question) {
		check(!chatHas(ChatHistory.echo(question)), "questions must be unique within a world: " + question);
		try {
			FakeClaude.scenario(fake, scenario);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		chat("@claude " + question);
		context.waitFor(mc -> ChatHistory.has(mc, ChatHistory.echo(question)), 5 * SECONDS);
	}

	/**
	 * Waits for the question's answer or failure in chat. The mod stops a question at the timeout the config sets, so
	 * that plus a margin is long enough for every question.
	 */
	Answer await(String question) {
		context.waitFor(mc -> ChatHistory.ended(mc, question), (options.questionTimeoutSeconds() + ANSWER_MARGIN_SECONDS) * SECONDS);
		context.waitTicks(2);
		return context.computeOnClient(mc -> {
			List<GuiMessage> after = ChatHistory.after(mc, question);
			return new Answer(after.stream().map(line -> line.content().getString()).toList(),
				after.stream().filter(ChatHistory::isError).map(line -> line.content().getString()).toList(),
				after.stream().anyMatch(ChatHistory::endsAnswer));
		});
	}

	/** Lets a waiting scenario go on. */
	void go() {
		try {
			FakeClaude.go(fake);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Waits for the fake to start; returns its pid and its child's. */
	List<Long> awaitStarted() {
		context.waitFor(mc -> !FakeClaude.startedPids(fake).isEmpty(), 30 * SECONDS);
		return FakeClaude.startedPids(fake);
	}

	void awaitDead(List<Long> pids) {
		context.waitFor(mc -> pids.stream().noneMatch(pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)),
			10 * SECONDS);
	}

	/** The system prompt the last question was given. */
	String systemPrompt() {
		try {
			return FakeClaude.systemPrompt(fake);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** The working directory the last question started in. */
	Path claudeWorkingDirectory() {
		try {
			return FakeClaude.workingDirectory(fake);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	List<String> fakeCalls() {
		try {
			return FakeClaude.calls(fake);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * A question the fake holds open, so the test can play Claude with its token: its tool calls get the permissions
	 * the question started with. Closing it lets the fake answer.
	 */
	Held hold() {
		String question = "test question " + ++held;
		start("hold Done.", question);
		awaitStarted();
		try {
			return new Held(question, McpClient.fromClaudeConfig(FakeClaude.mcpConfig(fake)));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	final class Held implements AutoCloseable {
		final String question;
		final McpClient mcp;

		private Held(String question, McpClient mcp) {
			this.question = question;
			this.mcp = mcp;
		}

		ToolResult call(String tool, String arguments) {
			return Game.this.call(mcp, tool, arguments);
		}

		/** Lets the fake answer, and waits for the answer. */
		@Override
		public void close() {
			go();
			await(question).requireAnswer();
		}
	}

	/**
	 * The real claude for live questions: on PATH, or where the native installer puts it, as Claude Chat looks when
	 * claudePath is empty.
	 */
	private static Path realClaude() {
		String name = System.getProperty("os.name").toLowerCase().startsWith("windows") ? "claude.exe" : "claude";
		String path = Optional.ofNullable(System.getenv("PATH")).orElse("");
		for (String entry : path.split(File.pathSeparator)) {
			Path candidate = Path.of(entry.trim()).resolve(name);
			if (Files.isRegularFile(candidate)) {
				return candidate;
			}
		}

		Path local = Path.of(System.getProperty("user.home"), ".local", "bin", name);
		check(Files.isRegularFile(local), "live questions need the claude CLI on PATH or in ~/.local/bin");
		return local;
	}

	// --- tools ----------------------------------------------------------------------------------------------------

	/** Calls a tool with the test's own access; fails with the error text Claude would get if the call fails. */
	JsonObject tool(String name, String arguments) {
		return call(mcp, name, arguments).object();
	}

	JsonArray toolArray(String name, String arguments) {
		JsonElement data = call(mcp, name, arguments).data();
		check(data.isJsonArray(), name + " didn't return an array: " + data);
		return data.getAsJsonArray();
	}

	/** The error a call fails with, as Claude gets it; fails the test if the call succeeds. */
	String toolError(String name, String arguments) {
		return call(mcp, name, arguments).error();
	}

	ToolResult call(String name, String arguments) {
		return call(mcp, name, arguments);
	}

	List<String> toolNames() {
		return offThread(mcp::toolNames, TOOL_TIMEOUT_SECONDS);
	}

	McpClient mcp() {
		return mcp;
	}

	/**
	 * Calls a tool over MCP as Claude does, off the test thread (world tools wait on the server thread, which the test
	 * thread drives), and logs it.
	 */
	ToolResult call(McpClient client, String name, String arguments) {
		JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
		long start = System.nanoTime();
		ToolResult result = offThread(() -> client.callTool(name, args), TOOL_TIMEOUT_SECONDS);
		LOGGER.info("tool {} {} ({} ms) -> {}{}", name, arguments, (System.nanoTime() - start) / 1_000_000,
			result.isError() ? "error: " : "", result.text());
		return result;
	}

	/** Runs {@code work} on another thread while the test thread keeps the game ticking; rethrows its failure. */
	<T> T offThread(Supplier<T> work, int timeoutSeconds) {
		CompletableFuture<T> result = CompletableFuture.supplyAsync(work);
		context.waitFor(mc -> result.isDone(), timeoutSeconds * SECONDS);
		try {
			return result.join();
		} catch (CompletionException e) {
			if (e.getCause() instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw e;
		}
	}

	static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
