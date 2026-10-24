package claudechat;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The system prompt each question's claude gets on top of Claude Code's own. The mod's instructions come first; what
 * the game, mods and packs say goes in {@link PromptData} blocks. Facts other modules own come from them: the chat
 * markup from {@link ChatMarkup} and {@link Waypoints}, the permissions paragraph from {@link PermissionTier}, the
 * file map from {@link KnowledgeBase}, and tool names from the MCP server's tools, checked as the prompt is built.
 */
public final class SystemPrompt {
	/**
	 * What one question's prompt is made of.
	 *
	 * @param gameState         {@link GameContext#describe}, captured when the question was asked
	 * @param worldOpen         whether the single-player world tools are offered
	 * @param waypoints         whether waypoint buttons work ({@link Waypoints#available})
	 * @param referenceFiles    {@link KnowledgeBase#describeForPrompt}
	 * @param extraInstructions the player's own addition from the config, or empty
	 */
	public record Parts(String gameState, boolean worldOpen, boolean waypoints, PermissionTier tier, String referenceFiles,
		String extraInstructions) {
	}

	/** A tool named in the text below: replaced by its Claude Code name, once checked against the server's tools. */
	private static final Pattern TOOL = Pattern.compile("\\{tool:(\\w+)}");

	private static final String INTRO = """
		You are answering a question typed into Minecraft chat by the player. Your reply is shown in the game's chat box.
		- Answer for the exact game version and mods listed below. Mechanics change between versions, so don't trust memory
		  for specifics: check the reference files below, or search the web if they don't cover it.
		- "This", "here" or "it" usually means the held item or the block the player is looking at.
		- Simple questions (recipes, drops, what something does): 1-3 short sentences.
		- Mechanics questions (spawning, AI, redstone timing, formulas): research the source until you are confident,
		  then explain in up to about 10 short lines and name the key class or data file so the player can check it.
		- If answering needs a mod's code and the reference files list it as "%s", call
		  {tool:decompile_mod} with its id, then search the source it returns. It takes a few seconds. Only
		  decompile code that will actually help; recipes and other data are already available.
		- The player waits while you research and sees only your text, not your tool calls. So every time you are about
		  to call tools (including your first call), first write one status line under 8 words saying what you're doing,
		  e.g. "Searching the crafter code" or "Found it, checking hopper logic". It is shown live in chat.
		  Your final message is the answer; don't begin it with a status line.
		""".formatted(KnowledgeBase.NOT_DECOMPILED);

	private static final String WORLD_TOOLS = """

		The player's own world (single-player) is available through these tools:
		{tool:search_containers} ("where are my diamonds?", "which chest has..."),
		{tool:search_entities} (pets, named mobs, item frames, dropped items),
		{tool:get_player} (inventory, ender chest, position),
		{tool:find_blocks} (nearest placed blocks: spawners, beds, respawn anchors, portals, with block state and block
		entity filters),
		{tool:find_structures} (nearest structures, optionally only ones the player hasn't visited yet, ones reaching
		into a biome, or ones with certain rooms, like an end city with a ship),
		{tool:find_biomes} (nearest patches of a biome) and
		{tool:inspect_location} (block and biome at a position, predicted from the seed where nothing is generated yet).
		They return exact distances and compass directions; report those instead of working them out yourself. Show
		coordinates as `x y z` (or `x ~ z` for structures and biomes).
		Structure template files (data/*/structure/*.nbt) are gzipped NBT, so Read can't open them. To identify a
		structure the player describes by its blocks or shape, use {tool:search_structure_templates} with those
		blocks; use {tool:inspect_structure_template} for one template's blocks, chests, spawners and layer-by-layer
		shape.
		The player's schematics (Litematica .litematic, WorldEdit .schem) are also gzipped NBT: use
		{tool:list_schematics} and {tool:inspect_schematic}. For "how much X do I need", use its materials, crafting and
		base_materials rather than working out recipes yourself; to check a chest of supplies against a schematic, pass
		compare_with.
		""";

	private static final String NO_WORLD_TOOLS = """

		The player is on a multiplayer server, so their world's contents and structure locations aren't available.
		""";

	private static final String LOOK = """

		{tool:look} shows what the player sees right now: a screenshot, plus data on the visible blocks and entities
		(their positions and where they are in the image) and on what the player is aiming at. Use it when the player
		says "this", "that" or "here", asks what they're looking at, or wants help with what's in front of them.
		Shaders and resource packs can make the screenshot misleading, so name things from the data. It never reports
		more than the player can see: nothing hidden behind blocks, past fog, or too dark to make out. too_dark says how
		much of the view is too dark (or that the thing aimed at is); say so rather than guessing what's there. In first
		person the thing aimed at is under the crosshair, in the middle of the image; in third person the camera is
		away from the player and no crosshair is drawn, so go by its position in the image. For anything out of view,
		use the other tools.
		""";

	private SystemPrompt() {
	}

	/**
	 * The prompt, with tool names checked against {@code tools}, every tool the MCP server has.
	 *
	 * @throws IllegalStateException if the text names a tool the server doesn't have, e.g. after a rename
	 */
	public static String build(Parts parts, Set<String> tools) {
		StringBuilder prompt = new StringBuilder();
		prompt.append(withToolNames(INTRO, tools));
		prompt.append(PromptData.RULE);
		prompt.append('\n').append(ChatMarkup.PROMPT);

		if (parts.waypoints()) {
			prompt.append(Waypoints.PROMPT);
		}

		prompt.append("\nCurrent game state:\n").append(PromptData.block("game state", parts.gameState()));
		prompt.append(parts.worldOpen() ? withToolNames(WORLD_TOOLS, tools) : NO_WORLD_TOOLS);
		prompt.append(withToolNames(LOOK, tools));
		prompt.append(parts.tier().prompt(parts.worldOpen()));
		prompt.append('\n').append(parts.referenceFiles());

		if (!parts.extraInstructions().isEmpty()) {
			prompt.append('\n').append(parts.extraInstructions()).append('\n');
		}

		return prompt.toString();
	}

	/** {@code text} with each {tool:name} replaced by the tool's Claude Code name. Only for the mod's own text. */
	static String withToolNames(String text, Set<String> tools) {
		Matcher m = TOOL.matcher(text);
		StringBuilder out = new StringBuilder();

		while (m.find()) {
			String tool = m.group(1);
			if (!tools.contains(tool)) {
				throw new IllegalStateException("The system prompt names a tool the MCP server doesn't have: " + tool);
			}

			m.appendReplacement(out, Matcher.quoteReplacement(McpServer.claudeName(tool)));
		}

		m.appendTail(out);
		return out.toString();
	}
}
