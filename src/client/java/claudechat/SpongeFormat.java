package claudechat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * Sponge .schem (WorldEdit and others). Version 1 and 2 keep everything at the top level; version 3 nests it under
 * "Schematic" with blocks in "Blocks". The palette maps state strings ("minecraft:oak_stairs[facing=east]") to indices,
 * stored as varints in x, z, y order for every position, so nothing is absent; structure void keeps the world's block.
 */
final class SpongeFormat implements Schematics.Format {
	/** Block state tags have used "id"/"properties" instead of "Name"/"Properties" since this data version (BlockStateFieldNamesFix). */
	private static final int STATE_FIELD_NAMES_CHANGED = 5006;

	@Override
	public List<FieldSelector> infoFields() {
		List<FieldSelector> fields = new ArrayList<>();
		for (String field : List.of("Width", "Height", "Length")) {
			fields.add(new FieldSelector(ShortTag.TYPE, field));
			fields.add(new FieldSelector("Schematic", ShortTag.TYPE, field));
		}

		fields.add(new FieldSelector(CompoundTag.TYPE, "Metadata"));
		fields.add(new FieldSelector("Schematic", CompoundTag.TYPE, "Metadata"));
		return fields;
	}

	@Override
	public Schematics.Info info(CompoundTag fields, String fileStem) {
		CompoundTag schematic = fields.getCompound("Schematic").orElse(fields);
		CompoundTag meta = schematic.getCompoundOrEmpty("Metadata");
		return new Schematics.Info(meta.getStringOr("Name", fileStem), meta.getStringOr("Author", ""), "", size(schematic), OptionalLong.empty());
	}

	/** Width, height and length are unsigned shorts. */
	private static Vec3i size(CompoundTag schematic) {
		return new Vec3i(schematic.getShortOr("Width", (short) 0) & 0xFFFF, schematic.getShortOr("Height", (short) 0) & 0xFFFF,
			schematic.getShortOr("Length", (short) 0) & 0xFFFF);
	}

	@Override
	public Schematics.Schematic read(CompoundTag root, String fileStem) throws IOException {
		CompoundTag schematic = root.getCompound("Schematic").orElse(root);
		int dataVersion = schematic.getIntOr("DataVersion", StructureNbt.UNKNOWN_VERSION);
		Vec3i size = size(schematic);
		// Before the palette and the fixers, so an oversized file costs nothing.
		BlockVolume.Builder volume = new BlockVolume.Builder(size);
		CompoundTag blocks = schematic.getCompound("Blocks").orElse(schematic);
		byte[] data = blocks.getByteArray(blocks.contains("Data") ? "Data" : "BlockData").orElse(new byte[0]);
		ListTag blockEntityList = blocks.getList("BlockEntities").orElseGet(() -> schematic.getListOrEmpty("TileEntities"));

		CompoundTag paletteTag = blocks.getCompoundOrEmpty("Palette");
		TooLargeException.requireAtMost(paletteTag.size(), BlockVolume.MAX_STATES, "block states in a palette");
		ListTag palette = new ListTag();
		boolean[] assigned = new boolean[paletteTag.size()];
		for (int i = 0; i < assigned.length; i++) {
			palette.add(stateTag("minecraft:air", dataVersion));
		}

		for (String key : paletteTag.keySet()) {
			int index = paletteTag.getIntOr(key, -1);
			if (index < 0 || index >= assigned.length) {
				throw new IOException("Palette entry " + key + " has index " + index + " of " + assigned.length);
			}

			palette.set(index, stateTag(key, dataVersion));
			assigned[index] = true;
		}

		ListTag blockEntities = new ListTag();
		blockEntityList.compoundStream().forEach(be -> be.getIntArray("Pos").filter(p -> p.length == 3)
			.ifPresent(p -> blockEntities.add(StructureNbt.blockEntityEntry(new BlockPos(p[0], p[1], p[2]), withId(be)))));
		ListTag entities = new ListTag();
		schematic.getListOrEmpty("Entities").compoundStream().forEach(e -> {
			ListTag pos = e.getListOrEmpty("Pos");
			entities.add(StructureNbt.entityEntry(pos.getDoubleOr(0, 0), pos.getDoubleOr(1, 0), pos.getDoubleOr(2, 0), withId(e)));
		});
		StructureNbt.Contents upgraded = StructureNbt.upgradeParts(palette, blockEntities, entities, dataVersion);

		readBlocks(data, size, upgraded.palettes().getFirst(), assigned, volume);
		for (StructureNbt.Entry be : upgraded.blocks()) {
			volume.blockEntity(be.pos(), be.nbt());
		}

		upgraded.entities().forEach(e -> volume.entity(e.pos(), e.type()));
		Schematics.Info info = info(root, fileStem);
		return new Schematics.Schematic(info, List.of(new Schematics.Region("main", BlockPos.ZERO, size)), volume.build());
	}

	/** Decodes one varint palette index per position. */
	private static void readBlocks(byte[] data, Vec3i size, List<BlockState> palette, boolean[] assigned, BlockVolume.Builder into) throws IOException {
		int width = size.getX();
		int length = size.getZ();
		long positions = (long) width * size.getY() * length;
		int at = 0;
		for (long i = 0; i < positions; i++) {
			int value = 0;
			int shift = 0;
			byte b;
			do {
				if (at >= data.length) {
					throw new IOException("Block data is truncated: it ends at block " + i + " of " + positions);
				}

				if (shift > 28) {
					throw new IOException("Block data is corrupt: a palette index at block " + i + " is too long");
				}

				b = data[at++];
				value |= (b & 0x7F) << shift;
				shift += 7;
			} while ((b & 0x80) != 0);

			// A fifth byte can set the sign bit.
			if (value < 0 || value >= palette.size() || !assigned[value]) {
				throw new IOException("Block data is corrupt: block " + i + " uses palette entry " + value + ", which isn't in the palette");
			}

			int x = (int) (i % width);
			int z = (int) ((i / width) % length);
			int y = (int) (i / ((long) width * length));
			into.set(x, y, z, palette.get(value));
		}
	}

	/**
	 * A block entity's or entity's data with the "id" the fixers dispatch on. Version 3 keeps it in "Data" beside "Id"
	 * and "Pos"; earlier versions keep the fields next to them.
	 */
	private static CompoundTag withId(CompoundTag stored) {
		CompoundTag data = stored.getCompound("Data").map(CompoundTag::copy).orElseGet(() -> {
			CompoundTag copy = stored.copy();
			copy.remove("Pos");
			copy.remove("Id");
			return copy;
		});
		stored.getString("Id").ifPresent(id -> data.putString("id", id));
		return data;
	}

	/** "minecraft:oak_stairs[facing=east,half=top]" → a block state tag in the form the file's data version used. */
	private static CompoundTag stateTag(String state, int dataVersion) {
		boolean renamed = dataVersion >= STATE_FIELD_NAMES_CHANGED;
		CompoundTag tag = new CompoundTag();
		int bracket = state.indexOf('[');
		tag.putString(renamed ? "id" : "Name", bracket < 0 ? state : state.substring(0, bracket));
		if (bracket >= 0 && state.endsWith("]")) {
			CompoundTag properties = new CompoundTag();
			for (String pair : state.substring(bracket + 1, state.length() - 1).split(",")) {
				String[] kv = pair.split("=", 2);
				if (kv.length == 2) {
					properties.putString(kv[0].trim(), kv[1].trim());
				}
			}

			tag.put(renamed ? "properties" : "Properties", properties);
		}

		return tag;
	}
}
