package claudechat;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Small schematic and structure files, built the way each format's writer lays them out. {@link #writeAll} writes the
 * set the schematic game test uses; the unit tests build single files.
 */
final class SchematicFixtures {
	/** Minecraft 1.12.2: before the flattening, with old spawner data and item counts as bytes. */
	static final int OLD_VERSION = 1343;
	/** Minecraft 1.16.5. */
	static final int SPONGE_V2_VERSION = 2586;

	private SchematicFixtures() {
	}

	static int currentVersion() {
		return SharedConstants.getCurrentVersion().dataVersion().version();
	}

	/**
	 * A 1.12 structure file 2×1×2: a skeleton spawner, a chest holding 5 redstone, a pig; (0 0 1) is air and (1 0 1)
	 * has no entry.
	 */
	static CompoundTag oldStructure() {
		CompoundTag root = new CompoundTag();
		root.putInt("DataVersion", OLD_VERSION);
		root.put("size", ints(2, 1, 2));
		root.putString("author", "tester");
		root.put("palette", list(legacyState("minecraft:mob_spawner", Map.of()), legacyState("minecraft:chest", Map.of("facing", "north")),
			legacyState("minecraft:air", Map.of())));

		CompoundTag spawnData = new CompoundTag();
		spawnData.putString("id", "minecraft:skeleton");
		CompoundTag spawner = new CompoundTag();
		spawner.putString("id", "minecraft:mob_spawner");
		spawner.put("SpawnData", spawnData);

		CompoundTag chest = new CompoundTag();
		chest.putString("id", "minecraft:chest");
		chest.put("Items", list(oldItem("minecraft:redstone", 5)));

		root.put("blocks", list(block(0, 0, 0, 0, spawner), block(1, 0, 0, 1, chest), block(0, 0, 1, 2, null)));
		root.put("entities", list(entity(0.5, 0, 0.5, "minecraft:pig")));
		return root;
	}

	/**
	 * A current-version Litematica file with two regions: "left" at x 0-1 (stone, air) and "right" given with a
	 * negative size, covering x 3-4 (chest holding 3 redstone, redstone wire). x 2 is in neither.
	 */
	static CompoundTag litematic() {
		CompoundTag chest = new CompoundTag();
		chest.putString("id", "minecraft:chest");
		chest.putInt("x", 0);
		chest.putInt("y", 0);
		chest.putInt("z", 0);
		chest.put("Items", list(item("minecraft:redstone", 3)));

		CompoundTag left = litematicRegion(0, 0, 0, 2, 1, 1, List.of(state("minecraft:air"), state("minecraft:stone")), new int[] {1, 0},
			List.of(), List.of());
		CompoundTag right = litematicRegion(4, 0, 0, -2, 1, 1, List.of(state("minecraft:air"), state("minecraft:chest"), state("minecraft:redstone_wire")),
			new int[] {1, 2}, List.of(chest), List.of());
		CompoundTag regions = new CompoundTag();
		regions.put("left", left);
		regions.put("right", right);
		return litematicRoot(currentVersion(), "Two Regions", 5, 1, 1, 3, regions);
	}

	/** A Litematica file whose block data is cut short. */
	static CompoundTag truncatedLitematic() {
		CompoundTag region = litematicRegion(0, 0, 0, 40, 1, 1, List.of(state("minecraft:air"), state("minecraft:stone")), new int[0],
			List.of(), List.of());
		region.putLongArray("BlockStates", new long[] {0});
		CompoundTag regions = new CompoundTag();
		regions.put("main", region);
		return litematicRoot(currentVersion(), "Truncated", 40, 1, 1, 40, regions);
	}

	/**
	 * A current-version Sponge v3 file, 4×1×1: air, structure void, stairs, and a chest holding 2 redstone; and a pig.
	 */
	static CompoundTag spongeV3() {
		CompoundTag palette = new CompoundTag();
		palette.putInt("minecraft:air", 0);
		palette.putInt("minecraft:structure_void", 1);
		palette.putInt("minecraft:oak_stairs[facing=east,half=top]", 2);
		palette.putInt("minecraft:chest[facing=north]", 3);

		CompoundTag chestData = new CompoundTag();
		chestData.put("Items", list(item("minecraft:redstone", 2)));
		CompoundTag chest = new CompoundTag();
		chest.putIntArray("Pos", new int[] {3, 0, 0});
		chest.putString("Id", "minecraft:chest");
		chest.put("Data", chestData);

		CompoundTag blocks = new CompoundTag();
		blocks.put("Palette", palette);
		blocks.putByteArray("Data", new byte[] {0, 1, 2, 3});
		blocks.put("BlockEntities", list(chest));

		CompoundTag pig = new CompoundTag();
		pig.put("Pos", doubles(0.5, 0, 0.5));
		pig.putString("Id", "minecraft:pig");
		pig.put("Data", new CompoundTag());

		CompoundTag metadata = new CompoundTag();
		metadata.putString("Name", "Sponge Three");
		metadata.putString("Author", "tester");

		CompoundTag schematic = spongeSize(4, 1, 1);
		schematic.putInt("Version", 3);
		schematic.putInt("DataVersion", currentVersion());
		schematic.put("Metadata", metadata);
		schematic.put("Blocks", blocks);
		schematic.put("Entities", list(pig));
		CompoundTag root = new CompoundTag();
		root.put("Schematic", schematic);
		return root;
	}

