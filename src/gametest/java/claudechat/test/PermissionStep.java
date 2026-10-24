package claudechat.test;

import claudechat.PermissionTier;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static claudechat.test.Game.LOGGER;
import static claudechat.test.Game.SECONDS;
import static claudechat.test.Game.check;

/**
 * run_command and approve as Claude calls them, in a world with cheats off: each tier and cheats setting is set with
 * /claude permissions and /claude cheats (clicking their confirm buttons), and a question the fake holds open makes
 * the calls with its token, so they get the permissions that question started with. Approval requests are answered
 * by clicking their own [Allow] and [Deny] buttons in chat. An approval clicked after its question ended, by
 * /claude reset or by answering, is too late and runs nothing.
 */
final class PermissionStep {
	/** Execute modifiers that write, ahead of a "run" whose command only reads. */
	private static final List<String> WRITING_MODIFIERS = List.of("execute summon minecraft:pig run seed",
		"execute store result entity @s Air short 1 run data get entity @s Air",
		"execute store result score @s x run time query time", "execute if function ns:x", "execute if function ns:x run seed");

	private PermissionStep() {
	}

	static void run(Game game) throws IOException {
		readOnly(game);
		full(game);
		ask(game);
		lateApprovals(game);
		if (game.options.live()) {
			live(game);
		}
	}

	/** Commands that change nothing run, the rest are refused, cheats or not; the cheats note says what's true. */
	private static void readOnly(Game game) {
		game.setPermissions(PermissionTier.READ_ONLY);
		game.setCheats(true);
		try (Game.Held claude = game.hold()) {
			for (String command : List.of("time query time", "/execute if entity @s", "gamerule keep_inventory",
				"scoreboard objectives list", "execute as @s at @s if block ~ ~-1 ~ minecraft:air")) {
				check(!runCommand(claude, command).isError(), "read-only command refused: " + command);
			}
			for (String command : List.of("gamerule keep_inventory true", "execute as @s run kill @s", "give @s minecraft:diamond",
				"execute store result score @s x if entity @s", "time set night")) {
				check(runCommand(claude, command).error().contains("Read only"), "changing command not refused: " + command);
			}
			for (String command : WRITING_MODIFIERS) {
				check(runCommand(claude, command).error().contains("Read only"), "writing execute not refused: " + command);
			}
			// /time needs command block level, which the player lacks here; /list doesn't.
			check(runCommand(claude, "time query time").object().has("note"), "time query ran with cheats but has no note");
			check(!runCommand(claude, "list").object().has("note"), "list has the cheats note though the player could run it");
		}

		game.setCheats(false);
		try (Game.Held claude = game.hold()) {
			check(runCommand(claude, "time query time").error().contains("/claude cheats on"),
				"time query ran without cheats in a world without cheats");
		}
	}

	/** Full access runs anything, at the player's own level with cheats off and at command block level with them on. */
	private static void full(Game game) {
		game.setPermissions(PermissionTier.FULL);
		try (Game.Held claude = game.hold()) {
			check(runCommand(claude, "give @s minecraft:diamond").error().contains("/claude cheats on"), "give ran without cheats");
			check(!hasDiamond(game), "diamond given without cheats");
		}

		game.setCheats(true);
		try (Game.Held claude = game.hold()) {
			JsonObject cheated = runCommand(claude, "give @s minecraft:diamond").object();
			check(cheated.get("success").getAsBoolean() && cheated.has("note"), "give with cheats failed: " + cheated);
			check(hasDiamond(game), "no diamond after give with cheats");
		}
	}

