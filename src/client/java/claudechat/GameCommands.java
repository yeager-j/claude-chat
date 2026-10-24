package claudechat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedArgument;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The run_command tool: Claude runs a game command as the player, in single-player only. Which commands need the
 * player's OK isn't left to Claude Code's permission system, which can't judge game commands (an auto mode test let
 * a 41×38×41 /fill to air through). Instead every command is parsed with the server's own dispatcher and judged by
 * {@link CommandRules}; the question's {@link Policy} then decides: read-only commands run in every tier, and anything
 * else, including every mod command, follows the tier's {@link PermissionTier.CommandRule}. A destructive or opaque
 * command only adds a warning to the request.
 * <p>
 * The command runs exactly as parsed and shown: an approved command isn't parsed again. It always runs as the player
 * who owns the world; the policy's cheats setting only raises its permission level ({@link Policy#permissions}).
 */
public class GameCommands {
	public static final String TOOL = "run_command";
	private static final Param<String> COMMAND = Param.requiredText("command",
		"The command, with or without the leading slash, e.g. \"time set day\".");
	private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(30);
	private static final Duration RUN_TIMEOUT = Duration.ofSeconds(60);

	private final WorldIndex index;

	public GameCommands(WorldIndex index) {
		this.index = index;
	}

	/** The run_command tool; its description says what the question's permissions and cheats setting let it run. */
	public Tool tool() {
		Tool.Requirement singlePlayer = new Tool.Requirement(() -> index.server() != null,
			"No single-player world is open, so game commands can't run.");
		return new Tool(TOOL, context -> description(context.policy()), List.of(COMMAND), singlePlayer,
			(arguments, context) -> Tool.Result.of(call(arguments.get(COMMAND), context)));
	}

	private static String description(Policy policy) {
		String cheats = policy.cheats()
			? "Runs as the player with at least command block permissions (cheats allowed)."
			: "Runs with the player's own permissions: in a world without cheats, cheat commands (give, gamemode, fill,"
				+ " tp, data...) fail.";
		return "Run a Minecraft command in the player's single-player world, as the player (so @s and ~ are the player). "
			+ policy.tier().commandsDescription + " " + cheats + " Returns the game's feedback. Prefer the other world tools"
			+ " for finding things; use this for what they can't do. Every command you run is shown to the player in chat.";
	}

	/**
	 * A command parsed for running, worked out on the server thread before anything runs.
	 *
	 * @param parse         what runs, as shown to the player; its source collects the game's feedback in {@code feedback}
	 * @param player        the player it runs as, so a respawn or rejoin while the player decides isn't run as someone else
	 * @param ranWithCheats whether the player couldn't have run it with their own permissions
	 */
	private record Prepared(String command, ParseResults<CommandSourceStack> parse, ServerPlayer player, boolean readOnly,
		String warning, boolean ranWithCheats, Feedback feedback) {
	}

	private JsonElement call(String command, Tool.Context context) throws Exception {
		String cmd = command.startsWith("/") ? command.substring(1).strip() : command;
		if (cmd.isEmpty()) {
			throw new IllegalArgumentException("command is required.");
		}

		Policy policy = context.policy();
		MinecraftServer server = server();
		Prepared prepared = ServerTasks.call(server, () -> prepare(server, cmd, policy), CHECK_TIMEOUT, "Checking the command");
		switch (policy.commandRule(prepared.readOnly())) {
			case RUN -> {
			}
			case ASK -> {
				String action = "run a game command" + (prepared.ranWithCheats() ? " (cheat)" : "");
				if (!context.approvals().ask(action, Approvals.code("/" + cmd, null), prepared.warning())) {
					throw new IllegalStateException(Approvals.DENIED);
				}
			}
			case REFUSE -> throw new IllegalStateException("The player's permissions are " + policy.tier().label
				+ ", so only commands that change nothing can run. Tell the player the command instead; they can let you"
				+ " run commands with /claude permissions " + PermissionTier.ASK.id + ".");
		}

		return ServerTasks.call(server, () -> execute(server, prepared, context), RUN_TIMEOUT, "The command");
	}

	/** Parses with the permissions the command will run with, and works out whether it only reads. Server thread. */
	private static Prepared prepare(MinecraftServer server, String command, Policy policy) {
		CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
		ServerPlayer player = host(server);
		CommandSourceStack own = player.createCommandSourceStack();
		Feedback feedback = new Feedback();
		CommandSourceStack source = own.withPermission(policy.permissions(own.permissions()))
			.withSource(feedback)
			.withCallback(feedback);
		ParseResults<CommandSourceStack> parse = dispatcher.parse(command, source);
		CommandSyntaxException problem = Commands.getParseException(parse);
		if (problem != null) {
			throw new IllegalArgumentException(problem.getMessage() + cheatsHint(dispatcher, command, own, policy));
		}

		List<CommandRules.Segment> segments = CommandRules.segments(parse.getContext());
		boolean ranWithCheats = policy.cheats() && Commands.getParseException(dispatcher.parse(command, own)) != null;
		String warning = warning(CommandRules.risk(segments), parse.getContext(), source);
		return new Prepared(command, parse, player, CommandRules.readOnly(segments), warning, ranWithCheats, feedback);
	}

