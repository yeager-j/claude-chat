package claudechat;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The vocabulary every tool's output uses, so Claude reads one format from all of them: ids, block states, positions,
 * sizes, distances and directions, item stacks, what a search covered, and the note on results left out.
 */
public final class ToolFormat {
	/** The key of the note saying what a result left out. */
	public static final String MORE_KEY = "more_not_shown";

	private static final String[] COMPASS = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};

	private ToolFormat() {
	}

	// --- ids ------------------------------------------------------------------------------------------------------

	/**
	 * "stone" for Minecraft's own ids, the full id ("create:cogwheel") for everything else. Every tool argument and chat
	 * tag reads a bare name as Minecraft's, so this is what Claude can pass back.
	 */
	public static String id(Identifier id) {
		return id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE) ? id.getPath() : id.toString();
	}

	/**
	 * {@link #id(Identifier)} for an id held as text, or a tag ("#minecraft:planks" → "#planks"); text that isn't an id is
	 * returned as it is.
	 */
	public static String id(String id) {
		if (id.startsWith("#")) {
			return "#" + id(id.substring(1));
		}

		Identifier parsed = Identifier.tryParse(id);
		return parsed == null ? id : id(parsed);
	}

	public static String id(Block block) {
		return id(BuiltInRegistries.BLOCK.getKey(block));
	}

	/** A registry entry's id; "?" for an entry that isn't registered. */
	public static String id(Holder<?> holder) {
		return holder.unwrapKey().map(key -> id(key.identifier())).orElse("?");
	}

	// --- blocks ---------------------------------------------------------------------------------------------------

	/** Every state property by name, e.g. {"charges": "4", "facing": "north"}. */
	public static Map<String, String> properties(BlockState state) {
		Map<String, String> properties = new LinkedHashMap<>();
		for (Property<?> property : state.getProperties()) {
			properties.put(property.getName(), valueName(state, property));
		}

		return properties;
	}

	/** Adds "block" and, if it has any, "properties": {"block": "respawn_anchor", "properties": {"charges": "4"}}. */
	public static void addBlockState(JsonObject out, BlockState state) {
		out.addProperty("block", id(state.getBlock()));
		Map<String, String> properties = properties(state);
		if (!properties.isEmpty()) {
			JsonObject json = new JsonObject();
			properties.forEach(json::addProperty);
			out.add("properties", json);
		}
	}

	/** A block state written as text, "minecraft:oak_stairs[facing=east]", with its id shortened: "oak_stairs[facing=east]". */
	public static String blockState(String state) {
		int properties = state.indexOf('[');
		return properties < 0 ? id(state) : id(state.substring(0, properties)) + state.substring(properties);
	}

	private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
		return property.getName(state.getValue(property));
	}

	// --- places ---------------------------------------------------------------------------------------------------

	/** "12 64 -30". */
	public static String position(BlockPos pos) {
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	/** "12.5 64.0 -30.2", for positions that aren't on the block grid (entities, the camera). */
	public static String position(double x, double y, double z) {
		return String.format(Locale.ROOT, "%.1f %.1f %.1f", x, y, z);
	}

	/** "9 wide (x) × 7 tall (y) × 11 deep (z)". */
	public static String size(Vec3i size) {
		return size.getX() + " wide (x) × " + size.getY() + " tall (y) × " + size.getZ() + " deep (z)";
	}

	/** A distance in blocks: to a tenth under 10 blocks, where a fraction tells near things apart; whole beyond. */
	public static Number distance(double blocks) {
		// Not a conditional expression: it would widen the whole number to a double, shown as "2830.0".
		if (blocks < 10) {
			return Math.round(blocks * 10) / 10.0;
		}

		return Math.round(blocks);
	}

	/**
	 * Adds "distance_blocks" and "direction" from {@code origin} (in the same dimension) to a point, plus "blocks_up" or
	 * "blocks_down" when {@code y} is known and differs by 3 or more. Without {@code y}, the distance is horizontal.
	 */
	public static void addDirection(JsonObject out, Origin origin, double x, Double y, double z) {
		double distance = y == null ? origin.horizontalDistanceTo(x, z) : origin.distanceTo(x, y, z);
		out.addProperty("distance_blocks", distance(distance));
		out.addProperty("direction", compass(x - origin.x(), z - origin.z()));
		if (y != null) {
			long dy = Math.round(y - origin.y());
			if (Math.abs(dy) >= 3) {
				out.addProperty(dy > 0 ? "blocks_up" : "blocks_down", Math.abs(dy));
			}
		}
	}

	/** The compass direction of a horizontal offset, "here" when it's under 2 blocks. North is -Z, east is +X. */
	public static String compass(double dx, double dz) {
		if (Math.abs(dx) < 2 && Math.abs(dz) < 2) {
			return "here";
		}

		double angle = Math.toDegrees(Math.atan2(dx, -dz));
		int index = (int) Math.round(((angle % 360) + 360) % 360 / 45) % 8;
		return COMPASS[index];
	}

	// --- items and searches ---------------------------------------------------------------------------------------

	/** "32 Emerald (emerald)", "1 Diamond Sword (diamond_sword) {Sharpness V, Mending}". */
	public static String item(int count, String id, String name, List<String> enchantments) {
		String text = count + " " + name + " (" + id(id) + ")";
		return enchantments.isEmpty() ? text : text + " {" + String.join(", ", enchantments) + "}";
	}

	/** What a search covered: the one id the query resolved to, or how many: "12 biomes matching \"warped\"". */
	public static String searched(Collection<String> ids, String query, String plural) {
		return ids.size() == 1 ? id(ids.iterator().next()) : ids.size() + " " + plural + " matching \"" + query + "\"";
	}

	/**
	 * Notes under {@link #MORE_KEY} that results were left out, e.g. "12 more not shown; raise limit". A second note
	 * on the same object is added to the first.
	 */
	public static void noteMore(JsonObject out, String note) {
		String earlier = out.has(MORE_KEY) ? out.get(MORE_KEY).getAsString() + " " : "";
		out.addProperty(MORE_KEY, earlier + note);
	}
}
