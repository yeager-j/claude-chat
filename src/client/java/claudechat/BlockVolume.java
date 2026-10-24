package claudechat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A box of blocks read from a structure template or a schematic, the same whatever the file format. Every position in
 * the box is one {@link Kind}: a block, air (placing it clears the world's block), structure void, or absent (the file
 * says nothing about it). Void and absent both keep whatever the world has there.
 * <p>
 * Which blocks a build is made of is decided once, by {@link #counts(BlockState)}.
 */
public final class BlockVolume {
	/** What one position of the box holds. */
	public enum Kind {
		ABSENT, VOID, AIR, BLOCK
	}

	/** An entity stored with the blocks, at the block position it stands in. */
	public record Entity(BlockPos pos, String type) {
	}

	/** A block with block entity data (a chest, spawner, sign, jigsaw connector...). */
	public record BlockEntity(BlockPos pos, BlockState state, CompoundTag data) {
	}

	/** Positions a volume may hold: 128 MB of cells. Bigger boxes are refused rather than risk running out of memory. */
	public static final long MAX_POSITIONS = 1L << 26;

	private static final short ABSENT = -1;

	private final Vec3i size;
	private final List<BlockState> palette;
	private final short[] cells;
	private final Map<BlockPos, CompoundTag> blockEntities;
	private final List<Entity> entities;

	private BlockVolume(Vec3i size, List<BlockState> palette, short[] cells, Map<BlockPos, CompoundTag> blockEntities, List<Entity> entities) {
		this.size = size;
		this.palette = palette;
		this.cells = cells;
		this.blockEntities = blockEntities;
		this.entities = entities;
	}

	/** Whether a block counts as part of a build: not air, not structure void, and not a jigsaw connector (which turns into another block when placed). */
	public static boolean counts(BlockState state) {
		return kindOf(state) == Kind.BLOCK && !state.is(Blocks.JIGSAW);
	}

	private static Kind kindOf(BlockState state) {
		if (state.is(Blocks.STRUCTURE_VOID)) {
			return Kind.VOID;
		}

		return state.isAir() ? Kind.AIR : Kind.BLOCK;
	}

	public Vec3i size() {
		return size;
	}

	/** Entities in file order. */
	public List<Entity> entities() {
		return entities;
	}

	/** What the position holds; positions outside the box are absent. */
	public Kind kind(int x, int y, int z) {
		BlockState state = state(x, y, z);
		return state == null ? Kind.ABSENT : kindOf(state);
	}

	/** The state at a position, or null where the position is absent or outside the box. */
	public BlockState state(int x, int y, int z) {
		if (!inside(size, x, y, z)) {
			return null;
		}

		short index = cells[index(size, x, y, z)];
		return index == ABSENT ? null : palette.get(index);
	}

	/** Calls {@code action} with each counted block's state (see {@link #counts}), one call per position. */
	public void forEachCounted(Consumer<BlockState> action) {
		for (short index : cells) {
			if (index != ABSENT && counts(palette.get(index))) {
				action.accept(palette.get(index));
			}
		}
	}

	/** Counted blocks by block, all states of a block together. */
	public Map<Block, Integer> countedBlocks() {
		Map<Block, Integer> counts = new HashMap<>();
		forEachCounted(state -> counts.merge(state.getBlock(), 1, Integer::sum));
		return counts;
	}

	/** Block entities in file order, each with the state at its position. Data on absent positions is dropped when the volume is built. */
	public List<BlockEntity> blockEntities() {
		List<BlockEntity> list = new ArrayList<>();
		blockEntities.forEach((pos, data) -> list.add(new BlockEntity(pos, state(pos.getX(), pos.getY(), pos.getZ()), data)));
		return list;
	}

	private static boolean inside(Vec3i size, int x, int y, int z) {
		return x >= 0 && y >= 0 && z >= 0 && x < size.getX() && y < size.getY() && z < size.getZ();
	}

	/** Cells run x fastest, then z, then y: the order Litematica and Sponge store blocks in. */
	private static int index(Vec3i size, int x, int y, int z) {
		return (y * size.getZ() + z) * size.getX() + x;
	}

	/**
	 * Builds a volume: every position starts absent, and each format sets what its file records. Not thread-safe.
	 */
	public static final class Builder {
		private final Vec3i size;
		private final short[] cells;
		private final List<BlockState> palette = new ArrayList<>();
		private final Map<BlockState, Short> paletteIndex = new HashMap<>();
		private final Map<BlockPos, CompoundTag> blockEntities = new LinkedHashMap<>();
		private final List<Entity> entities = new ArrayList<>();

		/** @throws IOException if a side is negative or the box has more than {@link #MAX_POSITIONS} positions */
		public Builder(Vec3i size) throws IOException {
			if (size.getX() < 0 || size.getY() < 0 || size.getZ() < 0) {
				throw new IOException("Negative size " + size.toShortString());
			}

			long positions = (long) size.getX() * size.getY() * size.getZ();
			if (positions > MAX_POSITIONS) {
				throw new IOException("Too large to read: " + positions + " positions (at most " + MAX_POSITIONS + ")");
			}

			this.size = size;
			this.cells = new short[(int) positions];
			Arrays.fill(cells, ABSENT);
		}

		/** Sets a position's state; air and structure void are recorded as such. */
		public Builder set(int x, int y, int z, BlockState state) throws IOException {
			if (!inside(size, x, y, z)) {
				throw new IOException("Block at " + x + " " + y + " " + z + " is outside the size " + size.toShortString());
			}

			cells[index(size, x, y, z)] = indexOf(state);
			return this;
		}

		public Builder blockEntity(BlockPos pos, CompoundTag data) {
			blockEntities.put(pos.immutable(), data);
			return this;
		}

		public Builder entity(BlockPos pos, String type) {
			entities.add(new Entity(pos.immutable(), type));
			return this;
		}

		public BlockVolume build() {
			blockEntities.keySet().removeIf(pos -> !inside(size, pos.getX(), pos.getY(), pos.getZ())
				|| cells[index(size, pos.getX(), pos.getY(), pos.getZ())] == ABSENT);
			return new BlockVolume(size, List.copyOf(palette), cells, Collections.unmodifiableMap(blockEntities), List.copyOf(entities));
		}

		private short indexOf(BlockState state) throws IOException {
			Short known = paletteIndex.get(state);
			if (known != null) {
				return known;
			}

			if (palette.size() == Short.MAX_VALUE) {
				throw new IOException("More than " + Short.MAX_VALUE + " different block states");
			}

			short added = (short) palette.size();
			palette.add(state);
			paletteIndex.put(state, added);
			return added;
		}
	}
}
