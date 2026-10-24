package claudechat;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [[waypoint:End City|1240 ~ -3380|the_end]] in an answer becomes an "[+ Waypoint]" button for Xaero's Minimap.
 * The click runs Xaero's own chat-sharing command (xaero_waypoint_add:...). Xaero intercepts it before it reaches the
 * server and opens its Add Waypoint screen, filled in, so the player confirms every waypoint. Nothing here links
 * against Xaero: the command format is the one players' shared waypoints use, which Xaero has kept for years.
 * The escaping, colours, name limit and folder names below copy Xaero internals, so the button is offered only for
 * the Xaero versions they were checked against.
 */
public final class Waypoints {
	private static final List<String> MINIMAP_IDS = List.of("xaerominimap", "xaerominimapfair");
	// Checked against 26.6.0's decompiled sharing code. Raise after checking a newer major version.
	private static final String KNOWN_GOOD_VERSIONS = ">=26.6.0 <27";
	private static final boolean AVAILABLE = supportedMinimapLoaded();

	private static final Pattern TAG = Pattern.compile(
		"^\\s*(?<name>[^|]+?)\\s*\\|\\s*(?<x>-?\\d+)[\\s,]+(?<y>-?\\d+|~)[\\s,]+(?<z>-?\\d+)\\s*(?:\\|\\s*(?<dim>[\\w.:/-]+)\\s*)?$");
	private static final Map<String, Identifier> DIMENSION_ALIASES = Map.of(
		"overworld", Level.OVERWORLD.identifier(),
		"nether", Level.NETHER.identifier(),
		"end", Level.END.identifier());
	// Readable WaypointColor ordinals: gold, green, aqua, red, light purple, yellow, blue.
	private static final int[] COLORS = {6, 10, 11, 12, 13, 14, 9};
	private static final int MAX_NAME = 32; // Xaero rejects longer shared names
	private static final String BUTTON = "[+ Waypoint]";

	/** The system prompt's waypoint rules, for when {@link #available()}: the tag's grammar, as {@code TAG} reads it. */
	public static final String PROMPT = """
		- Xaero's Minimap is installed. When the player asks for a waypoint, or you give them a place to travel to, put
		  [[waypoint:<name>|<x> <y> <z>|<dimension>]] right after the coordinates, e.g.
		  `1240 ~ -3380` [[waypoint:End City|1240 ~ -3380|the_end]]. Use ~ for an unknown y, a short name (%d characters
		  at most) and the dimension the place is in. It shows a button that opens Xaero's Add Waypoint screen filled in.
		  You can't add waypoints yourself, so never say one was added; tell the player to click %s.
		""".formatted(MAX_NAME, BUTTON);

	/** A parsed tag. {@code y} is null when unknown (structures, biomes); Xaero then shows the waypoint without a height. */
	private record Waypoint(String name, int x, Integer y, int z, Identifier dimension) {}

	private Waypoints() {
	}

	/**
	 * Is a minimap that understands the command installed, in a version known to read it as we write it? Without
	 * one the command would go to the server.
	 */
	public static boolean available() {
		return AVAILABLE;
	}

	private static boolean supportedMinimapLoaded() {
		VersionPredicate knownGood;
		try {
			knownGood = VersionPredicate.parse(KNOWN_GOOD_VERSIONS);
		} catch (VersionParsingException e) {
			throw new IllegalStateException(e);
		}

		for (String id : MINIMAP_IDS) {
			Optional<Version> version = FabricLoader.getInstance().getModContainer(id).map(mod -> mod.getMetadata().getVersion());
			if (version.isEmpty()) {
				continue;
			}

			if (knownGood.test(version.get())) {
				return true;
			}

			ClaudeChatClient.LOGGER.info("Waypoint buttons are off: {} {} isn't a version they were checked against ({})",
				id, version.get().getFriendlyString(), KNOWN_GOOD_VERSIONS);
		}

		return false;
	}

	/** "Name|x y z|dimension"; without a dimension, {@code defaultDimension}. */
	private static Optional<Waypoint> parse(String tag, Identifier defaultDimension) {
		Matcher m = TAG.matcher(tag);
		if (!m.matches()) {
			return Optional.empty();
		}
		String name = m.group("name").strip();
		if (name.length() > MAX_NAME) {
			name = name.substring(0, MAX_NAME).strip();
		}
		Identifier dimension = m.group("dim") == null ? defaultDimension : dimension(m.group("dim"));
		if (dimension == null) {
			return Optional.empty();
		}
		Integer y = m.group("y").equals("~") ? null : Integer.parseInt(m.group("y"));
		return Optional.of(new Waypoint(name, Integer.parseInt(m.group("x")), y, Integer.parseInt(m.group("z")), dimension));
	}

