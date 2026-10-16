package claudechat;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueOutput;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What's stored where in the current single-player world, for the world tools: every container with its contents
 * (including items inside shulker boxes and bundles), unopened loot chests, entities that hold items or are named or
 * tamed (chest minecarts, item frames, armor stands, dropped items, pets), each player's inventory and ender chest,
 * and how long players have spent near each chunk (its InhabitedTime, used to tell visited structures apart).
 * <p>
 * Saved chunks are read straight from the region files (read-only; only chunks whose save timestamp changed are
 * re-read). Chunks that are loaded right now are captured live from the server instead, since the save on disk can be
 * up to an autosave (5 minutes) behind.
 */
public class WorldIndex {
	private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
	private static final int MAX_NESTING = 2;

	/** One chunk of one dimension; block entities and entities are stored in separate region folders. */
	public record ChunkKey(String dimension, int x, int z, boolean entities) {
	}

	/** An item stack, with what's inside it if it's a shulker box or bundle. */
	public record Item(String id, String name, int count, List<String> enchantments, List<Item> contents) {
	}

	/** Something that holds items or is worth finding: a container block, an entity, or a player. */
	public record Holder(String kind, String dimension, int x, int y, int z, String type, String name,
		String lootTable, boolean tamed, List<Item> items, boolean live) {
	}

	public record PlayerInfo(String name, String dimension, double x, double y, double z, List<Item> inventory, List<Item> enderChest) {
	}

	/** Everything at one moment: saved data merged with the live loaded chunks. */
	public record Snapshot(List<Holder> holders, List<PlayerInfo> players, Map<ChunkKey, Long> inhabitedTicks) {
		/** How long players have spent within mob-spawning range (128 blocks) of a chunk, or -1 if it was never generated. */
		public long inhabited(String dimension, int chunkX, int chunkZ) {
			return inhabitedTicks.getOrDefault(new ChunkKey(dimension, chunkX, chunkZ, false), -1L);
		}
	}

	private volatile MinecraftServer server;
	/** Last-seen save timestamp per chunk, so rescans only parse what changed. */
	private final Map<ChunkKey, Integer> timestamps = new ConcurrentHashMap<>();
	private final Map<ChunkKey, List<Holder>> savedHolders = new ConcurrentHashMap<>();
	private final Map<ChunkKey, Long> savedInhabited = new ConcurrentHashMap<>();
	/**
	 * Which blocks each saved chunk may contain (the union of its sections' palettes, as sorted ids from
	 * {@link #blockIds}), so block searches only re-read chunks that can hold a match. Ints rather than strings keep
	 * this small in big worlds.
	 */
	private final Map<ChunkKey, int[]> savedBlocks = new ConcurrentHashMap<>();
	private final Map<String, Integer> blockIds = new ConcurrentHashMap<>();
	private final Map<String, Path> dimensionDirs = new ConcurrentHashMap<>();
	private int failuresLogged;

	/** A single-player world opened. Scans it in the background so the first question is quick. */
	public void start(MinecraftServer server) {
		this.server = server;
		timestamps.clear();
		savedHolders.clear();
		savedInhabited.clear();
		savedBlocks.clear();
		dimensionDirs.clear();
		Thread thread = new Thread(() -> {
			try {
				snapshot();
			} catch (Exception e) {
				ClaudeChatClient.LOGGER.warn("Could not index the world", e);
			}
		}, "Claude Chat world index");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		thread.start();
	}

	public void stop() {
		server = null;
	}

	public MinecraftServer server() {
		return server;
	}

