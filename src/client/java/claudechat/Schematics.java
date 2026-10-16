package claudechat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Dynamic;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Schematic files from the player's schematics folder (Litematica's, or the game's schematics/ folder): Litematica
 * .litematic, Sponge .schem (WorldEdit) and vanilla structure .nbt. All are gzipped NBT, which Claude can't read as
 * files. Block states are data-fixed from the version the file was saved in, so old schematics use today's names.
 */
public final class Schematics {
	private static final List<String> EXTENSIONS = List.of(".litematic", ".schem", ".nbt");

	/** One Litematica region (other formats have a single unnamed one). Position is the region's min corner in the schematic. */
	public record Region(String name, BlockPos position, Vec3i size) {
	}

	/** A loaded schematic, all regions merged into one box whose min corner is 0 0 0. */
	public record Schematic(Path file, String name, String author, String description, Vec3i size, List<Region> regions,
		List<StructureTemplates.BlockInfo> blocks, List<StructureTemplates.EntityInfo> entities) {
	}

	private Schematics() {
	}

	/**
	 * Litematica's schematics folder: its custom folder if that's switched on in config/litematica.json, otherwise
	 * schematics/ in the game folder (also where WorldEdit and other mods look).
	 */
	public static Path directory() {
		Path gameDir = FabricLoader.getInstance().getGameDir();
		Path config = FabricLoader.getInstance().getConfigDir().resolve("litematica.json");
		try {
			if (Files.isRegularFile(config)) {
				JsonObject json = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
				JsonObject generic = json.has("Generic") ? json.getAsJsonObject("Generic") : json;
				if (generic.has("customSchematicBaseDirectoryEnabled") && generic.get("customSchematicBaseDirectoryEnabled").getAsBoolean()
					&& generic.has("customSchematicBaseDirectory")) {
					Path custom = Path.of(generic.get("customSchematicBaseDirectory").getAsString());
					if (Files.isDirectory(custom)) {
						return custom;
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			ClaudeChatClient.LOGGER.debug("Could not read Litematica's schematic folder setting", e);
		}
		return gameDir.resolve("schematics");
	}

	/** Every schematic file under the folder, by path. */
	public static List<Path> list() throws IOException {
		Path dir = directory();
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> files = Files.walk(dir, 8)) {
			return files.filter(Files::isRegularFile)
				.filter(f -> EXTENSIONS.stream().anyMatch(ext -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(ext)))
				.sorted(Comparator.comparing(Path::toString)).toList();
		}
	}

	/** "witch farm" → Witch Farm.litematic: exact file name, then file or schematic name containing the query. */
	public static Optional<Path> find(String query) throws IOException {
		String q = query.trim().toLowerCase(Locale.ROOT);
		List<Path> files = list();
		Path dir = directory();
		for (Path file : files) {
			String relative = dir.relativize(file).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
			if (relative.equals(q) || stripExtension(relative).equals(q) || stripExtension(file.getFileName().toString().toLowerCase(Locale.ROOT)).equals(q)) {
				return Optional.of(file);
			}
		}
		for (Path file : files) {
			if (dir.relativize(file).toString().toLowerCase(Locale.ROOT).contains(q)) {
				return Optional.of(file);
			}
		}
		for (Path file : files) {
			try {
				if (load(file).name().toLowerCase(Locale.ROOT).contains(q)) {
					return Optional.of(file);
				}
			} catch (IOException | RuntimeException ignored) {
			}
		}
		return Optional.empty();
	}

	public static String relativeName(Path file) {
		return directory().relativize(file).toString().replace('\\', '/');
	}

	private static String stripExtension(String name) {
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	public static Schematic load(Path file) throws IOException {
		CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
		String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
		if (name.endsWith(".litematic")) {
			return litematic(file, root);
		}
		if (name.endsWith(".schem")) {
			return sponge(file, root);
		}
		return structure(file, root);
	}

	// --- Litematica ---------------------------------------------------------------------------------------------

	/**
	 * Regions each have a position and a size that may be negative (the region extends from the position the other way).
	 * Block states are a palette plus indices packed into longs; unlike chunk sections, an index may span two longs.
	 */
	private static Schematic litematic(Path file, CompoundTag root) {
		int dataVersion = root.getIntOr("MinecraftDataVersion", 0);
		CompoundTag meta = root.getCompoundOrEmpty("Metadata");
		CompoundTag regionsTag = root.getCompoundOrEmpty("Regions");

		record Raw(String name, int minX, int minY, int minZ, int sx, int sy, int sz, CompoundTag tag) {
		}
		List<Raw> raws = new ArrayList<>();
		for (String regionName : regionsTag.keySet()) {
			CompoundTag r = regionsTag.getCompoundOrEmpty(regionName);
			CompoundTag pos = r.getCompoundOrEmpty("Position");
			CompoundTag size = r.getCompoundOrEmpty("Size");
			int[] p = {pos.getIntOr("x", 0), pos.getIntOr("y", 0), pos.getIntOr("z", 0)};
			int[] s = {size.getIntOr("x", 0), size.getIntOr("y", 0), size.getIntOr("z", 0)};
			int[] min = new int[3];
			for (int i = 0; i < 3; i++) {
				min[i] = s[i] < 0 ? p[i] + s[i] + 1 : p[i];
			}
			raws.add(new Raw(regionName, min[0], min[1], min[2], Math.abs(s[0]), Math.abs(s[1]), Math.abs(s[2]), r));
		}
		int minX = raws.stream().mapToInt(Raw::minX).min().orElse(0);
		int minY = raws.stream().mapToInt(Raw::minY).min().orElse(0);
		int minZ = raws.stream().mapToInt(Raw::minZ).min().orElse(0);
		int maxX = raws.stream().mapToInt(r -> r.minX() + r.sx()).max().orElse(0);
		int maxY = raws.stream().mapToInt(r -> r.minY() + r.sy()).max().orElse(0);
		int maxZ = raws.stream().mapToInt(r -> r.minZ() + r.sz()).max().orElse(0);

		List<Region> regions = new ArrayList<>();
		List<StructureTemplates.BlockInfo> blocks = new ArrayList<>();
		List<StructureTemplates.EntityInfo> entities = new ArrayList<>();
		for (Raw raw : raws) {
			int ox = raw.minX() - minX;
			int oy = raw.minY() - minY;
			int oz = raw.minZ() - minZ;
			regions.add(new Region(raw.name(), new BlockPos(ox, oy, oz), new Vec3i(raw.sx(), raw.sy(), raw.sz())));
			List<BlockState> palette = palette(raw.tag().getListOrEmpty("BlockStatePalette"), dataVersion);
			long[] packed = raw.tag().getLongArray("BlockStates").orElse(new long[0]);
			int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));

			// Block entities are stored by position within the region.
			java.util.Map<BlockPos, CompoundTag> blockEntities = new java.util.HashMap<>();
			raw.tag().getListOrEmpty("TileEntities").compoundStream().forEach(be -> blockEntities.put(
				new BlockPos(be.getIntOr("x", 0), be.getIntOr("y", 0), be.getIntOr("z", 0)), be));

			long volume = (long) raw.sx() * raw.sy() * raw.sz();
			for (long i = 0; i < volume; i++) {
				int index = get(packed, bits, i);
				BlockState state = index < palette.size() ? palette.get(index) : Blocks.AIR.defaultBlockState();
				if (state.isAir() && index == 0) {
					continue; // palette entry 0 is air: unset, like a structure void
				}
				int x = (int) (i % raw.sx());
				int z = (int) ((i / raw.sx()) % raw.sz());
				int y = (int) (i / ((long) raw.sx() * raw.sz()));
				blocks.add(new StructureTemplates.BlockInfo(new BlockPos(ox + x, oy + y, oz + z), state, blockEntities.get(new BlockPos(x, y, z))));
			}
			raw.tag().getListOrEmpty("Entities").compoundStream().forEach(e -> {
				ListTag pos = e.getListOrEmpty("Pos");
				entities.add(new StructureTemplates.EntityInfo(BlockPos.containing(ox + pos.getDoubleOr(0, 0), oy + pos.getDoubleOr(1, 0),
					oz + pos.getDoubleOr(2, 0)), e.getStringOr("id", "?")));
			});
		}
		String displayName = meta.getStringOr("Name", stripExtension(file.getFileName().toString()));
		return new Schematic(file, displayName, meta.getStringOr("Author", ""), meta.getStringOr("Description", ""),
			new Vec3i(maxX - minX, maxY - minY, maxZ - minZ), regions, blocks, entities);
	}

	/** Litematica's bit array: entry i occupies bits [i*bits, (i+1)*bits), possibly across two longs. */
	private static int get(long[] packed, int bits, long i) {
		long start = i * bits;
		int startLong = (int) (start >> 6);
		int endLong = (int) ((start + bits - 1) >> 6);
		int offset = (int) (start & 63);
		long mask = (1L << bits) - 1;
		if (startLong >= packed.length) {
			return 0;
		}
		if (startLong == endLong || endLong >= packed.length) {
			return (int) ((packed[startLong] >>> offset) & mask);
		}
		return (int) (((packed[startLong] >>> offset) | (packed[endLong] << (64 - offset))) & mask);
	}

	// --- Sponge (.schem) ----------------------------------------------------------------------------------------

	/**
	 * Version 2 keeps everything at the top level; version 3 nests it under "Schematic" with blocks in "Blocks".
	 * The palette maps state strings ("minecraft:oak_stairs[facing=east]") to indices, stored as varints in x, z, y order.
	 */
	private static Schematic sponge(Path file, CompoundTag root) {
		CompoundTag s = root.getCompound("Schematic").orElse(root);
		int dataVersion = s.getIntOr("DataVersion", 0);
		int width = s.getShortOr("Width", (short) 0) & 0xFFFF;
		int height = s.getShortOr("Height", (short) 0) & 0xFFFF;
		int length = s.getShortOr("Length", (short) 0) & 0xFFFF;
		CompoundTag container = s.getCompound("Blocks").orElse(s);
		CompoundTag paletteTag = container.getCompoundOrEmpty("Palette");
		byte[] data = container.getByteArray(container.contains("Data") ? "Data" : "BlockData").orElse(new byte[0]);
		ListTag blockEntityList = container.getListOrEmpty("BlockEntities");

		BlockState[] palette = new BlockState[paletteTag.size()];
		for (String key : paletteTag.keySet()) {
			int index = paletteTag.getIntOr(key, 0);
			if (index >= 0 && index < palette.length) {
				palette[index] = fix(stateTag(key), dataVersion);
			}
		}
		java.util.Map<BlockPos, CompoundTag> blockEntities = new java.util.HashMap<>();
		blockEntityList.compoundStream().forEach(be -> be.getIntArray("Pos").filter(p -> p.length == 3)
			.ifPresent(p -> blockEntities.put(new BlockPos(p[0], p[1], p[2]), be.getCompound("Data").orElse(be))));

		List<StructureTemplates.BlockInfo> blocks = new ArrayList<>();
		int i = 0;
		int pos = 0;
		while (pos < data.length && i < width * height * length) {
			int value = 0;
			int shift = 0;
			byte b;
			do {
				b = data[pos++];
				value |= (b & 0x7F) << shift;
				shift += 7;
			} while ((b & 0x80) != 0 && pos < data.length);
			BlockState state = value < palette.length && palette[value] != null ? palette[value] : Blocks.AIR.defaultBlockState();
			int x = i % width;
			int z = (i / width) % length;
			int y = i / (width * length);
			BlockPos p = new BlockPos(x, y, z);
			if (!state.is(Blocks.STRUCTURE_VOID)) {
				blocks.add(new StructureTemplates.BlockInfo(p, state, blockEntities.get(p)));
			}
			i++;
		}
		List<StructureTemplates.EntityInfo> entities = new ArrayList<>();
		s.getListOrEmpty("Entities").compoundStream().forEach(e -> {
			ListTag p = e.getListOrEmpty("Pos");
			entities.add(new StructureTemplates.EntityInfo(BlockPos.containing(p.getDoubleOr(0, 0), p.getDoubleOr(1, 0), p.getDoubleOr(2, 0)),
				e.getStringOr("Id", e.getStringOr("id", "?"))));
		});
		CompoundTag meta = s.getCompoundOrEmpty("Metadata");
		Vec3i size = new Vec3i(width, height, length);
		return new Schematic(file, meta.getStringOr("Name", stripExtension(file.getFileName().toString())), meta.getStringOr("Author", ""), "",
			size, List.of(new Region("main", BlockPos.ZERO, size)), blocks, entities);
	}

	/** "minecraft:oak_stairs[facing=east,half=top]" → {Name, Properties}. */
	private static CompoundTag stateTag(String state) {
		CompoundTag tag = new CompoundTag();
		int bracket = state.indexOf('[');
		tag.putString("Name", bracket < 0 ? state : state.substring(0, bracket));
		if (bracket >= 0 && state.endsWith("]")) {
			CompoundTag properties = new CompoundTag();
			for (String pair : state.substring(bracket + 1, state.length() - 1).split(",")) {
				String[] kv = pair.split("=", 2);
				if (kv.length == 2) {
					properties.putString(kv[0].trim(), kv[1].trim());
				}
			}
			tag.put("Properties", properties);
		}
		return tag;
	}

	// --- vanilla structure (.nbt) -------------------------------------------------------------------------------

	private static Schematic structure(Path file, CompoundTag root) {
		int dataVersion = root.getIntOr("DataVersion", 500);
		ListTag sizeTag = root.getListOrEmpty("size");
		Vec3i size = new Vec3i(sizeTag.getIntOr(0, 0), sizeTag.getIntOr(1, 0), sizeTag.getIntOr(2, 0));
		Optional<ListTag> palettes = root.getList("palettes");
		List<BlockState> palette = palette(palettes.map(list -> list.getListOrEmpty(0)).orElseGet(() -> root.getListOrEmpty("palette")), dataVersion);
		List<StructureTemplates.BlockInfo> blocks = new ArrayList<>();
		root.getListOrEmpty("blocks").compoundStream().forEach(b -> {
			ListTag p = b.getListOrEmpty("pos");
			int state = b.getIntOr("state", 0);
			blocks.add(new StructureTemplates.BlockInfo(new BlockPos(p.getIntOr(0, 0), p.getIntOr(1, 0), p.getIntOr(2, 0)),
				state >= 0 && state < palette.size() ? palette.get(state) : Blocks.AIR.defaultBlockState(), b.getCompound("nbt").orElse(null)));
		});
		List<StructureTemplates.EntityInfo> entities = new ArrayList<>();
		root.getListOrEmpty("entities").compoundStream().forEach(e -> {
			ListTag p = e.getListOrEmpty("blockPos");
			entities.add(new StructureTemplates.EntityInfo(new BlockPos(p.getIntOr(0, 0), p.getIntOr(1, 0), p.getIntOr(2, 0)),
				e.getCompoundOrEmpty("nbt").getStringOr("id", "?")));
		});
		return new Schematic(file, stripExtension(file.getFileName().toString()), root.getStringOr("author", ""), "", size,
			List.of(new Region("main", BlockPos.ZERO, size)), blocks, entities);
	}

	// --- shared -------------------------------------------------------------------------------------------------

	private static List<BlockState> palette(ListTag list, int dataVersion) {
		List<BlockState> states = new ArrayList<>();
		for (int i = 0; i < list.size(); i++) {
			states.add(fix(list.getCompoundOrEmpty(i), dataVersion));
		}
		return states;
	}

	/** The data fixer builds its block state rules on first use, which takes seconds; do that ahead of the first question. */
	public static void prepare() {
		fix(stateTag("minecraft:stone"), 1343);
	}

	/** Brings a {Name, Properties} block state from the file's version up to today's, then resolves it. */
	private static BlockState fix(CompoundTag state, int dataVersion) {
		int current = SharedConstants.getCurrentVersion().dataVersion().version();
		Tag fixed = state;
		if (dataVersion > 0 && dataVersion < current) {
			fixed = DataFixers.getDataFixer().update(References.BLOCK_STATE, new Dynamic<>(NbtOps.INSTANCE, (Tag) state), dataVersion, current).getValue();
		}
		return NbtUtils.readBlockState(BuiltInRegistries.BLOCK, fixed instanceof CompoundTag tag ? tag : state);
	}
}
