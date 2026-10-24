package claudechat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;

/**
 * Litematica's .litematic. Regions each have a position and a size that may be negative (the region extends from the
 * position the other way). Every position of a region is stored, air included; positions between regions are absent.
 * Block states are a palette plus indices packed into longs; unlike chunk sections, an index may span two longs.
 */
final class LitematicaFormat implements Schematics.Format {
	/**
	 * Regions a file may have. Litematica saves one per selection box, so real files have a handful; each region is
	 * copied and walked even when it is empty, so their number is a limit of its own.
	 */
	static final int MAX_REGIONS = 1024;

	/** A region's box within the schematic, before the schematic is shifted to start at 0 0 0. */
	private record Bounds(String name, BlockPos min, Vec3i size, CompoundTag tag) {
		BlockPos max() {
			return min.offset(size);
		}
	}

	@Override
	public List<FieldSelector> infoFields() {
		return List.of(new FieldSelector(CompoundTag.TYPE, "Metadata"));
	}

	@Override
	public Schematics.Info info(CompoundTag fields, String fileStem) {
		CompoundTag meta = fields.getCompoundOrEmpty("Metadata");
		CompoundTag size = meta.getCompoundOrEmpty("EnclosingSize");
		Vec3i enclosing = new Vec3i(Math.abs(size.getIntOr("x", 0)), Math.abs(size.getIntOr("y", 0)), Math.abs(size.getIntOr("z", 0)));
		OptionalLong blocks = meta.getInt("TotalBlocks").map(OptionalLong::of).orElse(OptionalLong.empty());
		return new Schematics.Info(meta.getStringOr("Name", fileStem), meta.getStringOr("Author", ""), meta.getStringOr("Description", ""),
			enclosing, blocks);
	}

	@Override
	public Schematics.Schematic read(CompoundTag root, String fileStem) throws IOException {
		int dataVersion = root.getIntOr("MinecraftDataVersion", StructureNbt.UNKNOWN_VERSION);
		List<Bounds> bounds = bounds(root.getCompoundOrEmpty("Regions"));
		requireWithinLimits(bounds);
		BlockPos min = bounds.stream().map(Bounds::min).reduce(BlockPos::min).orElse(BlockPos.ZERO);
		BlockPos max = bounds.stream().map(Bounds::max).reduce(BlockPos::max).orElse(BlockPos.ZERO);
		Vec3i size = max.subtract(min);

		BlockVolume.Builder volume = new BlockVolume.Builder(size);
		List<Schematics.Region> regions = new ArrayList<>();
		for (Bounds region : bounds) {
			regions.add(new Schematics.Region(region.name(), region.min().subtract(min), region.size()));
		}
		readRegions(bounds, min, dataVersion, volume);

		Schematics.Info described = info(root, fileStem);
		Schematics.Info info = new Schematics.Info(described.name(), described.author(), described.description(), size, described.recordedBlocks());
		return new Schematics.Schematic(info, regions, volume.build());
	}

	private static List<Bounds> bounds(CompoundTag regions) throws TooLargeException {
		TooLargeException.requireAtMost(regions.size(), MAX_REGIONS, "regions");
		List<Bounds> bounds = new ArrayList<>();
		for (String name : regions.keySet()) {
			CompoundTag region = regions.getCompoundOrEmpty(name);
			CompoundTag pos = region.getCompoundOrEmpty("Position");
			CompoundTag size = region.getCompoundOrEmpty("Size");
			int[] p = {pos.getIntOr("x", 0), pos.getIntOr("y", 0), pos.getIntOr("z", 0)};
			int[] s = {size.getIntOr("x", 0), size.getIntOr("y", 0), size.getIntOr("z", 0)};
			int[] min = new int[3];
			for (int i = 0; i < 3; i++) {
				min[i] = s[i] < 0 ? p[i] + s[i] + 1 : p[i];
			}

			bounds.add(new Bounds(name, new BlockPos(min[0], min[1], min[2]), new Vec3i(Math.abs(s[0]), Math.abs(s[1]), Math.abs(s[2])), region));
		}

		return bounds;
	}

	/**
	 * The limits on the whole file, checked before any region is read: each region visits every one of its positions,
	 * and regions may overlap, so their positions together must fit in one volume; block entities and entities are
	 * upgraded region by region, so their limits apply to the sum.
	 */
	private static void requireWithinLimits(List<Bounds> bounds) throws IOException {
		long positions = 0;
		long blockEntities = 0;
		long entities = 0;

		for (Bounds region : bounds) {
			positions += BlockVolume.requireFits(region.size());
			blockEntities += region.tag().getListOrEmpty("TileEntities").size();
			entities += region.tag().getListOrEmpty("Entities").size();
		}

		TooLargeException.requireAtMost(positions, BlockVolume.MAX_POSITIONS, "positions in its regions");
		TooLargeException.requireAtMost(blockEntities, StructureNbt.MAX_BLOCK_ENTITIES, "block entities");
		TooLargeException.requireAtMost(entities, StructureNbt.MAX_ENTITIES, "entities");
	}

