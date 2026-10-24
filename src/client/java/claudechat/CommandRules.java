package claudechat;

import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which game commands run_command may run without asking, judged from the parse tree rather than the command text.
 * <p>
 * A parsed command is a chain of {@link Segment}s, one per Brigadier context: every redirect ({@code execute … run},
 * an {@code execute} modifier, an alias like {@code xp}) starts a new one. A command is read-only only when every
 * segment before the last changes nothing (an exact {@link #MODIFIERS} shape, or a redirect that runs no code) and
 * the last one is a {@link #READ_ONLY} command that Minecraft itself registered.
 * <p>
 * Only Minecraft's own nodes count. A command's code must come from a {@code net.minecraft} class, so a mod's command
 * under a read-only root ({@code data get}, {@code locate biome}) never reads as read-only. Modifiers are matched by their
 * whole path of node names instead: Brigadier merges a same-named node into the existing one and keeps the existing
 * redirect, so an exact vanilla path can only reach Minecraft's own modifier.
 * <p>
 * Pure, and generic over the command source, so tests can classify against a dispatcher built from vanilla commands.
 */
final class CommandRules {
	/*
	 * Pattern syntax: words are literal parts of the command, "<name>" is an argument by its registered name, "*" is
	 * any one part, "a|b" is either word, and a trailing "$" means nothing may follow. Without "$" a pattern is a prefix.
	 */

	/**
	 * Commands that change nothing. A bare {@code execute if …} only reports whether the condition holds. Of
	 * {@code locate}, only {@code biome} qualifies: it samples the seed's climate, while {@code locate structure} loads
	 * candidate chunks to {@code structure_starts} and saves them, and {@code locate poi} loads chunks too.
	 */
	static final List<String> READ_ONLY = List.of(
		"help", "list", "seed", "locate biome", "version", "time query", "time of * query", "data get", "tick query", "team list",
		"bossbar list", "bossbar get", "forceload query", "datapack list", "worldborder get", "experience query",
		"scoreboard objectives list", "scoreboard players list", "scoreboard players get",
		"attribute * * get", "attribute * * base get", "attribute * * modifier value get",
		"difficulty$", "gamerule *$", "execute if|unless");

	/**
	 * {@code execute} modifiers that only change who, where or whether the rest runs, each in its exact vanilla shape.
	 * Not {@code store} (writes the result), {@code summon} (spawns an entity) or {@code if|unless function} (runs data
	 * pack code).
	 */
	static final List<String> MODIFIERS = List.of(
		"execute as <targets>$", "execute at <targets>$", "execute positioned <pos>$", "execute positioned as <targets>$",
		"execute positioned over <heightmap>$", "execute rotated <rot>$", "execute rotated as <targets>$",
		"execute facing <pos>$", "execute facing entity <targets> <anchor>$", "execute align <axes>$",
		"execute anchored <anchor>$", "execute in <dimension>$",
		"execute on owner|leasher|target|attacker|vehicle|controller|origin|passengers$",
		"execute if|unless block <pos> <block>$", "execute if|unless biome <pos> <biome>$", "execute if|unless loaded <pos>$",
		"execute if|unless dimension <dimension>$",
		"execute if|unless score <target> <targetObjective> =|<|<=|>|>= <source> <sourceObjective>$",
		"execute if|unless score <target> <targetObjective> matches <range>$",
		"execute if|unless blocks <start> <end> <destination> all|masked$", "execute if|unless entity <entities>$",
		"execute if|unless predicate <predicate>$", "execute if|unless stopwatch <id> <range>$",
		"execute if|unless data block|entity|storage <source> <path>$",
		"execute if|unless items block|entity <source> <slots> <item_predicate>$",
		"execute if|unless slots block|entity <source> <slots>$");

	/** Commands that destroy or overwrite something the player can't easily get back. */
	static final List<String> DESTRUCTIVE = List.of(
		"clear", "kill", "fill", "fillbiome", "clone", "setblock", "place", "item", "data modify", "data merge",
		"data remove", "gamerule * *", "experience set", "worldborder set", "worldborder add", "worldborder center",
		"forceload", "damage", "spreadplayers", "effect clear", "attribute * * base set", "attribute * * modifier remove",
		"loot replace", "scoreboard players reset", "scoreboard objectives remove", "team remove", "datapack disable",
		"execute store");

	/** Data pack code whose effect can't be told from the command line. */
	static final List<String> OPAQUE = List.of("function", "reload", "schedule", "execute if|unless function");

	/** The first word of a segment whose start isn't in the command tree, so no pattern matches it. */
	private static final String UNREGISTERED = "<unregistered>";

	/** How a segment ends: what runs when the chain reaches it. */
	enum Ending {
		/** Runs a command: the last segment of a complete command. */
		COMMAND,
		/** Redirects through a modifier, which runs code (moves, forks, filters, stores, spawns…). */
		MODIFIER,
		/** Redirects without a modifier ({@code execute run}, an alias): nothing runs here. */
		PASS_THROUGH,
		/** Neither: the input stopped short of anything that runs. */
		INCOMPLETE
	}

	/** Nothing to say, overwrites something, or runs data pack code. */
	enum Risk {
		NONE, DESTRUCTIVE, OPAQUE
	}

	/**
	 * One context of a parsed command.
	 *
	 * @param words          the path from the dispatcher root: literals lower-cased, arguments as "&lt;name&gt;"; a
	 *                       segment that starts at a redirect target begins with that target's own path, so an alias
	 *                       reads as the command it stands for
	 * @param vanillaCommand for {@link Ending#COMMAND}, whether the command's code is Minecraft's own; false otherwise
	 */
	record Segment(List<String> words, Ending ending, boolean vanillaCommand) {
	}

	private CommandRules() {
	}

	/** The parse's contexts as segments, in the order they run. */
	static <S> List<Segment> segments(CommandContextBuilder<S> parse) {
		CommandNode<S> root = parse.getDispatcher().getRoot();
		List<Segment> segments = new ArrayList<>();

		for (CommandContextBuilder<S> context = parse; context != null; context = context.getChild()) {
			List<String> words = new ArrayList<>(pathTo(root, context.getRootNode()));

			for (ParsedCommandNode<S> parsed : context.getNodes()) {
				words.add(word(parsed.getNode()));
			}

			segments.add(new Segment(words, ending(context), isVanilla(context.getCommand())));
		}

		return segments;
	}

	private static <S> Ending ending(CommandContextBuilder<S> context) {
		if (context.getChild() == null) {
			return context.getCommand() == null ? Ending.INCOMPLETE : Ending.COMMAND;
		}

		CommandNode<S> redirecting = context.getNodes().getLast().getNode();

		return redirecting.getRedirectModifier() == null ? Ending.PASS_THROUGH : Ending.MODIFIER;
	}

	/** Does the command change nothing? See the class Javadoc for the rule. */
	static boolean readOnly(List<Segment> segments) {
		if (segments.isEmpty()) {
			return false;
		}

		List<Segment> before = segments.subList(0, segments.size() - 1);

		if (!before.stream().allMatch(CommandRules::changesNothing)) {
			return false;
		}

		Segment command = segments.getLast();

		return command.ending() == Ending.COMMAND && command.vanillaCommand() && matchesAny(READ_ONLY, command.words());
	}

	private static boolean changesNothing(Segment hop) {
		return switch (hop.ending()) {
			case PASS_THROUGH -> true;
			case MODIFIER -> matchesAny(MODIFIERS, hop.words());
			case COMMAND, INCOMPLETE -> false;
		};
	}

	/** The strongest warning any segment earns; data pack code outranks destruction, since neither can be checked. */
	static Risk risk(List<Segment> segments) {
		if (segments.stream().anyMatch(segment -> matchesAny(OPAQUE, segment.words()))) {
			return Risk.OPAQUE;
		}

		if (segments.stream().anyMatch(segment -> matchesAny(DESTRUCTIVE, segment.words()))) {
			return Risk.DESTRUCTIVE;
		}

		return Risk.NONE;
	}

	static boolean matchesAny(List<String> patterns, List<String> words) {
		return patterns.stream().anyMatch(pattern -> matches(pattern, words));
	}

	/** "data get" matches [data, get, entity, …]; "gamerule *$" matches exactly two words; "if|unless" either word. */
	static boolean matches(String pattern, List<String> words) {
		boolean exact = pattern.endsWith("$");
		String[] parts = (exact ? pattern.substring(0, pattern.length() - 1) : pattern).split(" ");

		if (words.size() < parts.length || (exact && words.size() != parts.length)) {
			return false;
		}

		for (int i = 0; i < parts.length; i++) {
			if (!parts[i].equals("*") && !List.of(parts[i].split("\\|")).contains(words.get(i))) {
				return false;
			}
		}

		return true;
	}

	/**
	 * Registered by Minecraft itself: vanilla commands are lambdas in {@code net.minecraft} classes, and a lambda's
	 * class is named after the class that wrote it. A mod's command, or a mod's replacement of a vanilla node's
	 * command, is not.
	 */
	private static boolean isVanilla(Object command) {
		return command != null && command.getClass().getName().startsWith("net.minecraft.");
	}

	private static String word(CommandNode<?> node) {
		return node instanceof LiteralCommandNode<?> literal
			? literal.getLiteral().toLowerCase(Locale.ROOT)
			: "<" + node.getName() + ">";
	}

	/**
	 * The words from the root to {@code target}: empty for the root, ["experience"] for the node {@code xp} redirects
	 * to. Nodes are compared by identity, since {@link CommandNode#equals} compares structure.
	 */
	private static <S> List<String> pathTo(CommandNode<S> root, CommandNode<S> target) {
		if (target == root) {
			return List.of();
		}

		Deque<String> path = new ArrayDeque<>();
		Set<CommandNode<S>> visited = Collections.newSetFromMap(new IdentityHashMap<>());

		return findPath(root, target, path, visited) ? List.copyOf(path) : List.of(UNREGISTERED);
	}

	private static <S> boolean findPath(CommandNode<S> node, CommandNode<S> target, Deque<String> path, Set<CommandNode<S>> visited) {
		for (CommandNode<S> child : node.getChildren()) {
			if (!visited.add(child)) {
				continue;
			}

			path.addLast(word(child));

			if (child == target || findPath(child, target, path, visited)) {
				return true;
			}

			path.removeLast();
		}

		return false;
	}
}