	/**
	 * Rescan chunks saved since last time, capture the loaded ones live, and merge. Usually well under a second.
	 * Call from a background thread (not the server or render thread).
	 */
	public synchronized Snapshot snapshot() throws Exception {
		MinecraftServer server = this.server;
		if (server == null) {
			throw new IllegalStateException("No single-player world is open.");
		}
		long start = System.nanoTime();
		RegistryOps<Tag> ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
		Path worldRoot = server.getWorldPath(LevelResource.ROOT);
		int changed = 0;
		for (ServerLevel level : server.getAllLevels()) {
			Path dimensionDir = DimensionType.getStorageFolder(level.dimension(), worldRoot);
			dimensionDirs.put(dimensionName(level.dimension()), dimensionDir);
			changed += scanRegions(server, ops, dimensionName(level.dimension()), dimensionDir.resolve("region"), false);
			changed += scanRegions(server, ops, dimensionName(level.dimension()), dimensionDir.resolve("entities"), true);
		}

		Live live = server.submit(() -> captureLive(server, ops)).get(20, TimeUnit.SECONDS);
		List<Holder> holders = new ArrayList<>();
		savedHolders.forEach((key, chunkHolders) -> {
			if (!live.loaded().contains(key)) {
				holders.addAll(chunkHolders);
			}
		});
		holders.addAll(live.holders());
		Map<ChunkKey, Long> inhabited = new HashMap<>(savedInhabited);
		inhabited.putAll(live.inhabited());

		ClaudeChatClient.LOGGER.debug("World snapshot: {} saved chunks re-read, {} holders, {} ms",
			changed, holders.size(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
		return new Snapshot(holders, live.players(), inhabited);
	}

	// --- saved chunks ---------------------------------------------------------------------------------------------

	private int scanRegions(MinecraftServer server, RegistryOps<Tag> ops, String dimension, Path dir, boolean entities) {
		if (!Files.isDirectory(dir)) {
			return 0;
		}
		int changed = 0;
		List<Path> regions;
		try (Stream<Path> files = Files.list(dir)) {
			regions = files.filter(f -> REGION_NAME.matcher(f.getFileName().toString()).matches()).toList();
		} catch (IOException e) {
			return 0;
		}
		for (Path region : regions) {
			Matcher m = REGION_NAME.matcher(region.getFileName().toString());
			m.matches();
			int regionX = Integer.parseInt(m.group(1));
			int regionZ = Integer.parseInt(m.group(2));
			try (FileChannel channel = FileChannel.open(region, StandardOpenOption.READ)) {
				if (channel.size() < 8192) {
					continue;
				}
				ByteBuffer header = ByteBuffer.allocate(8192);
				readFully(channel, header, 0);
				for (int i = 0; i < 1024; i++) {
					int location = header.getInt(i * 4);
					ChunkKey key = new ChunkKey(dimension, regionX * 32 + (i & 31), regionZ * 32 + (i >> 5), entities);
					if (location == 0) {
						if (timestamps.remove(key) != null) {
							savedHolders.remove(key);
							savedInhabited.remove(key);
							savedBlocks.remove(key);
						}
						continue;
					}
					int timestamp = header.getInt(4096 + i * 4);
					if (Integer.valueOf(timestamp).equals(timestamps.get(key))) {
						continue;
					}
					// The game may be writing this region right now; a torn read just fails and is retried next time.
					try {
						CompoundTag chunk = readChunk(channel, region, location, key);
						if (chunk != null) {
							chunk = upgrade(server, chunk, entities ? DataFixTypes.ENTITY_CHUNK : DataFixTypes.CHUNK);
							savedHolders.put(key, describeChunk(ops, dimension, chunk, entities));
							if (!entities) {
								// Only fully generated chunks count: structure searches (ours and /locate's) can leave
								// partly generated "structure starts" chunks on disk that nobody has ever been near.
								String status = chunk.getStringOr("Status", "");
								if (status.equals("minecraft:full") || status.equals("full")) {
									savedInhabited.put(key, chunk.getLongOr("InhabitedTime", 0L));
									savedBlocks.put(key, paletteIds(chunk));
								} else {
									savedInhabited.remove(key);
									savedBlocks.remove(key);
								}
							}
							timestamps.put(key, timestamp);
							changed++;
						}
					} catch (IOException | RuntimeException e) {
						logFailure("Skipping chunk " + key + " for now", e);
					}
				}
			} catch (IOException e) {
				logFailure("Could not read region " + region, e);
			}
		}
		return changed;
	}

	/** Every block id in the chunk's section palettes. Palettes can list blocks that are no longer there, never the reverse. */
	private int[] paletteIds(CompoundTag chunk) {
		Set<Integer> ids = new HashSet<>();
		for (CompoundTag section : compounds(chunk.getListOrEmpty("sections"))) {
			section.getCompound("block_states").ifPresent(states -> {
				for (Tag entry : states.getListOrEmpty("palette")) {
					ids.add(blockIds.computeIfAbsent(paletteName(entry), name -> blockIds.size()));
				}
			});
		}
		return ids.stream().mapToInt(Integer::intValue).sorted().toArray();
	}

	/**
	 * The block id of a section palette entry. In 26.x a block in its default state is saved as just its id
	 * ("minecraft:spawner"); other states are {id, properties} compounds (before 26.x, {Name, Properties}).
	 */
	public static String paletteName(Tag entry) {
		if (entry instanceof CompoundTag compound) {
			return compound.getString("id").or(() -> compound.getString("Name")).orElse("");
		}
		return entry.asString().orElse("");
	}

	/**
	 * Saved, fully generated block chunks of a dimension whose palettes include any of these block ids, as of the last
	 * {@link #snapshot()}. Includes chunks that are loaded right now; callers prefer the live copy of those.
	 */
	public List<ChunkKey> savedChunksContaining(String dimension, Set<String> ids) {
		int[] wanted = ids.stream().map(blockIds::get).filter(Objects::nonNull).mapToInt(Integer::intValue).toArray();
		List<ChunkKey> chunks = new ArrayList<>();
		if (wanted.length == 0) {
			return chunks;
		}
		savedBlocks.forEach((key, palette) -> {
			if (key.dimension().equals(dimension) && Arrays.stream(wanted).anyMatch(id -> Arrays.binarySearch(palette, id) >= 0)) {
				chunks.add(key);
			}
		});
		return chunks;
	}

	/** Re-read one saved chunk, upgraded to the current version, or null if it isn't on disk. Any thread. */
	public CompoundTag readSavedChunk(ChunkKey key) throws IOException {
		MinecraftServer server = this.server;
		Path dimensionDir = dimensionDirs.get(key.dimension());
		if (server == null || dimensionDir == null) {
			return null;
		}
		Path region = dimensionDir.resolve(key.entities() ? "entities" : "region")
			.resolve("r." + (key.x() >> 5) + "." + (key.z() >> 5) + ".mca");
		if (!Files.isRegularFile(region)) {
			return null;
		}
		try (FileChannel channel = FileChannel.open(region, StandardOpenOption.READ)) {
			ByteBuffer entry = ByteBuffer.allocate(4);
			readFully(channel, entry, ((key.x() & 31) + (key.z() & 31) * 32) * 4L);
			int location = entry.getInt(0);
			if (location == 0) {
				return null;
			}
			CompoundTag chunk = readChunk(channel, region, location, key);
			return chunk == null ? null : upgrade(server, chunk, key.entities() ? DataFixTypes.ENTITY_CHUNK : DataFixTypes.CHUNK);
		}
	}

	/** Warn about the first few failures (a torn read now and then is normal; a failure on every chunk is a bug). */
	private void logFailure(String message, Exception e) {
		if (failuresLogged++ < 3) {
			ClaudeChatClient.LOGGER.warn(message, e);
		} else {
			ClaudeChatClient.LOGGER.debug(message, e);
		}
	}

	private static CompoundTag readChunk(FileChannel channel, Path region, int location, ChunkKey key) throws IOException {
		long offset = (long) (location >>> 8) * 4096;
		// While the game runs, the region file isn't padded to whole sectors yet (that happens when it's closed),
		// so read the chunk's own length rather than assuming its last sector is complete.
		ByteBuffer prefix = ByteBuffer.allocate(5);
		readFully(channel, prefix, offset);
		int length = prefix.getInt(0);
		byte type = prefix.get(4);
		if (length <= 1 || length > (location & 0xFF) * 4096) {
			return null;
		}
		ByteBuffer data = ByteBuffer.allocate(4 + length);
		readFully(channel, data, offset);
		InputStream raw;
		if ((type & 0x80) != 0) {
			// Oversized chunk stored next to the region file as c.<x>.<z>.mcc
			raw = Files.newInputStream(region.resolveSibling("c." + key.x() + "." + key.z() + ".mcc"));
		} else {
			raw = new ByteArrayInputStream(data.array(), 5, length - 1);
		}
		RegionFileVersion version = RegionFileVersion.fromId(type & 0x7F);
		if (version == null) {
			raw.close();
			return null;
		}
		try (DataInputStream in = new DataInputStream(version.wrap(raw))) {
			return NbtIo.read(in);
		}
	}

	private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
		while (buffer.hasRemaining()) {
			if (channel.read(buffer, position + buffer.position()) < 0) {
				throw new IOException("unexpected end of region file at " + (position + buffer.position()) + " of " + channel.size());
			}
		}
	}

	private static List<Holder> describeChunk(RegistryOps<Tag> ops, String dimension, CompoundTag chunk, boolean entities) {
		List<Holder> holders = new ArrayList<>();
		if (entities) {
			for (CompoundTag entity : compounds(chunk.getListOrEmpty("Entities"))) {
				addIfPresent(holders, describeEntity(ops, dimension, entity.getStringOr("id", "?"), entity, false));
			}
		} else {
			for (CompoundTag blockEntity : compounds(chunk.getListOrEmpty("block_entities"))) {
				addIfPresent(holders, describeBlockEntity(ops, dimension, blockEntity, false));
			}
		}
		return holders;
	}

	/** Chunks saved by older versions are upgraded with the game's own data fixers; if that fails, use them as they are. */
	private static CompoundTag upgrade(MinecraftServer server, CompoundTag chunk, DataFixTypes type) {
		int current = SharedConstants.getCurrentVersion().dataVersion().version();
		int version = NbtUtils.getDataVersion(chunk, current);
		if (version >= current) {
			return chunk;
		}
		try {
			return type.updateToCurrentVersion(server.getFixerUpper(), chunk, version);
		} catch (RuntimeException e) {
			return chunk;
		}
	}

	// --- live state -----------------------------------------------------------------------------------------------

	private record Live(Set<ChunkKey> loaded, List<Holder> holders, List<PlayerInfo> players, Map<ChunkKey, Long> inhabited) {
	}

	/** Server thread: what's in the chunks around players right now, plus the players themselves. */
	private static Live captureLive(MinecraftServer server, RegistryOps<Tag> ops) {
		Set<ChunkKey> loaded = new HashSet<>();
		List<Holder> holders = new ArrayList<>();
		List<PlayerInfo> players = new ArrayList<>();
		Map<ChunkKey, Long> inhabited = new HashMap<>();
		for (ServerLevel level : server.getAllLevels()) {
			String dimension = dimensionName(level.dimension());
			for (LevelChunk chunk : loadedChunks(server, level)) {
				ChunkKey blocks = new ChunkKey(dimension, chunk.getPos().x(), chunk.getPos().z(), false);
				loaded.add(blocks);
				loaded.add(new ChunkKey(dimension, blocks.x(), blocks.z(), true));
				inhabited.put(blocks, chunk.getInhabitedTime());
				for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					try {
						addIfPresent(holders, describeBlockEntity(ops, dimension, blockEntity.saveWithFullMetadata(server.registryAccess()), true));
					} catch (RuntimeException e) {
						ClaudeChatClient.LOGGER.debug("Skipping block entity at {}", blockEntity.getBlockPos(), e);
					}
				}
			}
			for (Entity entity : level.getAllEntities()) {
				if (entity instanceof Player || !loaded.contains(new ChunkKey(dimension, entity.chunkPosition().x(), entity.chunkPosition().z(), true))) {
					continue;
				}
				try {
					addIfPresent(holders, describeEntity(ops, dimension, EntityType.getKey(entity.getType()).toString(), save(server, entity), true));
				} catch (RuntimeException e) {
					ClaudeChatClient.LOGGER.debug("Skipping entity {}", entity, e);
				}
			}
		}

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			CompoundTag tag = save(server, player);
			players.add(new PlayerInfo(player.getName().getString(), dimensionName(player.level().dimension()),
				player.getX(), player.getY(), player.getZ(),
				items(ops, tag.getListOrEmpty("Inventory")), items(ops, tag.getListOrEmpty("EnderItems"))));
		}
		return new Live(loaded, holders, players, inhabited);
	}

	/** Server thread: the chunks within view distance of the level's players that are loaded right now. */
	public static List<LevelChunk> loadedChunks(MinecraftServer server, ServerLevel level) {
		int radius = server.getPlayerList().getViewDistance() + 1;
		List<LevelChunk> chunks = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		for (ServerPlayer player : level.players()) {
			int px = player.chunkPosition().x();
			int pz = player.chunkPosition().z();
			for (int cx = px - radius; cx <= px + radius; cx++) {
				for (int cz = pz - radius; cz <= pz + radius; cz++) {
					if (seen.add(((long) cx << 32) ^ (cz & 0xFFFFFFFFL))) {
						addIfPresent(chunks, level.getChunkSource().getChunkNow(cx, cz));
					}
				}
			}
		}
		return chunks;
	}

	private static CompoundTag save(MinecraftServer server, Entity entity) {
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, server.registryAccess());
		entity.saveWithoutId(output);
		return output.buildResult();
	}

	// --- reading block entities, entities and items ---------------------------------------------------------------

	/** A container block (or any block entity holding items or a name); null if there's nothing worth listing. */
	private static Holder describeBlockEntity(RegistryOps<Tag> ops, String dimension, CompoundTag tag, boolean live) {
		String name = customName(ops, tag);
		String loot = tag.getStringOr("LootTable", "");
		List<Item> items = items(ops, tag.getListOrEmpty("Items"));
		for (String single : List.of("item", "Book", "RecordItem")) {
			tag.getCompound(single).flatMap(item -> item(ops, item, 0)).ifPresent(items::add);
		}
		if (items.isEmpty() && loot.isEmpty() && name == null) {
			return null;
		}
		return new Holder("block", dimension, tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0),
			shortId(tag.getStringOr("id", "?")), name, loot.isEmpty() ? null : loot, false, items, live);
	}

	/** Entities worth finding: ones holding items (minecarts, item frames, armor stands, dropped items) or named or tamed. */
	private static Holder describeEntity(RegistryOps<Tag> ops, String dimension, String typeId, CompoundTag tag, boolean live) {
		String type = shortId(typeId);
		String name = customName(ops, tag);
		boolean tamed = tag.contains("Owner");
		List<Item> items = items(ops, tag.getListOrEmpty("Items"));
		tag.getCompound("Item").flatMap(item -> item(ops, item, 0)).ifPresent(items::add);
		if (type.equals("armor_stand")) {
			tag.getCompound("equipment").ifPresent(equipment -> {
				for (String slot : equipment.keySet()) {
					equipment.getCompound(slot).flatMap(item -> item(ops, item, 0)).ifPresent(items::add);
				}
			});
		}
		String loot = tag.getStringOr("LootTable", "");
		if (items.isEmpty() && name == null && !tamed && loot.isEmpty()) {
			return null;
		}
		ListTag pos = tag.getListOrEmpty("Pos");
		int x = pos.size() == 3 ? (int) Math.floor(pos.getDoubleOr(0, 0)) : 0;
		int y = pos.size() == 3 ? (int) Math.floor(pos.getDoubleOr(1, 0)) : 0;
		int z = pos.size() == 3 ? (int) Math.floor(pos.getDoubleOr(2, 0)) : 0;
		return new Holder("entity", dimension, x, y, z, type, name, loot.isEmpty() ? null : loot, tamed, items, live);
	}

	private static List<Item> items(RegistryOps<Tag> ops, ListTag list) {
		List<Item> items = new ArrayList<>();
		for (CompoundTag tag : compounds(list)) {
			item(ops, tag, 0).ifPresent(items::add);
		}
		return items;
	}

	private static Optional<Item> item(RegistryOps<Tag> ops, CompoundTag tag, int depth) {
		Optional<ItemStack> parsed = ItemStack.CODEC.parse(ops, tag).result();
		if (parsed.isPresent()) {
			return Optional.ofNullable(item(parsed.get(), depth));
		}
		String id = tag.getStringOr("id", "");
		return id.isEmpty() ? Optional.empty() : Optional.of(new Item(id, id, tag.getIntOr("count", 1), List.of(), List.of()));
	}

	/** Name, count, enchantments, and (two levels deep) what's inside shulker boxes and bundles. */
	private static Item item(ItemStack stack, int depth) {
		if (stack.isEmpty()) {
			return null;
		}
		List<String> enchantments = new ArrayList<>();
		for (ItemEnchantments enchants : new ItemEnchantments[] {stack.get(DataComponents.ENCHANTMENTS), stack.get(DataComponents.STORED_ENCHANTMENTS)}) {
			if (enchants != null) {
				for (var entry : enchants.entrySet()) {
					enchantments.add(Enchantment.getFullname(entry.getKey(), entry.getIntValue()).getString());
				}
			}
		}
		List<Item> contents = new ArrayList<>();
		if (depth < MAX_NESTING) {
			ItemContainerContents container = stack.get(DataComponents.CONTAINER);
			if (container != null) {
				container.nonEmptyItemCopyStream().forEach(inner -> addIfPresent(contents, item(inner, depth + 1)));
			}
			BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
			if (bundle != null) {
				for (ItemStackTemplate inner : bundle.items()) {
					addIfPresent(contents, item(inner.create(), depth + 1));
				}
			}
		}
		return new Item(stack.typeHolder().getRegisteredName(), stack.getHoverName().getString(), stack.getCount(), enchantments, contents);
	}

	private static String customName(RegistryOps<Tag> ops, CompoundTag tag) {
		Tag name = tag.get("CustomName");
		if (name == null) {
			return null;
		}
		return ComponentSerialization.CODEC.parse(ops, name).result().map(Component::getString).filter(s -> !s.isBlank()).orElse(null);
	}

	private static List<CompoundTag> compounds(ListTag list) {
		return list.compoundStream().toList();
	}

	public static String dimensionName(ResourceKey<Level> dimension) {
		return dimension.identifier().getNamespace().equals("minecraft") ? dimension.identifier().getPath() : dimension.identifier().toString();
	}

	private static String shortId(String id) {
		return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
	}

	private static <T> void addIfPresent(List<T> list, T value) {
		if (value != null) {
			list.add(value);
		}
	}
}
