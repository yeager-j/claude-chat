package claudechat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedArgument;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
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
import net.minecraft.server.permissions.PermissionLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The run_command tool: Claude runs a game command as the player, in single-player only. Which commands need the
 * player's OK isn't left to Claude Code's permission system, which can't judge game commands (an auto mode test let
 * a 41×38×41 /fill to air through). Instead every command is parsed with the server's own dispatcher and judged by
 * {@link CommandRules}: read-only commands run in every tier; anything else, including every mod command, is refused
 * in Read only and asked about in Ask for approval. A destructive or opaque command only adds a warning to the request.
 * <p>
 * Cheats off: the player's own command source, so Claude can run exactly what the player could type. Cheats on: the
 * same source raised to command block level (GAMEMASTER), so still "@s" and "~" are the player.
 */
public class GameCommands {
	public static final String TOOL = "run_command";
	private static final Param<String> COMMAND = Param.requiredText("command",
		"The command, with or without the leading slash, e.g. \"time set day\".");

	private final ClaudeConfig config;
	private final WorldIndex index;

	public GameCommands(ClaudeConfig config, WorldIndex index) {
		this.config = config;
		this.index = index;
	}

	/** The run_command tool; its description says what the current permissions and cheats setting let it run. */
	public Tool tool() {
		Tool.Requirement singlePlayer = new Tool.Requirement(() -> index.server() != null,
			"No single-player world is open, so game commands can't run.");
		return new Tool(TOOL, this::description, List.of(COMMAND), singlePlayer,
			(arguments, context) -> Tool.Result.of(call(arguments.get(COMMAND))));
	}

	private String description() {
		String tier = switch (config.permissions) {
			case READ_ONLY -> "Only commands that change nothing (time query, data get, gamerule <name>, locate, scoreboard"
				+ " ... get, execute if/unless) run; the player's permissions are Read only, so others are refused.";
			case ASK -> "Commands that change nothing run straight away; any other command waits for the player to click"
				+ " Allow in chat, and may be denied.";
			case FULL -> "Every command runs straight away.";
		};
		String cheats = config.cheats
			? "Runs with command block permissions (cheats allowed), as the player."
			: "Runs with the player's own permissions: in a world without cheats, cheat commands (give, gamemode, fill,"
				+ " tp, data...) fail.";
		return "Run a Minecraft command in the player's single-player world, as the player (so @s and ~ are the player). "
			+ tier + " " + cheats + " Returns the game's feedback. Prefer the other world tools for finding things; use"
			+ " this for what they can't do. Every command you run is shown to the player in chat.";
	}

	/** What a command would do, worked out on the server thread before running it. */
	record Check(String error, boolean readOnly, String warning, boolean needsCheats) {
	}

	private JsonElement call(String command) throws Exception {
		String cmd = command.startsWith("/") ? command.substring(1).strip() : command;
		if (cmd.isEmpty()) {
			throw new IllegalArgumentException("command is required.");
		}
		MinecraftServer server = server();
		Check check = server.submit(() -> check(server, cmd)).get(30, TimeUnit.SECONDS);
		if (check.error() != null) {
			throw new IllegalArgumentException(check.error());
		}

		if (!check.readOnly()) {
			switch (config.permissions) {
				case READ_ONLY -> throw new IllegalStateException("The player's permissions are Read only, so only commands"
					+ " that change nothing can run. Tell the player the command instead; they can let you run commands with"
					+ " /claude permissions ask.");
				case ASK -> {
					String action = "run a game command" + (check.needsCheats() ? " (cheat)" : "");
					if (!Approvals.ask(action, Approvals.code("/" + cmd, null), check.warning())) {
						throw new IllegalStateException("The player denied this command. Don't try to do it another way; if"
							+ " it matters, say what you wanted to do and why.");
					}
				}
				case FULL -> {
				}
			}
		}
		return server.submit(() -> run(server, cmd, check)).get(60, TimeUnit.SECONDS);
	}