	/** A dimension as Claude writes it ("the_end", "nether", "minecraft:overworld", a modded id); null if invalid. */
	private static Identifier dimension(String text) {
		String lower = text.toLowerCase(Locale.ROOT);
		Identifier alias = DIMENSION_ALIASES.get(lower.replaceFirst("^(minecraft:)?(the_)?", ""));
		return alias != null ? alias : Identifier.tryParse(lower);
	}

	/**
	 * The command a shared waypoint's "Add" link runs:
	 * xaero_waypoint_add:name:initials:x:y:z:color:rotate:yaw:Internal-&lt;dimension folder&gt;-waypoints.
	 */
	private static String xaeroCommand(Waypoint waypoint) {
		String name = waypoint.name().replace(":", "^col^");
		int color = COLORS[Math.floorMod(waypoint.name().hashCode(), COLORS.length)];
		return "xaero_waypoint_add:" + name + ":" + initials(waypoint.name())
			+ ":" + waypoint.x() + ":" + (waypoint.y() == null ? "~" : waypoint.y()) + ":" + waypoint.z()
			+ ":" + color + ":false:0"
			// Xaero cuts the folder name off at the last '-', and its escaping turns '_' into '-'. Without the
			// suffix, a modded dimension like "dim%mod$deep_dark" would be cut short and land in the current one.
			+ ":Internal-" + escape(dimensionFolder(waypoint.dimension())) + "-waypoints";
	}

	/** Xaero's folder for a dimension's waypoints (MinimapDimensionHelper.getDimensionDirectoryName). */
	private static String dimensionFolder(Identifier dimension) {
		if (dimension.equals(Level.OVERWORLD.identifier())) {
			return "dim%0";
		}
		if (dimension.equals(Level.NETHER.identifier())) {
			return "dim%-1";
		}
		if (dimension.equals(Level.END.identifier())) {
			return "dim%1";
		}
		return "dim%" + dimension.getNamespace() + "$" + dimension.getPath().replace('/', '%');
	}

	/** WaypointSharingHandler.removeFormatting, which Xaero undoes when reading the destination. */
	private static String escape(String s) {
		return s.replace("-", "^min^").replace("_", "-").replace("*", "^ast^");
	}

	private static String initials(String name) {
		int first = name.codePoints().filter(Character::isLetterOrDigit).findFirst().orElse('W');
		return Character.toString(Character.toUpperCase(first));
	}

	/**
	 * The chat button for the text inside [[waypoint:…]]. Nothing without a supported minimap; the text as written,
	 * dimmed, if it can't be read. A tag without a dimension gets {@code defaultDimension}: the one the player was in
	 * when they asked, since they may have moved by the time the answer arrives.
	 */
	public static MutableComponent button(String tag, Identifier defaultDimension) {
		if (!available()) {
			return Component.empty();
		}

		Optional<Waypoint> parsed = parse(tag, defaultDimension);
		if (parsed.isEmpty()) {
			ClaudeChatClient.LOGGER.warn("Couldn't read waypoint [[waypoint:{}]]", tag);
			return Component.literal(tag).withStyle(style -> style
				.withColor(ChatFormatting.GRAY)
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Couldn't read this waypoint"))));
		}

		Waypoint waypoint = parsed.get();
		String coords = waypoint.x() + " " + (waypoint.y() == null ? "~" : waypoint.y()) + " " + waypoint.z();
		String where = waypoint.dimension().getPath().replaceFirst("^the_", "").replace('_', ' ');
		Component hover = Component.literal("Add \"" + waypoint.name() + "\" at " + coords + " (" + where + ")\n")
			.append(Component.literal("Opens Xaero's Add Waypoint screen").withStyle(ChatFormatting.GRAY));
		return Component.literal(BUTTON).withStyle(style -> style
			.withColor(ChatFormatting.GREEN)
			.withClickEvent(new ClickEvent.RunCommand("/" + xaeroCommand(waypoint)))
			.withHoverEvent(new HoverEvent.ShowText(hover)));
	}
}