	/**
	 * Ask for approval: read-only commands don't ask; others wait for a click, and only Allow runs them. A used button
	 * does nothing. Claude Code's own prompts, through the approve tool, return exactly the input shown, including a
	 * field the request lists as "also"; outside Ask for approval approve denies outright.
	 */
	private static void ask(Game game) {
		game.setPermissions(PermissionTier.ASK);
		BlockPos below = game.playerPosition().below();
		try (Game.Held claude = game.hold()) {
			String before = game.newestButton("Allow");
			check(!runCommand(claude, "time query time").isError() && Objects.equals(before, game.newestButton("Allow")), "a read-only command asked");
			for (String command : WRITING_MODIFIERS) {
				check(withApproval(game, claude, command, false, null).error().contains("denied"), "writing execute didn't ask: " + command);
			}

			String setblock = "setblock " + below.getX() + " " + below.getY() + " " + below.getZ() + " minecraft:gold_block";
			check(withApproval(game, claude, setblock, false, "claudechat-approval-setblock").error().contains("denied"), "denied setblock ran");
			check(!blockIs(game, below, Blocks.GOLD_BLOCK), "block placed after Deny");
			JsonObject allowed = withApproval(game, claude, "fill ~-2 ~-1 ~-2 ~2 ~-1 ~2 minecraft:gold_block", true, "claudechat-approval-fill").object();
			check(allowed.get("success").getAsBoolean(), "allowed fill failed: " + allowed);
			check(blockIs(game, below, Blocks.GOLD_BLOCK), "no gold block after Allow");
			game.click(game.newestButton("Allow"));
			check(game.chatHas("That button has expired."), "a used button still did something");

			JsonObject write = JsonParser.parseString(
				"{\"tool_name\": \"Write\", \"input\": {\"file_path\": \"config/x.txt\", \"content\": \"hi\\nthere\", \"mode\": \"append\"}}")
				.getAsJsonObject();
			JsonObject decision = answering(game, true, "claudechat-approval-write", () -> claude.mcp.callTool("approve", write)).object();
			LOGGER.info("approve Write -> {}", decision);
			check(Json.is(decision, "behavior", "allow") && decision.getAsJsonObject("updatedInput").equals(
				JsonParser.parseString("{\"file_path\": \"config/x.txt\", \"content\": \"hi\\nthere\", \"mode\": \"append\"}")),
				"approve tool didn't allow with the input shown: " + decision);
			check(game.chatHas("(also mode=\"append\")"), "the extra input field wasn't shown");
		}

		game.setPermissions(PermissionTier.READ_ONLY);
		try (Game.Held claude = game.hold()) {
			JsonObject refused = claude.call("approve", "{\"tool_name\": \"Bash\", \"input\": {\"command\": \"echo hi\"}}").object();
			check(Json.is(refused, "behavior", "deny"), "approve allowed outside Ask for approval: " + refused);
		}
	}

	/**
	 * An approval belongs to its question: one clicked after the question ended, whether /claude reset cancelled it or
	 * it finished meanwhile, is too late, and its command never runs.
	 */
	private static void lateApprovals(Game game) {
		game.setPermissions(PermissionTier.ASK);
		BlockPos below = game.playerPosition().below();

		Game.Held cancelled = game.hold();
		lateAllow(game, "was reset", cancelled, below, Blocks.DIAMOND_BLOCK, () -> game.chat("/claude reset"));
		check(game.chatHas("Stopped the question and started a new conversation."), "/claude reset didn't stop the question");

		Game.Held finished = game.hold();
		lateAllow(game, "was answered", finished, below, Blocks.EMERALD_BLOCK, finished::close);
		game.setPermissions(PermissionTier.READ_ONLY);
	}