	/** A 1.16 Sponge v2 file, 2×1×1: a chest holding 4 redstone (item counts as bytes), then a lever. */
	static CompoundTag spongeV2() {
		CompoundTag palette = new CompoundTag();
		palette.putInt("minecraft:chest[facing=north,type=single,waterlogged=false]", 0);
		palette.putInt("minecraft:lever[face=floor,facing=north,powered=false]", 1);

		CompoundTag chest = new CompoundTag();
		chest.putIntArray("Pos", new int[] {0, 0, 0});
		chest.putString("Id", "minecraft:chest");
		chest.put("Items", list(oldItem("minecraft:redstone", 4)));

		CompoundTag root = spongeSize(2, 1, 1);
		root.putInt("Version", 2);
		root.putInt("DataVersion", SPONGE_V2_VERSION);
		root.put("Palette", palette);
		root.putByteArray("BlockData", new byte[] {0, 1});
		root.put("BlockEntities", list(chest));
		return root;
	}

	/** A Sponge v3 file with these block bytes and a two-entry palette (air, stone), 4×1×1. */
	static CompoundTag spongeWithData(byte[] data) {
		CompoundTag palette = new CompoundTag();
		palette.putInt("minecraft:air", 0);
		palette.putInt("minecraft:stone", 1);
		CompoundTag blocks = new CompoundTag();
		blocks.put("Palette", palette);
		blocks.putByteArray("Data", data);
		CompoundTag schematic = spongeSize(4, 1, 1);
		schematic.putInt("Version", 3);
		schematic.putInt("DataVersion", currentVersion());
		schematic.put("Blocks", blocks);
		CompoundTag root = new CompoundTag();
		root.put("Schematic", schematic);
		return root;
	}

	/**
	 * A small current-version redstone build for the game test's material checks: 4 stone, 3 redstone wire, a
	 * repeater, a sticky piston with its head (which needs no item), and the lower half of an iron door.
	 */
	static CompoundTag redstoneBuild() {
		List<CompoundTag> palette = List.of(state("minecraft:air"), state("minecraft:stone"), state("minecraft:redstone_wire"),
			state("minecraft:repeater"), state("minecraft:sticky_piston", Map.of("extended", "true", "facing", "up")),
			state("minecraft:piston_head", Map.of("facing", "up", "type", "sticky")), state("minecraft:iron_door", Map.of("half", "lower")));
		// 4×3×1, x fastest then y. y 0: stone ×4; y 1: wire ×3, repeater; y 2: piston, head, door, air.
		int[] cells = {1, 1, 1, 1, 2, 2, 2, 3, 4, 5, 6, 0};
		CompoundTag regions = new CompoundTag();
		regions.put("main", litematicRegion(0, 0, 0, 4, 3, 1, palette, cells, List.of(), List.of()));
		return litematicRoot(currentVersion(), "Redstone Test", 4, 3, 1, 11, regions);
	}

	/** A Sponge v3 file claiming 1024×1024×1024 positions, with no block data. */
	static CompoundTag oversizedSponge() {
		CompoundTag schematic = spongeSize(1024, 1024, 1024);
		schematic.putInt("Version", 3);
		schematic.putInt("DataVersion", currentVersion());
		schematic.put("Blocks", new CompoundTag());
		CompoundTag root = new CompoundTag();
		root.put("Schematic", schematic);
		return root;
	}

	/** A current-version structure file, 1×1×1, with {@code count} palettes of one stone block each. */
	static CompoundTag structureWithPalettes(int count) {
		ListTag palettes = new ListTag();
		for (int i = 0; i < count; i++) {
			palettes.add(list(state("minecraft:stone")));
		}

		CompoundTag root = new CompoundTag();
		root.putInt("DataVersion", currentVersion());
		root.put("size", ints(1, 1, 1));
		root.put("palettes", palettes);
		root.put("blocks", list(block(0, 0, 0, 0, null)));
		root.put("entities", new ListTag());
		return root;
	}

