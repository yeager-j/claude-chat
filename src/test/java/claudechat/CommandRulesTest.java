package claudechat;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * run_command's read-only rule against Minecraft's real command tree. The mutating table is the negative control: any
 * entry that classifies as read-only would run unasked in Read only and in Ask for approval.
 */
class CommandRulesTest {
	private static CommandBuildContext buildContext;
	/** Command block level, as run_command parses with cheats on. */
	private static CommandSourceStack source;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		buildContext = Commands.createValidationContext(VanillaRegistries.createWorldLookup());
		source = Commands.createCompilationContext(LevelBasedPermissionSet.GAMEMASTER);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"seed", "help", "help give", "list", "list uuids", "time query time", "data get entity @s",
		"data get entity @s Health", "gamerule keep_inventory", "difficulty", "scoreboard objectives list",
		"scoreboard players get @s points", "attribute @s minecraft:max_health get", "locate biome minecraft:plains",
		"xp query @s levels", "experience query @s points",
		"execute if entity @s", "execute unless entity @s", "execute as @s at @s if block ~ ~-1 ~ minecraft:air",
		"execute as @a at @s positioned ~ ~1 ~ run data get entity @s", "execute unless entity @s run time query time",
		"execute on vehicle run seed", "execute in minecraft:the_nether run locate biome minecraft:soul_sand_valley",
		"execute facing entity @s eyes rotated as @s anchored eyes align xyz run seed", "execute run xp query @s levels",
		"execute positioned over world_surface if loaded ~ ~ ~ run seed",
		// Every modifier shape in MODIFIERS.
		"execute as @s run seed", "execute at @s run seed", "execute positioned ~ ~ ~ run seed",
		"execute positioned as @s run seed", "execute rotated ~ ~ run seed", "execute facing ~ ~ ~ run seed",
		"execute on owner run seed", "execute on passengers run seed", "execute if block ~ ~ ~ minecraft:stone run seed",
		"execute unless biome ~ ~ ~ minecraft:plains run seed", "execute if dimension minecraft:overworld run seed",
		"execute if score @s points = @s other run seed", "execute if score @s points >= @s other run seed",
		"execute if score @s points matches 1.. run seed", "execute if blocks ~ ~ ~ ~1 ~1 ~1 ~ ~5 ~ masked run seed",
		"execute if predicate ns:p run seed", "execute if stopwatch ns:w 1.. run seed",
		"execute if data entity @s Health run seed", "execute if data storage ns:s path run seed",
		"execute if data block ~ ~ ~ Items run seed", "execute if items entity @s weapon.mainhand minecraft:stick run seed",
		"execute if slots entity @s weapon.mainhand run seed"
	})
	void readOnly(String command) {
		List<CommandRules.Segment> segments = segments(vanilla(), command);

		assertTrue(CommandRules.readOnly(segments), () -> command + " -> " + segments);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		// The shapes of review issue #1: the part after "run" reads, but an execute modifier before it writes.
		"execute summon minecraft:wither run seed",
		"execute store result entity @s Health float 1 run data get entity @s Health",
		"execute store result score @s points run time query time",
		"execute store result score @s points if entity @s",
		"execute store success bossbar minecraft:x value run seed",
		"execute if function ns:x", "execute if function ns:x run seed", "execute unless function ns:x run seed",
		"execute as @s run execute summon minecraft:pig run seed",
		"execute run execute store success score @s points run seed",
		"execute as @s if entity @s store result score @s points run seed",
		// Plain mutating commands, directly, through execute and through aliases.
		"execute as @s run kill @s", "gamerule keep_inventory true", "give @s minecraft:diamond", "time set night",
		"xp set @s 1", "experience add @s 5", "tp @s ~ ~1 ~", "function ns:x", "data modify entity @s Health set value 1f",
		"setblock ~ ~-1 ~ minecraft:gold_block", "fill ~ ~ ~ ~1 ~1 ~1 minecraft:air", "reload",
		// Structure and POI searches load chunks and save their structure starts.
		"locate structure minecraft:village", "execute in minecraft:the_nether run locate structure minecraft:fortress",
		"locate poi minecraft:meeting",
		// Incomplete: nothing that runs is read-only.
		"execute", "execute as @s"
	})
	void mutating(String command) {
		List<CommandRules.Segment> segments = segments(vanilla(), command);

		assertFalse(CommandRules.readOnly(segments), () -> command + " -> " + segments);
	}

	@Test
	void aliasesReadAsTheirTarget() {
		List<CommandRules.Segment> segments = segments(vanilla(), "xp query @s levels");

		assertEquals(List.of("experience", "query", "<target>", "levels"), segments.getLast().words());
		assertEquals(CommandRules.Risk.DESTRUCTIVE, CommandRules.risk(segments(vanilla(), "xp set @s 1")));
	}

	@Test
	void risks() {
		assertEquals(CommandRules.Risk.NONE, CommandRules.risk(segments(vanilla(), "seed")));
		assertEquals(CommandRules.Risk.OPAQUE, CommandRules.risk(segments(vanilla(), "execute if function ns:x run seed")));
		assertEquals(CommandRules.Risk.OPAQUE, CommandRules.risk(segments(vanilla(), "function ns:x")));
		assertEquals(CommandRules.Risk.DESTRUCTIVE, CommandRules.risk(segments(vanilla(), "execute store result score @s points run seed")));
		assertEquals(CommandRules.Risk.DESTRUCTIVE, CommandRules.risk(segments(vanilla(), "execute as @s run kill @s")));
	}

	@ParameterizedTest
	@ValueSource(strings = {"data get", "locate biome", "help", "list"})
	void modChildUnderReadOnlyRootIsNot(String root) {
		CommandDispatcher<CommandSourceStack> dispatcher = vanilla();
		String[] words = root.split(" ");
		var modded = Commands.literal(words[words.length - 1]).then(Commands.literal("modthing").executes(c -> 1));

		for (int i = words.length - 2; i >= 0; i--) {
			modded = Commands.literal(words[i]).then(modded);
		}

		dispatcher.register(modded);

		assertFalse(CommandRules.readOnly(segments(dispatcher, root + " modthing")), root);
	}

	@Test
	void modReplacingAVanillaCommandIsNotReadOnly() {
		CommandDispatcher<CommandSourceStack> dispatcher = vanilla();
		dispatcher.register(Commands.literal("seed").executes(c -> 1));

		assertFalse(CommandRules.readOnly(segments(dispatcher, "seed")));
	}

	@Test
	void modExecuteModifiersAreNotReadOnly() {
		CommandDispatcher<CommandSourceStack> dispatcher = vanilla();
		CommandNode<CommandSourceStack> execute = dispatcher.getRoot().getChild("execute");
		dispatcher.register(Commands.literal("execute")
			.then(Commands.literal("modhop").redirect(execute, c -> c.getSource()))
			.then(Commands.literal("if").then(Commands.literal("modcheck").fork(execute, c -> List.of(c.getSource())).executes(c -> 1))));

		assertFalse(CommandRules.readOnly(segments(dispatcher, "execute modhop run seed")));
		assertFalse(CommandRules.readOnly(segments(dispatcher, "execute if modcheck run seed")));
		assertFalse(CommandRules.readOnly(segments(dispatcher, "execute if modcheck")));
	}

	@Test
	void modArgumentBesideAVanillaModifierIsNotReadOnly() {
		CommandDispatcher<CommandSourceStack> dispatcher = vanilla();
		CommandNode<CommandSourceStack> execute = dispatcher.getRoot().getChild("execute");
		dispatcher.register(Commands.literal("execute").then(Commands.literal("in")
			.then(Commands.literal("moddimension").redirect(execute, c -> c.getSource()))));

		assertFalse(CommandRules.readOnly(segments(dispatcher, "execute in moddimension run seed")));
	}

	@Test
	void patterns() {
		assertTrue(CommandRules.matches("data get", List.of("data", "get", "entity", "<target>")));
		assertTrue(CommandRules.matches("gamerule *$", List.of("gamerule", "keep_inventory")));
		assertFalse(CommandRules.matches("gamerule *$", List.of("gamerule", "keep_inventory", "<value>")));
		assertTrue(CommandRules.matches("execute if|unless entity", List.of("execute", "unless", "entity", "<entities>")));
		assertFalse(CommandRules.matches("execute if|unless entity", List.of("execute", "store", "entity")));
	}

	/** A fresh tree of vanilla commands, since some tests add mod commands to it. */
	private static CommandDispatcher<CommandSourceStack> vanilla() {
		return new Commands(Commands.CommandSelection.INTEGRATED, buildContext).getDispatcher();
	}

	private static List<CommandRules.Segment> segments(CommandDispatcher<CommandSourceStack> dispatcher, String command) {
		ParseResults<CommandSourceStack> parse = dispatcher.parse(command, source);
		assertNull(Commands.getParseException(parse), () -> command + " doesn't parse");

		return CommandRules.segments(parse.getContext());
	}
}