	/**
	 * Adds every region's blocks, block entities and entities to the volume. The fixers run once for the whole file, not
	 * once per region: the regions' palettes go in as one (each distinct state once), and block entities and entities
	 * go in at their place in the schematic.
	 */
	private static void readRegions(List<Bounds> bounds, BlockPos min, int dataVersion, BlockVolume.Builder into) throws IOException {
		ListTag palette = new ListTag();
		Map<CompoundTag, Integer> paletteIndex = new HashMap<>();
		List<int[]> regionPalettes = new ArrayList<>();
		ListTag blockEntities = new ListTag();
		ListTag entities = new ListTag();

		for (Bounds region : bounds) {
			requireNotInterrupted();
			CompoundTag tag = region.tag();
			BlockPos offset = region.min().subtract(min);
			ListTag rawPalette = tag.getListOrEmpty("BlockStatePalette");
			int[] toShared = new int[rawPalette.size()];
			for (int i = 0; i < toShared.length; i++) {
				CompoundTag state = rawPalette.getCompoundOrEmpty(i);
				Integer shared = paletteIndex.get(state);
				if (shared == null) {
					shared = palette.size();
					TooLargeException.requireAtMost(shared + 1L, BlockVolume.MAX_STATES, "different block states in its regions");
					paletteIndex.put(state, shared);
					palette.add(state.copy());
				}
				toShared[i] = shared;
			}
			regionPalettes.add(toShared);

			// Block entities are stored by position within the region; entities by position, with their full data.
			tag.getListOrEmpty("TileEntities").compoundStream().forEach(be -> blockEntities.add(StructureNbt.blockEntityEntry(
				new BlockPos(be.getIntOr("x", 0), be.getIntOr("y", 0), be.getIntOr("z", 0)).offset(offset), be.copy())));
			tag.getListOrEmpty("Entities").compoundStream().forEach(e -> {
				ListTag pos = e.getListOrEmpty("Pos");
				entities.add(StructureNbt.entityEntry(pos.getDoubleOr(0, 0) + offset.getX(), pos.getDoubleOr(1, 0) + offset.getY(),
					pos.getDoubleOr(2, 0) + offset.getZ(), e.copy()));
			});
		}

		StructureNbt.Contents upgraded = StructureNbt.upgradeParts(palette, blockEntities, entities, dataVersion);
		List<BlockState> states = upgraded.palettes().getFirst();

		for (int r = 0; r < bounds.size(); r++) {
			requireNotInterrupted();
			Bounds region = bounds.get(r);
			int[] toShared = regionPalettes.get(r);
			BlockPos offset = region.min().subtract(min);
			PackedIndices indices = new PackedIndices(region.name(), region.tag().getLongArray("BlockStates").orElse(new long[0]), toShared.length,
				(long) region.size().getX() * region.size().getY() * region.size().getZ());
			int sx = region.size().getX();
			int sz = region.size().getZ();
			for (long i = 0; i < indices.count(); i++) {
				int x = (int) (i % sx);
				int z = (int) ((i / sx) % sz);
				int y = (int) (i / ((long) sx * sz));
				into.set(offset.getX() + x, offset.getY() + y, offset.getZ() + z, states.get(toShared[indices.get(i)]));
			}
		}

		for (StructureNbt.Entry be : upgraded.blocks()) {
			into.blockEntity(be.pos(), be.nbt());
		}

		upgraded.entities().forEach(e -> into.entity(e.pos(), e.type()));
	}

	private static void requireNotInterrupted() {
		if (Thread.currentThread().isInterrupted()) {
			throw new CancellationException("Reading the schematic was cancelled");
		}
	}

	/** Litematica's bit array: entry i occupies bits [i*bits, (i+1)*bits), possibly across two longs. */
	private record PackedIndices(String region, long[] packed, int paletteSize, long count, int bits) {
		/** @throws IOException if the array is too short for {@code count} entries */
		PackedIndices(String region, long[] packed, int paletteSize, long count) throws IOException {
			this(region, packed, paletteSize, count, Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteSize - 1))));
			long needed = (count * bits + 63) / 64;
			if (packed.length < needed) {
				throw new IOException("Region \"" + region + "\" is truncated: " + packed.length + " of " + needed + " longs of block data");
			}
		}

		/** @throws IOException if the index is outside the palette */
		int get(long i) throws IOException {
			long start = i * bits;
			int startLong = (int) (start >> 6);
			int endLong = (int) ((start + bits - 1) >> 6);
			int offset = (int) (start & 63);
			long mask = (1L << bits) - 1;
			long value = startLong == endLong
				? packed[startLong] >>> offset
				: packed[startLong] >>> offset | packed[endLong] << (64 - offset);
			int index = (int) (value & mask);
			if (index >= paletteSize) {
				throw new IOException("Region \"" + region + "\" is corrupt: block " + i + " uses palette entry " + index + " of " + paletteSize);
			}

			return index;
		}
	}
}
