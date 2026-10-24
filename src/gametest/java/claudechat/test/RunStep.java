package claudechat.test;

import claudechat.ClaudeConfig;
import claudechat.PermissionTier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static claudechat.test.Game.check;

/**
 * Question lifecycles, with the fake: an error, a lost session, the tier a question keeps when it changes mid-question
 * and its token dying with it, a question stopped at the timeout with the process it started, and a disconnect
 * mid-question whose late answer must never reach the next world.
 */
final class RunStep {
	private RunStep() {
	}

	static void run(Game game) throws IOException {
		// An error result shows Claude Code's own message.
		game.ask("error Fake failure: the model is overloaded", "fail, please").requireError("Fake failure: the model is overloaded");

		// A follow-up whose session is gone is asked again in a new conversation.
		check(game.ask("missing-session Answer in a fresh conversation", "and now?").requireAnswer().text().contains("Answer in a fresh conversation"),
			"no answer after the lost session");
		List<String> calls = game.fakeCalls();
		check(calls.size() >= 2 && calls.get(calls.size() - 2).contains("--resume fake-session-error")
			&& !calls.getLast().contains("--resume"), "the lost session wasn't retried fresh: " + calls);

		tierKept(game);
		timeout(game);
		disconnect(game);
	}

	/** The tier a question starts with is the one its run_command calls get, whatever changes meanwhile; its token dies with it. */
	private static void tierKept(Game game) throws IOException {
		game.setPermissions(PermissionTier.READ_ONLY);
		String question = "put a gold block down";
		game.start("run_command setblock 3 -55 3 minecraft:gold_block", question);
		game.awaitStarted();
		game.setPermissions(PermissionTier.FULL);
		game.go();
		check(game.await(question).requireAnswer().text().contains("run_command said: Error: The player's permissions are Read only"),
			"run_command didn't use the tier the question started with");
		check(!game.server().computeOnServer(s -> s.overworld().getBlockState(new BlockPos(3, -55, 3)).is(Blocks.GOLD_BLOCK)),
			"the block was placed with the tier set mid-question");
		check(!game.fakeCalls().getLast().contains("bypassPermissions"), "the question was started with Full access flags");
		check(McpClient.fromClaudeConfig(FakeClaude.mcpConfig(game.fake)).pingStatus() == 401, "the finished question's token still works");
		try (Stream<Path> left = Files.list(ClaudeConfig.dir().resolve("runs"))) {
			List<Path> files = left.toList();
			check(files.isEmpty(), "question files left behind: " + files);
		}
		game.setPermissions(PermissionTier.READ_ONLY);
	}

	/** A question that never ends is stopped at the timeout the config sets, with the process it started. */
	private static void timeout(Game game) {
		String question = "never answer";
		game.start("hang", question);
		List<Long> hung = game.awaitStarted();
		check(hung.size() == 2, "the fake didn't start its child: " + hung);
		game.await(question).requireError("Claude took longer than " + game.options.questionTimeoutSeconds() + "s and was stopped.");
		game.awaitDead(hung);
	}

	/**
	 * Leaving the world mid-question cancels it: its process dies, and its answer, given after it's gone, never shows
	 * in the next world, which starts a fresh conversation.
	 */
	private static void disconnect(Game game) {
		game.start("late-answer Late answer from the old world", "answer later");
		List<Long> abandoned = game.awaitStarted();
		game.leaveWorld();
		game.awaitDead(abandoned);
		game.go();

		game.openWorld(Step.RUN.world);
		check(game.ask("answer Fresh answer in the new world", "anything new?").requireAnswer().text().contains("Fresh answer in the new world"),
			"no answer in the new world");
		check(!game.chatHas("Late answer from the old world"), "the old world's answer arrived");
		check(!game.chatHas("Still thinking"), "the old world's question still counted as running");
		check(!game.fakeCalls().getLast().contains("--resume"), "the new world continued the old conversation");
		game.screenshot("claudechat-runs");
	}
}
