package claudechat.test;

import claudechat.ClaudeConfig;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.HoverEvent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static claudechat.test.Game.SECONDS;
import static claudechat.test.Game.check;

/**
 * What every run checks in a world: the world tools are offered, /claude preview and recipe grids draw, tab completion
 * offers the effort levels, /claude knowledge lists the sources, Claude starts in the knowledge folder, and the status
 * line updates in place until the answer replaces it.
 */
final class BasicsStep {
	private static final String PROGRESS_NOTE = "Checking the fake data";

	private BasicsStep() {
	}

	static void run(Game game) {
		List<String> tools = game.toolNames();
		check(tools.containsAll(List.of("decompile_mod", "approve", "look", "find_structures", "run_command")), "world tools missing: " + tools);

		preview(game);
		recipes(game);
		tabCompletion(game);
		knowledge(game);
		statusLine(game);
	}

	/** Every kind of formatting, through the real command: headings, recipe grids, a cooking note, the copy button. */
	private static void preview(Game game) {
		game.chat("/claude preview");
		game.context.waitTicks(5);
		check(game.chatHas("Crafter basics") && game.chatHas("block/crafter_top") && game.chatHas("10s, 0.7 xp"),
			"the preview is missing its heading, grid or cooking note:\n" + game.chatText());
		check(game.context.computeOnClient(mc -> ChatHistory.lines(mc).stream().anyMatch(ChatHistory::endsAnswer)
			&& ChatHistory.lines(mc).stream().noneMatch(ChatHistory::isError)), "the preview has no copy button, or an error");
		game.screenshot("claudechat-preview");
	}

	/**
	 * Recipe grids in an answer, from the world's recipes: a shaped recipe as three rows, a cooking recipe with its
	 * station, and a trim recipe (no fixed result) as a row of icons. An icon's text is its sprite ("[item/…@items]",
	 * or a block's face for block items); a name instead of one would read "[Smithing Template]". An answer starting
	 * with a grid puts the prefix on a line of its own.
	 */
	private static void recipes(Game game) {
		Game.Answer answer = game.ask("answer [[recipe:minecraft:crafter]]\n[[recipe:minecraft:iron_ingot_from_smelting_raw_iron]]\n"
			+ "[[recipe:minecraft:sentry_armor_trim_smithing_template_smithing_trim]]\nThree recipes.", "show me three recipes").requireAnswer();
		List<String> lines = answer.lines();
		int cooking = indexOf(lines, "item/raw_iron");
		check(lines.getFirst().strip().equals("[Claude]"), "the prefix isn't on a line of its own before the grid: " + lines);
		check(cooking == 4 && lines.subList(1, 4).stream().filter(line -> line.contains("→")).count() == 1
			&& lines.get(2).contains("→ [block/crafter_top] Crafter"), "the crafter isn't a three-row grid with its result: " + lines);
		check(lines.get(cooking).contains("Furnace, 10s"), "the cooking recipe has no station: " + lines.get(cooking));
		String trim = lines.get(cooking + 1);
		check(trim.contains("item/sentry_armor_trim_smithing_template") && !trim.contains("[Smithing Template]"),
			"the trim recipe has no icons: " + trim);
		game.screenshot("claudechat-recipes");
		workingDirectory(game);
		webSetting(game);
	}

	/**
	 * Web access is off unless the player turns it on with a confirm click: off, a question's claude isn't offered the
	 * web tools and its prompt says so; on, it is. Turned off again for the steps after.
	 */
	private static void webSetting(Game game) {
		checkWeb(game, false);
		game.setWeb(true);
		game.ask("answer Web on.", "can you search the web?").requireAnswer();
		checkWeb(game, true);
		game.setWeb(false);
		game.ask("answer Web off.", "can you search the web now?").requireAnswer();
		checkWeb(game, false);
	}

	/** The last question's arguments and prompt, as the fake got them. */
	private static void checkWeb(Game game, boolean on) {
		String arguments = game.fakeCalls().getLast();
		String prompt = game.systemPrompt();
		check(arguments.contains("WebFetch") == on && arguments.contains("WebSearch") == on,
			"web " + (on ? "on" : "off") + " but claude got: " + arguments);
		check(prompt.contains("Web access is off") != on, "web " + (on ? "on" : "off") + " but the prompt disagrees");
	}

