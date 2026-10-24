package claudechat.test;

import static claudechat.test.Game.SECONDS;
import static claudechat.test.Game.check;

/**
 * A question naming a data-only mod decompiles it before Claude starts: the status line says so, and the prompt Claude
 * gets lists the mod's code. The mod is the game test's own, which {@link TestPreLaunch} leaves without code.
 */
final class ModStep {
	/** The game test's own mod: it has classes, so it starts data-only and can be decompiled. */
	static final String MOD_ID = "claudechat-test";
	private static final String MOD_NAME = "Claude Chat tests";

	private ModStep() {
	}

	static void run(Game game) {
		String question = "what does " + MOD_ID + " add?";
		game.start("answer It adds nothing; it tests Claude Chat.", question);
		game.context.waitFor(mc -> ChatHistory.has(mc, "Decompiling " + MOD_NAME), 30 * SECONDS);
		game.screenshot("claudechat-mod-status");
		game.await(question).requireAnswer();

		String prompt = game.systemPrompt();
		check(prompt.lines().anyMatch(line -> line.contains("[" + MOD_ID + "]") && line.contains("src")),
			"the prompt doesn't list the mod's decompiled code");

		game.options.question(Step.MOD).ifPresent(real -> game.askClaude(real, "claudechat-mod-answer").requireAnswer());
	}
}
