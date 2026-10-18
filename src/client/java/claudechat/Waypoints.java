package claudechat;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

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
 */
public final class Waypoints {
	private static final Pattern TAG = Pattern.compile(
		"^\\s*(?<name>[^|]+?)\\s*\\|\\s*(?<x>-?\\d+)[\\s,]+(?<y>-?\\d+|~)[\\s,]+(?<z>-?\\d+)\\s*(?:\\|\\s*(?<dim>[\\w.:/-]+)\\s*)?$");
	private static final Map<String, Identifier> DIMENSION_ALIASES = Map.of(
		"overworld", Level.OVERWORLD.identifier(),
		"nether", Level.NETHER.identifier(),
		"end", Level.END.identifier());
	// Readable WaypointColor ordinals: gold, green, aqua, red, light purple, yellow, blue.
	private static final int[] COLORS = {6, 10, 11, 12, 13, 14, 9};
	private static final int MAX_NAME = 32; // Xaero rejects longer shared names

	/** A parsed tag. {@code y} is null when unknown (structures, biomes); Xaero then shows the waypoint without a height. */
	public record Waypoint(String name, int x, Integer y, int z, Identifier dimension) {}

	private Waypoints() {
	}

	/** Is a minimap that understands the command installed? Without one the command would go to the server. */
	public static boolean available() {
		FabricLoader loader = FabricLoader.getInstance();
		return loader.isModLoaded("xaerominimap") || loader.isModLoaded("xaerominimapfair");
	}

	/** "Name|x y z|dimension"; the dimension is optional and defaults to the player's current one. */
	public static Optional<Waypoint> parse(String tag) {
		Matcher m = TAG.matcher(tag);
		if (!m.matches()) {
			return Optional.empty();
		}
		String name = m.group("name").strip();
		if (name.length() > MAX_NAME) {
			name = name.substring(0, MAX_NAME).strip();
		}
		Identifier dimension = dimension(m.group("dim"));
		if (dimension == null) {
			return Optional.empty();
		}
		Integer y = m.group("y").equals("~") ? null : Integer.parseInt(m.group("y"));
		return Optional.of(new Waypoint(name, Integer.parseInt(m.group("x")), y, Integer.parseInt(m.group("z")), dimension));
	}

	private static Identifier dimension(String text) {
		if (text == null) {
			// Answers are formatted off the render thread, so read the level once.
			ClientLevel level = Minecraft.getInstance().level;
			return level != null ? level.dimension().identifier() : Level.OVERWORLD.identifier();
		}
		String lower = text.toLowerCase(Locale.ROOT);
		Identifier alias = DIMENSION_ALIASES.get(lower.replaceFirst("^(minecraft:)?(the_)?", ""));
		return alias != null ? alias : Identifier.tryParse(lower);
	}

	/**
	 * The command a shared waypoint's "Add" link runs:
	 * xaero_waypoint_add:name:initials:x:y:z:color:rotate:yaw:Internal-&lt;dimension folder&gt;-waypoints.
	 */
	public static String xaeroCommand(Waypoint waypoint) {
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
	static String dimensionFolder(Identifier dimension) {
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

	/** The chat button, or nothing if no minimap is installed or the tag doesn't parse. */
	public static MutableComponent button(String tag) {
		Optional<Waypoint> parsed = available() ? parse(tag) : Optional.empty();
		if (parsed.isEmpty()) {
			return Component.empty();
		}
		Waypoint waypoint = parsed.get();
		String coords = waypoint.x() + " " + (waypoint.y() == null ? "~" : waypoint.y()) + " " + waypoint.z();
		String where = waypoint.dimension().getPath().replaceFirst("^the_", "").replace('_', ' ');
		Component hover = Component.literal("Add \"" + waypoint.name() + "\" at " + coords + " (" + where + ")\n")
			.append(Component.literal("Opens Xaero's Add Waypoint screen").withStyle(ChatFormatting.GRAY));
		return Component.literal("[+ Waypoint]").withStyle(style -> style
			.withColor(ChatFormatting.GREEN)
			.withClickEvent(new ClickEvent.RunCommand("/" + xaeroCommand(waypoint)))
			.withHoverEvent(new HoverEvent.ShowText(hover)));
	}
}