	/** Why a command that didn't parse would have with cheats, when that's the reason. */
	private static String cheatsHint(CommandDispatcher<CommandSourceStack> dispatcher, String command, CommandSourceStack own,
		Policy policy) {
		if (policy.cheats()) {
			return "";
		}

		CommandSourceStack cheating = own.withPermission(LevelBasedPermissionSet.GAMEMASTER);
		boolean parsesWithCheats = Commands.getParseException(dispatcher.parse(command, cheating)) == null;
		return parsesWithCheats ? " This command needs cheats, which this world doesn't allow for the player, and Claude's"
			+ " cheats setting is off. Tell the player instead; they can allow it with /claude cheats on." : "";
	}

	/** Runs the prepared parse, the one the player saw and approved. Server thread. */
	private JsonElement execute(MinecraftServer server, Prepared prepared, Tool.Context context) {
		// The question may have ended while the player decided or this waited for the server thread.
		context.throwIfEnded();
		if (index.server() != server || host(server) != prepared.player()) {
			throw new IllegalStateException("The world or the player changed before the command ran, so it didn't. Run it again"
				+ " if it's still wanted.");
		}

		server.getCommands().performCommand(prepared.parse(), prepared.command());
		Feedback feedback = prepared.feedback();
		ClaudeChatClient.LOGGER.info("Claude ran /{} -> {} {}", prepared.command(), feedback.success, feedback.output);
		showInChat(prepared.command(), feedback.success, feedback.output);

		JsonObject out = new JsonObject();
		out.addProperty("command", "/" + prepared.command());
		out.addProperty("success", feedback.success);
		out.addProperty("result", feedback.result);
		JsonArray lines = new JsonArray();
		feedback.output.forEach(lines::add);
		out.add("output", lines);
		if (prepared.ranWithCheats()) {
			out.addProperty("note", "Ran at command block level (Claude's cheats setting): the player couldn't have run this"
				+ " themselves.");
		}
		return out;
	}

	/** What the game says while a command runs, and its result. */
	private static final class Feedback implements CommandSource, CommandResultCallback {
		final List<String> output = Collections.synchronizedList(new ArrayList<>());
		volatile boolean success;
		volatile int result;

		@Override
		public void sendSystemMessage(Component message) {
			output.add(message.getString());
		}

		@Override
		public boolean acceptsSuccess() {
			return true;
		}

		@Override
		public boolean acceptsFailure() {
			return true;
		}

		@Override
		public boolean shouldInformAdmins() {
			return false;
		}

		@Override
		public void onResult(boolean ok, int value) {
			success |= ok;
			result += value;
		}
	}

	/** Every command Claude runs is shown, so nothing happens to the world unseen, even in Full access. */
	private static void showInChat(String command, boolean success, List<String> output) {
		String feedback = output.isEmpty() ? (success ? "Done" : "Failed") : String.join("\n", output);
		ChatOutput.sendLater(ChatOutput.line(Component.empty()
			.append(Component.literal("ran ").withStyle(ChatFormatting.GRAY))
			.append(Component.literal("/" + command).withStyle(style -> style
				.withColor(success ? ChatFormatting.WHITE : ChatFormatting.RED)
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(feedback)))))));
	}

	private MinecraftServer server() {
		MinecraftServer server = index.server();
		if (server == null) {
			throw new IllegalStateException("No single-player world is open, so game commands can't run.");
		}
		return server;
	}

	/**
	 * The player whose world this is ({@link WorldIndex#host}). Never anyone else: with the world open to LAN, a guest
	 * could be first in the player list. Server thread.
	 */
	private static ServerPlayer host(MinecraftServer server) {
		return WorldIndex.host(server).orElseThrow(() ->
			new IllegalStateException("The player who owns this world isn't in it, so game commands can't run."));
	}

	private static String warning(CommandRules.Risk risk, CommandContextBuilder<CommandSourceStack> context, CommandSourceStack source) {
		return switch (risk) {
			case NONE -> null;
			case OPAQUE -> "Runs data pack code: what it does can't be checked in advance.";
			case DESTRUCTIVE -> {
				long blocks = volume(context, source);
				yield blocks > 0 ? String.format(Locale.ROOT, "Changes up to %,d blocks. This can't be undone.", blocks) : "This can't be undone.";
			}
		};
	}

	/** Blocks in a fill, fillbiome or clone area; 0 for other commands. */
	private static long volume(CommandContextBuilder<CommandSourceStack> context, CommandSourceStack source) {
		for (CommandContextBuilder<CommandSourceStack> c = context; c != null; c = c.getChild()) {
			BlockPos a = position(c, "from", source);
			BlockPos b = position(c, "to", source);
			if (a == null || b == null) {
				a = position(c, "begin", source);
				b = position(c, "end", source);
			}
			if (a != null && b != null) {
				return (long) (Math.abs(a.getX() - b.getX()) + 1) * (Math.abs(a.getY() - b.getY()) + 1) * (Math.abs(a.getZ() - b.getZ()) + 1);
			}
		}
		return 0;
	}

	private static BlockPos position(CommandContextBuilder<CommandSourceStack> context, String name, CommandSourceStack source) {
		ParsedArgument<CommandSourceStack, ?> argument = context.getArguments().get(name);
		return argument != null && argument.getResult() instanceof Coordinates coordinates ? coordinates.getBlockPos(source) : null;
	}
}