	/**
	 * A Litematica file with two regions over the same 512×256×257 box: each fits in a volume, but reading both visits
	 * more positions than one volume holds. There is no block data; the limit applies before it is read.
	 */
	static CompoundTag overlappingLitematic() {
		CompoundTag regions = new CompoundTag();
		regions.put("first", litematicRegion(0, 0, 0, 512, 256, 257, List.of(state("minecraft:air")), new int[0], List.of(), List.of()));
		regions.put("second", litematicRegion(0, 0, 0, 512, 256, 257, List.of(state("minecraft:air")), new int[0], List.of(), List.of()));
		return litematicRoot(currentVersion(), "Overlapping", 512, 256, 257, 0, regions);
	}

	/** A one-block Litematica file whose region lists {@code count} chests as block entities. */
	static CompoundTag litematicWithBlockEntities(int count) {
		List<CompoundTag> chests = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			CompoundTag chest = new CompoundTag();
			chest.putString("id", "minecraft:chest");
			chests.add(chest);
		}

		CompoundTag regions = new CompoundTag();
		regions.put("main", litematicRegion(0, 0, 0, 1, 1, 1, List.of(state("minecraft:chest")), new int[] {0}, chests, List.of()));
		return litematicRoot(currentVersion(), "Many Chests", 1, 1, 1, 1, regions);
	}

	/** A Litematica file of one stone block, plus {@code empty} regions that have no positions at all. */
	static CompoundTag litematicWithEmptyRegions(int empty) {
		CompoundTag regions = new CompoundTag();
		regions.put("main", litematicRegion(0, 0, 0, 1, 1, 1, List.of(state("minecraft:stone")), new int[] {0}, List.of(), List.of()));
		for (int i = 0; i < empty; i++) {
			regions.put("empty " + i, litematicRegion(0, 0, 0, 0, 0, 0, List.of(), new int[0], List.of(), List.of()));
		}

		return litematicRoot(currentVersion(), "Empty Regions", 1, 1, 1, 1, regions);
	}

	/**
	 * Writes a Sponge v2 file, 1×1×1, whose block data is {@code bytes} zeros: a few hundred kilobytes that decompress
	 * to {@code bytes}. Streamed as raw NBT, so the test never holds the array.
	 */
	static void writeZeroBomb(Path file, long bytes) throws IOException {
		Files.createDirectories(file.getParent());

		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(file))))) {
			out.writeByte(Tag.TAG_COMPOUND);
			out.writeUTF("");

			for (String side : List.of("Width", "Height", "Length")) {
				out.writeByte(Tag.TAG_SHORT);
				out.writeUTF(side);
				out.writeShort(1);
			}

			out.writeByte(Tag.TAG_BYTE_ARRAY);
			out.writeUTF("BlockData");
			out.writeInt(Math.toIntExact(bytes));
			byte[] zeros = new byte[1 << 20];
			for (long left = bytes; left > 0; left -= zeros.length) {
				out.write(zeros, 0, (int) Math.min(left, zeros.length));
			}

			out.writeByte(Tag.TAG_END);
		}
	}

	/**
	 * Writes the game test's schematics folder: several formats, a subfolder, two names that "witch" both match, a
	 * broken file, and one that decompresses to more than {@link NbtBudget#FILE_BYTES}.
	 */
	static void writeAll(Path dir) throws IOException {
		write(dir.resolve("Redstone Test.litematic"), redstoneBuild());
		write(dir.resolve("farms/Witch Farm.litematic"), litematic());
		write(dir.resolve("farms/Witch Hut.schem"), spongeV3());
		write(dir.resolve("old/Old Spawner.nbt"), oldStructure());
		write(dir.resolve("old/Sponge Two.schem"), spongeV2());
		Files.write(dir.resolve("old/Broken.litematic"), new byte[] {1, 2, 3});
		writeZeroBomb(dir.resolve("old/Bomb.schem"), NbtBudget.FILE_BYTES + (1 << 20));
	}

	static void write(Path file, CompoundTag root) throws IOException {
		Files.createDirectories(file.getParent());
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		NbtIo.writeCompressed(root, bytes);
		Files.write(file, bytes.toByteArray());
	}

	// --- building blocks --------------------------------------------------------------------------------------------

	private static CompoundTag litematicRoot(int dataVersion, String name, int sx, int sy, int sz, int totalBlocks, CompoundTag regions) {
		CompoundTag enclosing = new CompoundTag();
		enclosing.putInt("x", sx);
		enclosing.putInt("y", sy);
		enclosing.putInt("z", sz);
		CompoundTag metadata = new CompoundTag();
		metadata.putString("Name", name);
		metadata.putString("Author", "tester");
		metadata.putString("Description", "a test");
		metadata.put("EnclosingSize", enclosing);
		metadata.putInt("TotalBlocks", totalBlocks);
		CompoundTag root = new CompoundTag();
		root.putInt("Version", 6);
		root.putInt("MinecraftDataVersion", dataVersion);
		root.put("Metadata", metadata);
		root.put("Regions", regions);
		return root;
	}

	private static CompoundTag litematicRegion(int px, int py, int pz, int sx, int sy, int sz, List<CompoundTag> palette, int[] cells,
		List<CompoundTag> blockEntities, List<CompoundTag> entities) {
		CompoundTag region = new CompoundTag();
		region.put("Position", xyz(px, py, pz));
		region.put("Size", xyz(sx, sy, sz));
		region.put("BlockStatePalette", list(palette.toArray(Tag[]::new)));
		int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
		region.putLongArray("BlockStates", pack(cells, bits));
		region.put("TileEntities", list(blockEntities.toArray(Tag[]::new)));
		region.put("Entities", list(entities.toArray(Tag[]::new)));
		return region;
	}

	/** Litematica's packing: entry i takes bits [i*bits, (i+1)*bits), spanning two longs where it must. */
	private static long[] pack(int[] values, int bits) {
		long[] packed = new long[(int) (((long) values.length * bits + 63) / 64)];
		for (int i = 0; i < values.length; i++) {
			long start = (long) i * bits;
			int index = (int) (start >> 6);
			int offset = (int) (start & 63);
			packed[index] |= (long) values[i] << offset;
			if (offset + bits > 64) {
				packed[index + 1] |= (long) values[i] >>> (64 - offset);
			}
		}

		return packed;
	}

	private static CompoundTag spongeSize(int width, int height, int length) {
		CompoundTag tag = new CompoundTag();
		tag.putShort("Width", (short) width);
		tag.putShort("Height", (short) height);
		tag.putShort("Length", (short) length);
		return tag;
	}

	/** A block state tag as written since data version 5006. */
	static CompoundTag state(String id) {
		return state(id, Map.of());
	}

	static CompoundTag state(String id, Map<String, String> properties) {
		CompoundTag tag = new CompoundTag();
		tag.putString("id", id);
		if (!properties.isEmpty()) {
			CompoundTag props = new CompoundTag();
			new LinkedHashMap<>(properties).forEach(props::putString);
			tag.put("properties", props);
		}

		return tag;
	}

	private static CompoundTag legacyState(String name, Map<String, String> properties) {
		CompoundTag tag = new CompoundTag();
		tag.putString("Name", name);
		if (!properties.isEmpty()) {
			CompoundTag props = new CompoundTag();
			properties.forEach(props::putString);
			tag.put("Properties", props);
		}

		return tag;
	}

	private static CompoundTag block(int x, int y, int z, int state, CompoundTag nbt) {
		CompoundTag block = new CompoundTag();
		block.put("pos", ints(x, y, z));
		block.putInt("state", state);
		if (nbt != null) {
			block.put("nbt", nbt);
		}

		return block;
	}

	private static CompoundTag entity(double x, double y, double z, String id) {
		CompoundTag nbt = new CompoundTag();
		nbt.putString("id", id);
		CompoundTag entity = new CompoundTag();
		entity.put("pos", doubles(x, y, z));
		entity.put("blockPos", ints((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
		entity.put("nbt", nbt);
		return entity;
	}

	/** An item stack as saved before 1.20.5: count as a byte. */
	private static CompoundTag oldItem(String id, int count) {
		CompoundTag item = new CompoundTag();
		item.putByte("Slot", (byte) 0);
		item.putString("id", id);
		item.putByte("Count", (byte) count);
		return item;
	}

	private static CompoundTag item(String id, int count) {
		CompoundTag item = new CompoundTag();
		item.putByte("Slot", (byte) 0);
		item.putString("id", id);
		item.putInt("count", count);
		return item;
	}

	private static CompoundTag xyz(int x, int y, int z) {
		CompoundTag tag = new CompoundTag();
		tag.putInt("x", x);
		tag.putInt("y", y);
		tag.putInt("z", z);
		return tag;
	}

	private static ListTag ints(int... values) {
		ListTag list = new ListTag();
		for (int value : values) {
			list.add(IntTag.valueOf(value));
		}

		return list;
	}

	private static ListTag doubles(double... values) {
		ListTag list = new ListTag();
		for (double value : values) {
			list.add(DoubleTag.valueOf(value));
		}

		return list;
	}

	private static ListTag list(Tag... tags) {
		ListTag list = new ListTag();
		list.addAll(List.of(tags));
		return list;
	}
}
