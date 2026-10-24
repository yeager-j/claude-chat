package claudechat;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Where a world search measures distances and directions from: a dimension and a position in it. A search in another
 * dimension than the player's measures from the player's position translated there (x/8 and z/8 into the Nether),
 * and {@code translated} says so.
 */
public record Origin(ResourceKey<Level> dimension, double x, double y, double z, boolean translated) {
	/** A position as it is, not translated from another dimension. */
	public static Origin at(ResourceKey<Level> dimension, double x, double y, double z) {
		return new Origin(dimension, x, y, z, false);
	}

	/**
	 * This position in {@code other}: x and z scaled by {@code scale} (the game's teleportation scale between the two
	 * dimensions), y kept. Itself in its own dimension.
	 */
	public Origin translatedTo(ResourceKey<Level> other, double scale) {
		if (other.equals(dimension)) {
			return this;
		}
		return new Origin(other, x * scale, y, z * scale, true);
	}

	public double distanceTo(double toX, double toY, double toZ) {
		return Math.sqrt(square(toX - x) + square(toY - y) + square(toZ - z));
	}

	public double horizontalDistanceTo(double toX, double toZ) {
		return Math.sqrt(square(toX - x) + square(toZ - z));
	}

	public BlockPos blockPos() {
		return BlockPos.containing(x, y, z);
	}

	private static double square(double value) {
		return value * value;
	}
}