	/** Parses with the permissions Claude will run with; works out whether the command reads only. Server thread. */
	Check check(MinecraftServer server, String command) {
		ServerPlayer player = host(server);
		CommandSourceStack own = player.createCommandSourceStack();
		CommandSourceStack source = source(player);
		ParseResults<CommandSourceStack> parse = server.getCommands().getDispatcher().parse(command, source);
		CommandSyntaxException problem = Commands.getParseException(parse);
		if (problem != null) {
			String hint = "";
			if (!config.cheats) {
				ParseResults<CommandSourceStack> elevated = server.getCommands().getDispatcher()
					.parse(command, atLeastGamemaster(own));
				if (Commands.getParseException(elevated) == null) {
					hint = " This command needs cheats, which this world doesn't allow for the player, and Claude's cheats"
						+ " setting is off. Tell the player instead; they can allow it with /claude cheats on.";
				}
			}
			return new Check(problem.getMessage() + hint, false, null, false);
		}
		List<CommandRules.Segment> segments = CommandRules.segments(parse.getContext());
		boolean needsCheats = config.cheats && Commands.getParseException(server.getCommands().getDispatcher().parse(command, own)) != null;
		String warning = warning(CommandRules.risk(segments), parse.getContext(), source);
		return new Check(null, CommandRules.readOnly(segments), warning, needsCheats);
	}

	private JsonElement run(MinecraftServer server, String command, Check check) {
		ServerPlayer player = host(server);
		List<String> output = Collections.synchronizedList(new ArrayList<>());
		boolean[] success = {false};
		int[] result = {0};
		CommandSource capture = new CommandSource() {
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
		};
		CommandSourceStack source = source(player).withSource(capture).withCallback((ok, value) -> {
			success[0] |= ok;
			result[0] += value;
		});
		// Parsed again: the world may have changed while the player decided.
		server.getCommands().performCommand(server.getCommands().getDispatcher().parse(command, source), command);
		ClaudeChatClient.LOGGER.info("Claude ran /{} -> {} {}", command, success[0], output);
		showInChat(command, success[0], output);

		JsonObject out = new JsonObject();
		out.addProperty("command", "/" + command);
		out.addProperty("success", success[0]);
		out.addProperty("result", result[0]);
		JsonArray lines = new JsonArray();
		output.forEach(lines::add);
		out.add("output", lines);
		if (check.needsCheats()) {
			out.addProperty("note", "Ran with cheats: the player couldn't have run this themselves.");
		}
		return out;
	}

	/** Every command Claude runs is shown, so nothing happens to the world unseen, even in Full access. */
	private static void showInChat(String command, boolean success, List<String> output) {
		String feedback = output.isEmpty() ? (success ? "Done" : "Failed") : String.join("\n", output);
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player == null) {
				return;
			}
			mc.player.sendSystemMessage(Component.empty()
				.append(Component.literal("[Claude] ").withStyle(ChatFormatting.GOLD))
				.append(Component.literal("ran ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal("/" + command).withStyle(style -> style
					.withColor(success ? ChatFormatting.WHITE : ChatFormatting.RED)
					.withHoverEvent(new HoverEvent.ShowText(Component.literal(feedback))))));
		});
	}

	private CommandSourceStack source(ServerPlayer player) {
		CommandSourceStack own = player.createCommandSourceStack();
		return config.cheats ? atLeastGamemaster(own) : own;
	}

	/**
	 * Not withMaximumPermission: in 26.3 LevelBasedPermissionSet.union keeps the lower of two levels, so a player at
	 * level ALL stayed at ALL. A player who already has more (cheats on, or op) keeps it.
	 */
	private static CommandSourceStack atLeastGamemaster(CommandSourceStack source) {
		boolean enough = source.permissions() instanceof LevelBasedPermissionSet level
			&& level.level().isEqualOrHigherThan(PermissionLevel.GAMEMASTERS);
		return enough ? source : source.withPermission(LevelBasedPermissionSet.GAMEMASTER);
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
