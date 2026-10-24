package claudechat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The structure .nbt layout: vanilla structure templates and structure block saves. It is also the form every
 * schematic format is upgraded in, so {@link #upgrade} is the one place an old file's block states, block entities
 * and entities are brought up to the running version.
 */
final class StructureNbt {
	/** The data version assumed when a file records none: the oldest the fixers know, and what the game assumes for structure files. */
	static final int UNKNOWN_VERSION = 500;

	/**
	 * Palettes (block variants) a structure file may have. Vanilla's most is 8 (shipwrecks), and summarizing a template
	 * builds one volume per palette.
	 */
	static final int MAX_PALETTES = 64;

	/**
	 * Block entities a file may carry; each one goes through the data fixers, which are slow for old files. The most
	 * among 7,218 structure templates in a heavily modded game is 3,107; a schematic of a large storage build may have
	 * tens of thousands.
	 */
	static final int MAX_BLOCK_ENTITIES = 1 << 18;

	/** Entities a file may carry, for the same reason. The most among those templates is 226. */
	static final int MAX_ENTITIES = 1 << 16;

	/** A block entry: its position, palette index and block entity data (null if it has none). */
	record Entry(BlockPos pos, int state, CompoundTag nbt) {
	}

	/** A structure tag's contents: its size, block palettes (one per variant), block entries and entities. */
	record Contents(Vec3i size, List<List<BlockState>> palettes, List<Entry> blocks, List<BlockVolume.Entity> entities) {
		/**
		 * One palette variant as a volume. Positions without an entry are absent.
		 *
		 * @throws IOException if an entry's state isn't in the palette or it lies outside the size
		 */
		BlockVolume volume(int palette) throws IOException {
			List<BlockState> states = palettes.isEmpty() ? List.of() : palettes.get(palette);
			BlockVolume.Builder builder = new BlockVolume.Builder(size);
			for (Entry entry : blocks) {
				if (entry.state() < 0 || entry.state() >= states.size()) {
					throw new IOException("Block at " + entry.pos().toShortString() + " has state " + entry.state()
						+ ", but the palette has " + states.size());
				}

				builder.set(entry.pos().getX(), entry.pos().getY(), entry.pos().getZ(), states.get(entry.state()));
				if (entry.nbt() != null) {
					builder.blockEntity(entry.pos(), entry.nbt());
				}
			}

			entities.forEach(entity -> builder.entity(entity.pos(), entity.type()));
			return builder.build();
		}
	}

	private StructureNbt() {
	}

	/**
	 * Brings a structure-format tag from {@code dataVersion} up to the running version. Doesn't modify {@code structure}.
	 *
	 * @throws TooLargeException if the tag is over the limits on palettes, palette entries, block entities or entities,
	 *     checked first so the fixers never see it
	 */
	static CompoundTag upgrade(CompoundTag structure, int dataVersion) throws TooLargeException {
		requireWithinLimits(structure);
		return DataFixTypes.STRUCTURE.updateToCurrentVersion(DataFixers.getDataFixer(), structure.copy(), dataVersion);
	}

	private static void requireWithinLimits(CompoundTag structure) throws TooLargeException {
		List<ListTag> palettes = structure.getList("palettes")
			.map(list -> list.stream().map(palette -> palette instanceof ListTag states ? states : new ListTag()).toList())
			.orElseGet(() -> List.of(structure.getListOrEmpty("palette")));
		TooLargeException.requireAtMost(palettes.size(), MAX_PALETTES, "palettes");

		for (ListTag palette : palettes) {
			TooLargeException.requireAtMost(palette.size(), BlockVolume.MAX_STATES, "block states in a palette");
		}

		long blockEntities = structure.getListOrEmpty("blocks").compoundStream().filter(block -> block.contains("nbt")).count();
		TooLargeException.requireAtMost(blockEntities, MAX_BLOCK_ENTITIES, "block entities");
		TooLargeException.requireAtMost(structure.getListOrEmpty("entities").size(), MAX_ENTITIES, "entities");
	}

	/** The data version a structure file records, or {@link #UNKNOWN_VERSION}. */
	static int dataVersion(CompoundTag structure) {
		return NbtUtils.getDataVersion(structure, UNKNOWN_VERSION);
	}

	/** Reads an up-to-date structure tag (see {@link #upgrade}). Block entity data is copied, so the tag can be shared. */
	static Contents read(CompoundTag current) {
		ListTag size = current.getListOrEmpty("size");
		List<List<BlockState>> palettes = new ArrayList<>();
		Optional<ListTag> paletteList = current.getList("palettes");
		if (paletteList.isPresent()) {
			for (int i = 0; i < paletteList.get().size(); i++) {
				palettes.add(states(paletteList.get().getListOrEmpty(i)));
			}
		} else {
			palettes.add(states(current.getListOrEmpty("palette")));
		}

		List<Entry> blocks = current.getListOrEmpty("blocks").compoundStream()
			.map(b -> new Entry(blockPos(b.getListOrEmpty("pos")), b.getIntOr("state", 0), b.getCompound("nbt").map(CompoundTag::copy).orElse(null)))
			.toList();
		List<BlockVolume.Entity> entities = current.getListOrEmpty("entities").compoundStream()
			.map(e -> new BlockVolume.Entity(blockPos(e.getListOrEmpty("blockPos")), e.getCompoundOrEmpty("nbt").getStringOr("id", "?")))
			.toList();
		return new Contents(new Vec3i(size.getIntOr(0, 0), size.getIntOr(1, 0), size.getIntOr(2, 0)), palettes, blocks, entities);
	}

	private static List<BlockState> states(ListTag palette) {
		List<BlockState> states = new ArrayList<>();
		for (int i = 0; i < palette.size(); i++) {
			states.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK, palette.getCompoundOrEmpty(i)));
		}

		return states;
	}

	private static BlockPos blockPos(ListTag xyz) {
		return new BlockPos(xyz.getIntOr(0, 0), xyz.getIntOr(1, 0), xyz.getIntOr(2, 0));
	}

	// --- formats that are upgraded through this one ---------------------------------------------------------------

	/**
	 * Upgrades the parts of another format that the fixers know: its block palette (state tags in the file version's
	 * form), block entities ({@link #blockEntityEntry}) and entities ({@link #entityEntry}). The result has one palette;
	 * its blocks are the block entity entries.
	 */
	static Contents upgradeParts(ListTag palette, ListTag blockEntities, ListTag entities, int dataVersion) throws TooLargeException {
		CompoundTag structure = new CompoundTag();
		structure.put("palette", palette);
		structure.put("blocks", blockEntities);
		structure.put("entities", entities);
		return read(upgrade(structure, dataVersion));
	}

	/**
	 * A block entry carrying block entity data, for {@link #upgrade}; {@code nbt} needs its "id". Its palette index is
	 * 0: the fixers don't read it, and the caller places blocks from its own data.
	 */
	static CompoundTag blockEntityEntry(BlockPos pos, CompoundTag nbt) {
		CompoundTag entry = new CompoundTag();
		entry.put("pos", intList(pos.getX(), pos.getY(), pos.getZ()));
		entry.putInt("state", 0);
		entry.put("nbt", nbt);
		return entry;
	}

	/** An entity entry, for {@link #upgrade}; {@code nbt} needs its "id". */
	static CompoundTag entityEntry(double x, double y, double z, CompoundTag nbt) {
		BlockPos block = BlockPos.containing(x, y, z);
		CompoundTag entry = new CompoundTag();
		ListTag pos = new ListTag();
		pos.add(DoubleTag.valueOf(x));
		pos.add(DoubleTag.valueOf(y));
		pos.add(DoubleTag.valueOf(z));
		entry.put("pos", pos);
		entry.put("blockPos", intList(block.getX(), block.getY(), block.getZ()));
		entry.put("nbt", nbt);
		return entry;
	}

	private static ListTag intList(int... values) {
		ListTag list = new ListTag();
		for (int value : values) {
			list.add(IntTag.valueOf(value));
		}

		return list;
	}
}