	/**
	 * Asks to place {@code block}, ends the question with {@code end} while the request waits, then clicks its Allow:
	 * the call must end without success, the click must say it's too late, and the block must not be placed.
	 */
	private static void lateAllow(Game game, String how, Game.Held claude, BlockPos at, Block block, Runnable end) {
		String before = game.newestButton("Allow");
		String command = "setblock " + at.getX() + " " + at.getY() + " " + at.getZ() + " " + block.builtInRegistryHolder().getRegisteredName();
		CompletableFuture<ToolResult> call = CompletableFuture.supplyAsync(() -> claude.mcp.callTool("run_command", runArguments(command)));
		String allow = game.awaitNewButton("Allow", before);
		end.run();
		game.context.waitFor(mc -> call.isDone(), 30 * SECONDS);
		// A revoked token may cut the connection instead of answering; either way nothing ran.
		ToolResult result = call.handle((r, e) -> r).join();
		long tooLate = game.context.computeOnClient(mc -> ChatHistory.count(mc, "Too late"));
		game.click(allow);
		game.context.waitTicks(10);

		check(result == null || result.isError(), "a request whose question " + how + " succeeded: " + result);
		check(game.context.computeOnClient(mc -> ChatHistory.count(mc, "Too late")) > tooLate,
			"an Allow clicked after its question " + how + " didn't say it was too late");
		check(!blockIs(game, at, block), "an Allow clicked after its question " + how + " placed the block");
	}

	/** Live: Claude asks, the test clicks each Allow, and the change happens. */
	private static void live(Game game) throws IOException {
		Path file = FabricLoader.getInstance().getGameDir().resolve("config/claude-permission-test.txt");
		Files.deleteIfExists(file);
		game.setPermissions(PermissionTier.ASK);
		String question = "create the file config/claude-permission-test.txt in the game folder containing the word hello,"
			+ " then set the time to midnight with a command";
		game.startClaude(question);
		String seen = game.newestButton("Allow");
		for (int i = 0; !game.context.computeOnClient(mc -> ChatHistory.ended(mc, question)); i++) {
			String last = seen;
			game.context.waitFor(mc -> !Objects.equals(ChatHistory.newestButton(mc, "Allow"), last) || ChatHistory.ended(mc, question),
				(game.options.questionTimeoutSeconds() + 60) * SECONDS);
			seen = game.newestButton("Allow");
			if (!Objects.equals(seen, last)) {
				game.context.waitTicks(10);
				game.screenshot("claudechat-live-approval-" + i);
				game.click(seen);
			}
		}

		game.await(question).requireAnswer();
		game.screenshot("claudechat-live-permissions-answer");
		check(Files.exists(file), "Claude didn't create the file after Allow");
		Files.deleteIfExists(file);
	}

	/** Runs the command, which asks: screenshots the request (unless {@code screenshot} is null) and clicks. */
	private static ToolResult withApproval(Game game, Game.Held claude, String command, boolean allow, String screenshot) {
		ToolResult result = answering(game, allow, screenshot, () -> claude.mcp.callTool("run_command", runArguments(command)));
		LOGGER.info("run_command {} ({}) -> {}", command, allow ? "allowed" : "denied", result.text());
		return result;
	}

	/**
	 * Starts {@code call}, which asks the player; waits for its request in chat, screenshots it (unless
	 * {@code screenshot} is null), clicks, and returns what the call returned.
	 */
	private static ToolResult answering(Game game, boolean allow, String screenshot, Supplier<ToolResult> call) {
		String before = game.newestButton("Allow");
		CompletableFuture<ToolResult> result = CompletableFuture.supplyAsync(call);
		game.awaitNewButton("Allow", before);
		game.context.waitTicks(5);
		if (screenshot != null) {
			game.screenshot(screenshot);
		}

		game.click(game.newestButton(allow ? "Allow" : "Deny"));
		game.context.waitFor(mc -> result.isDone(), 30 * SECONDS);
		return result.join();
	}

	private static ToolResult runCommand(Game.Held claude, String command) {
		return claude.call("run_command", runArguments(command).toString());
	}

	private static JsonObject runArguments(String command) {
		JsonObject arguments = new JsonObject();
		arguments.addProperty("command", command);
		return arguments;
	}

	private static boolean hasDiamond(Game game) {
		return game.server().computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().getInventory().contains(stack -> stack.is(Items.DIAMOND)));
	}

	private static boolean blockIs(Game game, BlockPos pos, Block block) {
		return game.server().computeOnServer(s -> s.overworld().getBlockState(pos).is(block));
	}
}
