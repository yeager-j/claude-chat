package claudechat.test;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.util.List;
import java.util.Optional;

/**
 * The game test's steps, each declared once: the {@code CLAUDECHAT_*} switch that turns it on, the question variable
 * that also asks the real Claude, the other variables it reads, and the world it runs in. {@link TestOptions} parses
 * the environment against these declarations, and AGENTS.md's table describes them.
 * <p>
 * Each step runs in a fresh world of its own and leaves no files behind, so steps pass in any combination. They run
 * in declaration order. A question variable needs its step's switch, and asks the real Claude through
 * {@link FakeClaude}'s pass-through, which spends the signed-in account; every other step only talks to the fake.
 */
enum Step {
	/** Always: MCP tools in a world, /claude preview, recipe grids, tab completion, /claude knowledge, the status line. */
	BASICS(null, null, World.FLAT, BasicsStep::run),
	/** A question naming a data-only mod decompiles it first, then the fake answers. */
	MOD("CLAUDECHAT_MOD_TEST", "CLAUDECHAT_MOD_QUESTION", World.FLAT, ModStep::run),
	/** A folder and a zip data pack: recipe grids, material lists and the prompt's file map follow the server's packs. */
	PACK("CLAUDECHAT_PACK_TEST", null, World.FLAT, PackStep::run, Names.LIVE),
	/** Structure templates: block search, tracing to structures, saved templates, size limits. */
	TEMPLATE("CLAUDECHAT_TEMPLATE_TEST", "CLAUDECHAT_TEMPLATE_QUESTION", World.NORMAL, TemplateStep::run),
	/** Schematics copied from a folder (the switch's value): material lists, upgrades, limits, a chest comparison. */
	SCHEMATIC("CLAUDECHAT_SCHEMATIC_TEST", "CLAUDECHAT_SCHEMATIC_QUESTION", World.FLAT, SchematicStep::run),
	/** Xaero waypoint buttons in an answer; needs Xaero's Minimap through -PextraMods. */
	WAYPOINT("CLAUDECHAT_WAYPOINT_TEST", "CLAUDECHAT_WAYPOINT_QUESTION", World.NORMAL, WaypointStep::run),
	/** run_command and approve in each tier set with /claude permissions and /claude cheats; late approvals. */
	PERMISSION("CLAUDECHAT_PERMISSION_TEST", null, World.FLAT_NO_CHEATS, PermissionStep::run, Names.LIVE),
	/** look: what it must see and must not (occlusion, darkness, Blindness, water, third person). */
	LOOK("CLAUDECHAT_LOOK_TEST", "CLAUDECHAT_LOOK_QUESTION", World.FLAT, LookStep::run),
	/** Every world tool against facts the step placed, structure pieces against generation, visits. */
	WORLD("CLAUDECHAT_WORLD_TEST", null, World.NORMAL, WorldStep::run, Names.LIVE),
	/** Question lifecycles with the fake: errors, lost sessions, the tier a question keeps, timeout, disconnect. */
	RUN("CLAUDECHAT_RUN_TEST", null, World.FLAT, RunStep::run),
	/** Real questions: a recipe and a research question with progress notes. */
	LIVE(Names.LIVE, null, World.FLAT, LiveStep::run);

	/** Names declared by more than one step; a nested class so the constants above may use them. */
	static final class Names {
		static final String LIVE = "CLAUDECHAT_LIVE_TEST";
		/** The switch of the schematic step, whose value is a folder rather than 1. */
		static final String SCHEMATIC = "CLAUDECHAT_SCHEMATIC_TEST";

		private Names() {
		}
	}

	/** What a step does, in the world {@link Game} has open for it. */
	@FunctionalInterface
	interface Body {
		void run(Game game) throws Exception;
	}

	/**
	 * The world a step runs in. The default test world is superflat with structures off; a normal world has terrain,
	 * structures and a fixed seed, so searches find the same places every run.
	 *
	 * @param cheats whether the world allows commands, which decides what the player's own permissions can run
	 */
	record World(boolean normal, boolean cheats) {
		static final World FLAT = new World(false, true);
		static final World FLAT_NO_CHEATS = new World(false, false);
		static final World NORMAL = new World(true, true);

		TestSingleplayerContext create(ClientGameTestContext context) {
			TestSingleplayerContext world = context.worldBuilder().adjustSettings(settings -> {
				settings.setAllowCommands(cheats);
				if (normal) {
					settings.setWorldType(settings.getNormalPresetList().getFirst());
					settings.setGenerateStructures(true);
					settings.setSeed("claudechat");
				}
			}).create();
			world.getConnection().waitForChunksRender();
			return world;
		}
	}

	/** Null for a step that always runs. */
	final String switchName;
	/** Null for a step without a real question of its own. */
	final String questionName;
	final World world;
	final Body body;
	/** Other variables the step reads, such as the live switch. */
	final List<String> alsoReads;

	Step(String switchName, String questionName, World world, Body body, String... alsoReads) {
		this.switchName = switchName;
		this.questionName = questionName;
		this.world = world;
		this.body = body;
		this.alsoReads = List.of(alsoReads);
	}

	Optional<String> questionName() {
		return Optional.ofNullable(questionName);
	}
}
