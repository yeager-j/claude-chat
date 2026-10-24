package claudechat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Schematic files from the player's schematics folder (Litematica's, or the game's schematics/ folder): Litematica
 * .litematic, Sponge .schem (WorldEdit) and vanilla structure .nbt. All are gzipped NBT, which Claude can't read as
 * files. Listing reads only each file's description; loading reads every block, upgraded from the version the file
 * was saved in (see {@link StructureNbt#upgrade}), so old schematics use today's names.
 */
public final class Schematics {
	/** A schematic's own description, read without its blocks. {@code recordedBlocks} is the block count the file stores, if it stores one. */
	public record Info(String name, String author, String description, Vec3i size, OptionalLong recordedBlocks) {
	}

	/** One Litematica region (other formats have a single unnamed one). Position is the region's min corner in the schematic. */
	public record Region(String name, BlockPos position, Vec3i size) {
	}

	/** A loaded schematic, all regions merged into one volume whose min corner is 0 0 0. */
	public record Schematic(Info info, List<Region> regions, BlockVolume volume) {
	}

	/** One schematic file in the folder; {@code path} is relative to the folder, with '/' separators. */
	public sealed interface Entry permits Readable, Unreadable {
		Path file();

		String path();
	}

	public record Readable(Path file, String path, Info info) implements Entry {
	}

	public record Unreadable(Path file, String path, String error) implements Entry {
	}

	/** What a query names: one schematic, several, or none. */
	public sealed interface Match permits Found, Ambiguous, NotFound {
	}

	public record Found(Entry entry) implements Match {
	}

	public record Ambiguous(List<Entry> candidates) implements Match {
	}

	public record NotFound() implements Match {
	}

	/** How one file format is read: its description from a few fields, and its blocks from the whole file. */
	interface Format {
		/** The fields {@link #info} needs, so listing can skip the block data. */
		List<FieldSelector> infoFields();

		/** The description from a tag holding at least {@link #infoFields}. {@code fileStem} names schematics that have no name of their own. */
		Info info(CompoundTag fields, String fileStem);

		/** @throws IOException if the block data is truncated or corrupt */
		Schematic read(CompoundTag root, String fileStem) throws IOException;
	}

	/** File extension → format; the one place a file's format is decided. */
	private static final Map<String, Format> FORMATS = formats();

	private static Map<String, Format> formats() {
		Map<String, Format> formats = new LinkedHashMap<>();
		formats.put(".litematic", new LitematicaFormat());
		formats.put(".schem", new SpongeFormat());
		formats.put(".nbt", new StructureFormat());
		return formats;
	}

	private Schematics() {
	}

	/**
	 * The schematics folder: Litematica's custom folder if that's switched on in config/litematica.json, otherwise
	 * schematics/ in the game folder (also where WorldEdit and other mods look). Reads Litematica's config, so resolve
	 * it once per tool call.
	 */
	public static Folder folder() {
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
						return new Folder(custom);
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			ClaudeChatClient.LOGGER.debug("Could not read Litematica's schematic folder setting", e);
		}

		return new Folder(gameDir.resolve("schematics"));
	}

	/** The schematics folder as resolved once; see {@link #folder()}. */
	public record Folder(Path dir) {
		/** Every schematic file under the folder with its description, by path. Files that can't be read are listed with the reason. */
		public List<Entry> entries() throws IOException {
			if (!Files.isDirectory(dir)) {
				return List.of();
			}

			List<Path> files;
			try (Stream<Path> walk = Files.walk(dir, 8)) {
				files = walk.filter(Files::isRegularFile).filter(f -> format(f).isPresent()).sorted(Comparator.comparing(Path::toString)).toList();
			}

			List<Entry> entries = new ArrayList<>();
			for (Path file : files) {
				String path = dir.relativize(file).toString().replace('\\', '/');
				try {
					entries.add(new Readable(file, path, info(file)));
				} catch (IOException | RuntimeException e) {
					entries.add(new Unreadable(file, path, String.valueOf(e.getMessage())));
				}
			}

			return entries;
		}
	}

	/**
	 * Whether an entry is a candidate for a query: its path or its schematic name contains it, ignoring case. A query may
	 * use '/' or '\' between folders.
	 */
	public static boolean matches(Entry entry, String query) {
		String q = normalize(query);
		return entry.path().toLowerCase(Locale.ROOT).contains(q) || name(entry).map(n -> n.contains(q)).orElse(false);
	}

	/**
	 * The schematic a query names. An exact match (the path with or without extension, the file name without extension,
	 * or the schematic's name) wins over partial ones from {@link #matches}; several equally good matches are ambiguous.
	 */
	public static Match match(List<Entry> entries, String query) {
		String q = normalize(query);
		Predicate<Entry> exact = entry -> {
			String path = entry.path().toLowerCase(Locale.ROOT);
			String fileName = path.substring(path.lastIndexOf('/') + 1);
			return path.equals(q) || stripExtension(path).equals(q) || stripExtension(fileName).equals(q) || name(entry).map(q::equals).orElse(false);
		};
		List<Entry> exactMatches = entries.stream().filter(exact).toList();
		List<Entry> candidates = exactMatches.isEmpty() ? entries.stream().filter(e -> matches(e, q)).toList() : exactMatches;
		return switch (candidates.size()) {
			case 0 -> new NotFound();
			case 1 -> new Found(candidates.getFirst());
			default -> new Ambiguous(candidates);
		};
	}

	private static String normalize(String query) {
		return query.trim().toLowerCase(Locale.ROOT).replace('\\', '/');
	}

	private static Optional<String> name(Entry entry) {
		return entry instanceof Readable readable ? Optional.of(readable.info().name().toLowerCase(Locale.ROOT)) : Optional.empty();
	}

	/** A schematic's description, reading only the fields its format needs. */
	public static Info info(Path file) throws IOException {
		Format format = format(file).orElseThrow(() -> new IOException("Not a schematic file: " + file.getFileName()));
		CollectFields fields = new CollectFields(format.infoFields().toArray(FieldSelector[]::new));
		NbtIo.parseCompressed(file, fields, NbtAccounter.unlimitedHeap());
		CompoundTag tag = fields.getResult() instanceof CompoundTag compound ? compound : new CompoundTag();
		return format.info(tag, stem(file));
	}

	/** Every block of a schematic. */
	public static Schematic load(Path file) throws IOException {
		Format format = format(file).orElseThrow(() -> new IOException("Not a schematic file: " + file.getFileName()));
		return format.read(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()), stem(file));
	}

	private static Optional<Format> format(Path file) {
		String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
		return FORMATS.entrySet().stream().filter(e -> name.endsWith(e.getKey())).map(Map.Entry::getValue).findFirst();
	}

	private static String stem(Path file) {
		return stripExtension(file.getFileName().toString());
	}

	private static String stripExtension(String name) {
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	/** The data fixer builds its rules on first use, which takes seconds; do that ahead of the first question. */
	public static void prepare() {
		CompoundTag structure = new CompoundTag();
		ListTag palette = new ListTag();
		CompoundTag stone = new CompoundTag();
		stone.putString("Name", "minecraft:stone");
		palette.add(stone);
		structure.put("palette", palette);
		CompoundTag chest = new CompoundTag();
		chest.putString("id", "minecraft:chest");
		ListTag blocks = new ListTag();
		blocks.add(StructureNbt.blockEntityEntry(BlockPos.ZERO, chest));
		structure.put("blocks", blocks);
		CompoundTag pig = new CompoundTag();
		pig.putString("id", "minecraft:pig");
		ListTag entities = new ListTag();
		entities.add(StructureNbt.entityEntry(0, 0, 0, pig));
		structure.put("entities", entities);
		StructureNbt.upgrade(structure, 1343);
	}

	/** Vanilla structure files saved with a structure block: the same layout as structure templates. */
	private static final class StructureFormat implements Format {
		@Override
		public List<FieldSelector> infoFields() {
			return List.of(new FieldSelector(ListTag.TYPE, "size"), new FieldSelector(StringTag.TYPE, "author"));
		}

		@Override
		public Info info(CompoundTag fields, String fileStem) {
			ListTag size = fields.getListOrEmpty("size");
			return new Info(fileStem, fields.getStringOr("author", ""), "", new Vec3i(size.getIntOr(0, 0), size.getIntOr(1, 0), size.getIntOr(2, 0)),
				OptionalLong.empty());
		}

		@Override
		public Schematic read(CompoundTag root, String fileStem) throws IOException {
			Info info = info(root, fileStem);
			BlockVolume volume = StructureNbt.read(StructureNbt.upgrade(root, StructureNbt.dataVersion(root))).volume(0);
			return new Schematic(info, List.of(new Region("main", BlockPos.ZERO, info.size())), volume);
		}
	}
}