	/** Claude starts in the knowledge folder: the reads Read only pre-approves ({@code Read(./**)}) are relative to it. */
	private static void workingDirectory(Game game) {
		try {
			Path knowledge = ClaudeConfig.dir().resolve("knowledge").toRealPath();
			Path started = game.claudeWorkingDirectory().toRealPath();
			check(started.equals(knowledge), "claude started in " + started + ", not the knowledge folder " + knowledge);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static int indexOf(List<String> lines, String text) {
		return IntStream.range(0, lines.size()).filter(i -> lines.get(i).contains(text)).findFirst().orElse(-1);
	}

	/** What the chat screen offers after "/claude effort ", from the same dispatcher it asks. */
	private static void tabCompletion(Game game) {
		game.openChat();
		game.context.getInput().typeChars("/claude effort ");
		game.context.waitTicks(10);
		game.context.takeScreenshot("claudechat-autocomplete");
		CompletableFuture<Suggestions> pending = game.context.computeOnClient(mc -> {
			var connection = mc.player.connection;
			return connection.getCommands().getCompletionSuggestions(connection.getCommands().parse("claude effort ", connection.getSuggestionsProvider()));
		});
		game.context.waitFor(mc -> pending.isDone(), 5 * SECONDS);
		List<String> offered = pending.join().getList().stream().map(Suggestion::getText).toList();
		game.closeChat();
		check(offered.containsAll(List.of("default", "low", "medium", "high", "xhigh", "max")) && offered.size() == 6,
			"tab completion doesn't offer the effort levels: " + offered);
	}

	/** Minecraft with decompiled code, ready (decompile_mod waited for it); the test mod among the data-only ones. */
	private static void knowledge(Game game) {
		game.chat("/claude knowledge");
		game.context.waitTicks(5);
		List<String> hovers = game.context.computeOnClient(mc -> ChatHistory.lines(mc).stream()
			.flatMap(line -> ChatHistory.segments(line.content()).stream())
			.map(segment -> segment.style().getHoverEvent() instanceof HoverEvent.ShowText(var text) ? text.getString() : "")
			.filter(text -> !text.isEmpty()).toList());
		check(game.chatHas("Knowledge base: decompiled code for these") && hovers.stream().anyMatch(hover -> hover.startsWith("minecraft: ready")),
			"Minecraft isn't listed as ready: " + hovers);
		check(game.chatHas("Data only; code is decompiled when a question needs it")
			&& hovers.stream().anyMatch(hover -> hover.startsWith(ModStep.MOD_ID + ": ") && hover.contains("decompiled when needed")),
			"the test mod isn't listed as data only: " + hovers);
		game.screenshot("claudechat-knowledge");
	}

	/**
	 * The status line shows a progress note, stays one line while its timer counts up (replaced in place, newest), and
	 * is gone once the answer arrives.
	 */
	private static void statusLine(Game game) {
		String question = "what does the fake know?";
		game.start("progress Fake answer about crafters", question);
		game.context.waitFor(mc -> ChatHistory.has(mc, PROGRESS_NOTE), 30 * SECONDS);
		String first = onlyStatusLine(game);
		game.context.waitTicks(3 * SECONDS);
		String later = onlyStatusLine(game);
		check(!later.equals(first), "the status line's timer didn't move: " + first);
		game.go();

		Game.Answer answer = game.await(question).requireAnswer();
		check(answer.text().contains("Fake answer about crafters"), "the fake's answer isn't in chat: " + answer.lines());
		check(!game.chatHas(PROGRESS_NOTE), "the status line stayed after the answer");
	}

	/** The one status line, which must be the newest line in chat. */
	private static String onlyStatusLine(Game game) {
		return game.context.computeOnClient(mc -> {
			List<GuiMessage> lines = ChatHistory.lines(mc);
			check(ChatHistory.count(mc, PROGRESS_NOTE) == 1 && lines.getFirst().content().getString().contains(PROGRESS_NOTE),
				"the status line isn't one line at the bottom of chat");
			return lines.getFirst().content().getString();
		});
	}
}
