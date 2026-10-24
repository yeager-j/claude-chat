package claudechat.test;

import static claudechat.test.Game.SECONDS;

/**
 * Real questions, answered by the signed-in Claude: how to craft the held item, and a research question in a new
 * conversation, whose status line is screenshot while progress notes arrive.
 */
final class LiveStep {
	private LiveStep() {
	}

	static void run(Game game) {
		game.command("give @a minecraft:crafter");
		game.context.waitTicks(10);
		game.askClaude("how do I craft this? show the recipe", "claudechat-live-answer").requireAnswer();

		game.chat("/claude reset");
		String research = "how does the crafter decide which slot a hopper fills?";
		game.startClaude(research);
		game.context.waitTicks(2 * SECONDS);
		game.context.takeScreenshot("claudechat-status-2s");
		game.context.waitTicks(10 * SECONDS);
		if (!game.context.computeOnClient(mc -> ChatHistory.ended(mc, research))) {
			game.context.takeScreenshot("claudechat-status-12s");
		}
		game.await(research).requireAnswer();
		game.screenshot("claudechat-live-research");
	}
}
