package claudechat;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueOutput;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * What's stored where in the open single-player world, for the world tools: every container with its contents
 * (including items inside shulker boxes and bundles), unopened loot chests, entities that hold items or are named or
 * tamed (chest minecarts, item frames, armor stands, dropped items, pets), each player's inventory and ender chest,
 * and how long players have spent near each chunk (its InhabitedTime, used to tell visited structures apart).
 * <p>
 * Saved chunks are read straight from the region files (read-only; only chunks whose save timestamp changed are
 * re-read). {@link LiveChunks} are captured from the running game instead, since the save on disk can be up to an
 * autosave (5 minutes) behind.
 * <p>
 * InhabitedTime needs more: the game writes a chunk only when something in it changes, and its time going up doesn't
 * count, so a chunk the player only walked through keeps the time from its last save, often 0. The index records
 * each chunk's time as it unloads (and every few seconds near players) and keeps the ones newer than the save in
 * {@value VisitsFile#NAME} in the world folder, so they survive restarts. Those records count only for chunks the save
 * shows fully generated, and are dropped once the chunk is gone from the save.
 * <p>
 * Everything known about one world lives in an object created when it opens and dropped when it closes, so a scan
 * still running for one world can't write into the next.
 */
public class WorldIndex {
	private static final Duration LIVE_CAPTURE_TIMEOUT = Duration.ofSeconds(20);

	/** One chunk of one dimension. */
	public record ChunkKey(ResourceKey<Level> dimension, int x, int z) {
	}

	/** An item stack, with what's inside it if it's a shulker box or bundle. */
	public record Item(String id, String name, int count, List<String> enchantments, List<Item> contents) {
	}

	/**
	 * Something that holds items or is worth finding. {@code type} is the block or entity id without the "minecraft:"
	 * namespace, or for a player's inventory or ender chest, the player's name.
	 */
	public record Holder(Kind kind, ResourceKey<Level> dimension, int x, int y, int z, String type, String name,
		String lootTable, boolean tamed, List<Item> items, Provenance provenance) {
		public enum Kind {
			BLOCK, ENTITY, INVENTORY, ENDER_CHEST;

			/** A player's inventory or ender chest, whose {@code type} is the player's name. */
			public boolean ofPlayer() {
				return this == INVENTORY || this == ENDER_CHEST;
			}
		}
	}

	/** A player in the world right now. */
	public record PlayerInfo(UUID id, String name, ResourceKey<Level> dimension, double x, double y, double z,
		List<Item> inventory, List<Item> enderChest) {
		public Origin origin() {
			return Origin.at(dimension, x, y, z);
		}

		/** The inventory and ender chest as holders, so item searches find what the player carries. */
		public List<Holder> holders() {
			int bx = (int) Math.floor(x);
			int by = (int) Math.floor(y);
			int bz = (int) Math.floor(z);
			return List.of(
				new Holder(Holder.Kind.INVENTORY, dimension, bx, by, bz, name, null, null, false, inventory, Provenance.LIVE),
				new Holder(Holder.Kind.ENDER_CHEST, dimension, bx, by, bz, name, null, null, false, enderChest, Provenance.LIVE));
		}
	}

	/** How much a snapshot captures. */
	public enum Detail {
		/** Players, how long they've spent near each chunk, and reads of saved chunks. Cheap. */
		VISITS,
		/** Also every container and entity, which means saving every loaded block entity and entity to NBT. */
		HOLDERS
	}

	/** The world at one moment: saved data merged with the live chunks, plus reads of the saved chunks. */
	public static final class Snapshot {
		private final World world;
		private final List<Holder> holders;
		private final List<PlayerInfo> players;
		private final UUID hostId;
		private final Map<ChunkKey, Long> inhabitedTicks;

		private Snapshot(World world, List<Holder> holders, List<PlayerInfo> players, UUID hostId, Map<ChunkKey, Long> inhabitedTicks) {
			this.world = world;
			this.holders = holders;
			this.players = players;
			this.hostId = hostId;
			this.inhabitedTicks = inhabitedTicks;
		}

		public MinecraftServer server() {
			return world.server;
		}

		/** Every container and entity worth finding. Only in a {@link Detail#HOLDERS} snapshot. */
		public List<Holder> holders() {
			if (holders == null) {
				throw new IllegalStateException("This snapshot was taken without holders");
			}
			return holders;
		}

		public List<PlayerInfo> players() {
			return players;
		}

		/** The player whose game this is (on a LAN world, not a guest). Distances and directions are from them. */
		public PlayerInfo host() {
			return players.stream().filter(player -> player.id().equals(hostId)).findFirst()
				.orElseThrow(() -> new IllegalStateException("The player isn't in the world."));
		}

		/** How long players have spent within 128 blocks of a chunk; not generated if it isn't fully generated. */
		public Visit visit(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
			Long ticks = inhabitedTicks.get(new ChunkKey(dimension, chunkX, chunkZ));
			return ticks == null ? Visit.NOT_GENERATED : Visit.of(ticks);
		}

		public Visit.ByChunk visits(ResourceKey<Level> dimension) {
			return (chunkX, chunkZ) -> visit(dimension, chunkX, chunkZ);
		}

		/**
		 * Saved, fully generated chunks of a dimension whose palettes include any of these block ids. Includes live
		 * chunks; callers prefer the live copy of those.
		 */
		public List<ChunkKey> savedChunksContaining(ResourceKey<Level> dimension, Set<String> blockIds) {
			return world.savedChunksContaining(dimension, blockIds);
		}

		/**
		 * Re-reads one saved chunk, upgraded to the current version; empty if it isn't on disk. Any thread, but it's
		 * file IO: on the server thread, only for a few chunks.
		 */
		public Optional<SavedChunk> readSavedChunk(ChunkKey key) throws IOException {
			return world.readSavedChunk(key);
		}
	}

	private volatile World world;

	/** A single-player world opened. Scans it in the background so the first question is quick. */
	public void start(MinecraftServer server) {
		World opened = new World(server);
		World previous = world;
		if (previous != null) {
			previous.close();
		}
		world = opened;
		// Before anything can save it again, or the next save would drop what earlier sessions recorded.
		opened.loadVisits();
		Thread thread = new Thread(() -> {
			try {
				opened.snapshot(Detail.VISITS);
			} catch (Exception e) {
				if (!opened.closed) {
					ClaudeChatClient.LOGGER.warn("Could not index the world", e);
				}
			}
		}, "Claude Chat world index");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		thread.start();
	}

	public void stop() {
		World closing = world;
		world = null;
		if (closing != null) {
			closing.close();
		}
	}

	/** The open world's server, or null. */
	public MinecraftServer server() {
		World open = world;
		return open == null ? null : open.server;
	}

	/**
	 * Rescans chunks saved since last time and captures the live ones. Usually well under a second. Not on the server
	 * thread: it waits for it.
	 */
	public Snapshot snapshot(Detail detail) throws Exception {
		World open = world;
		if (open == null) {
			throw new IllegalStateException("No single-player world is open.");
		}
		return open.snapshot(detail);
	}

	/** Server thread. The player whose game this is, if they're in the world. */
	public static Optional<ServerPlayer> host(MinecraftServer server) {
		ServerTasks.requireServerThread(server);
		var profile = server.getSingleplayerProfile();
		return profile == null ? Optional.empty() : Optional.ofNullable(server.getPlayerList().getPlayer(profile.id()));
	}

	// --- time spent near chunks ----------------------------------------------------------------------------------

	/** Server thread: a chunk is unloading. Remember its time, which the game may not write. */
	public void chunkUnloaded(ServerLevel level, LevelChunk chunk) {
		World open = openFor(level.getServer());
		if (open != null) {
			open.record(level, chunk);
		}
	}

	/** Server thread: records the time of the live chunks, which don't unload while the world is closing. */
	public void recordLoadedChunks(MinecraftServer server) {
		World open = openFor(server);
		if (open == null) {
			return;
		}
		for (ServerLevel level : server.getAllLevels()) {
			LiveChunks.in(level).forEach(chunk -> open.record(level, chunk));
		}
	}

	/** Server thread, after a save: writes the recorded times the saved chunks lack. Small: one line per chunk. */
	public void saveVisits(MinecraftServer server) {
		World open = openFor(server);
		if (open == null) {
			return;
		}
		recordLoadedChunks(server);
		open.saveVisits();
	}

	private World openFor(MinecraftServer server) {
		ServerTasks.requireServerThread(server);
		World open = world;
		return open != null && open.server == server ? open : null;
	}

	/**
	 * InhabitedTime per chunk: the save's, raised by recorded and live times. A recorded time counts only while the
	 * save shows its chunk fully generated or the chunk is live: a chunk trimmed from the save (with a world editor,
	 * say) generates afresh, unvisited.
	 */
	static Map<ChunkKey, Long> inhabitedTimes(Map<ChunkKey, Long> saved, Map<ChunkKey, Long> recorded, Map<ChunkKey, Long> live) {
		Map<ChunkKey, Long> times = new HashMap<>(saved);
		recorded.forEach((key, ticks) -> {
			if (stillExists(key, saved, live)) {
				times.merge(key, ticks, Math::max);
			}
		});
		live.forEach((key, ticks) -> times.merge(key, ticks, Math::max));
		return times;
	}

	private static boolean stillExists(ChunkKey key, Map<ChunkKey, Long> saved, Map<ChunkKey, Long> live) {
		return saved.containsKey(key) || live.containsKey(key);
	}

	// --- one open world ---------------------------------------------------------------------------------------------

	/** What the game has right now; {@code holders} is null unless asked for. */
	private record Live(Set<ChunkKey> chunks, Map<ChunkKey, Long> inhabited, List<Holder> holders, List<PlayerInfo> players, UUID hostId) {
	}

	/** One pass over the saves: how many chunks were re-read, and whether every file could be read. */
	private record Scan(int changed, boolean complete) {
	}

	/** What's known from one region folder of every dimension: "region" (blocks) or "entities". */
	private static final class Folder {
		final String name;
		/** Last-seen save timestamp per chunk, so rescans only parse what changed. */
		final Map<ChunkKey, Integer> timestamps = new ConcurrentHashMap<>();
		final Map<ChunkKey, List<Holder>> holders = new ConcurrentHashMap<>();

		Folder(String name) {
			this.name = name;
		}
	}

	@FunctionalInterface
	private interface ChunkHandler {
		void read(ChunkKey key, CompoundTag chunk);
	}

	/** Everything known about one open world. Dropped (and closed) when the world closes. */
	private static final class World {
		final MinecraftServer server;
		final Path root;
		final Map<ResourceKey<Level>, Path> dimensionDirs = new HashMap<>();
		final Folder blockChunks = new Folder("region");
		final Folder entityChunks = new Folder("entities");
		/** InhabitedTime of saved chunks that are fully generated. */
		final Map<ChunkKey, Long> savedInhabited = new ConcurrentHashMap<>();
		/** InhabitedTime recorded from live chunks, which the save may lack (see the class comment). */
		final Map<ChunkKey, Long> recordedInhabited = new ConcurrentHashMap<>();
		/**
		 * Which blocks each fully generated saved chunk may contain (the union of its sections' palettes, as sorted ids
		 * from {@link #blockIds}), so block searches only re-read chunks that can hold a match. Ints rather than strings
		 * keep this small in big worlds.
		 */
		final Map<ChunkKey, int[]> savedBlocks = new ConcurrentHashMap<>();
		final Map<String, Integer> blockIds = new ConcurrentHashMap<>();
		/** Read failures so far; guarded by the scan's lock. */
		int failures;
		volatile boolean closed;

		World(MinecraftServer server) {
			this.server = server;
			this.root = server.getWorldPath(LevelResource.ROOT);
			for (ResourceKey<Level> dimension : server.levelKeys()) {
				dimensionDirs.put(dimension, DimensionType.getStorageFolder(dimension, root));
			}
		}

		void close() {
			closed = true;
		}

		Snapshot snapshot(Detail detail) throws Exception {
			ServerTasks.requireOffServerThread(server);
			requireOpen();
			long start = System.nanoTime();
			Scan scan = scanSaves();
			Live live = ServerTasks.call(server, () -> captureLive(detail), LIVE_CAPTURE_TIMEOUT, "Reading the loaded chunks");

			// A chunk that couldn't be read this time (mid-write, say) isn't gone; prune only after a clean scan.
			if (scan.complete()) {
				recordedInhabited.keySet().removeIf(key -> !stillExists(key, savedInhabited, live.inhabited()));
			}
			Map<ChunkKey, Long> inhabited = inhabitedTimes(savedInhabited, recordedInhabited, live.inhabited());
			List<Holder> holders = live.holders() == null ? null : mergeHolders(live);
			ClaudeChatClient.LOGGER.debug("World snapshot: {} saved chunks re-read, {} ms", scan.changed(),
				TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
			return new Snapshot(this, holders, live.players(), live.hostId(), inhabited);
		}

		/** Saved holders except in live chunks, whose live copy replaces them. */
		private List<Holder> mergeHolders(Live live) {
			List<Holder> holders = new ArrayList<>();
			for (Folder folder : List.of(blockChunks, entityChunks)) {
				folder.holders.forEach((key, chunkHolders) -> {
					if (!live.chunks().contains(key)) {
						holders.addAll(chunkHolders);
					}
				});
			}
			holders.addAll(live.holders());
			return holders;
		}

		private void requireOpen() {
			if (closed) {
				throw new IllegalStateException("The world was closed.");
			}
		}

		// --- saved chunks -----------------------------------------------------------------------------------------

		/** Re-reads every chunk saved since the last scan, in every dimension. */
		synchronized Scan scanSaves() {
			RegistryOps<Tag> ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
			int failuresBefore = failures;
			int changed = 0;
			for (ResourceKey<Level> dimension : dimensionDirs.keySet()) {
				changed += scanFolder(dimension, blockChunks, (key, tag) -> readBlockChunk(ops, key, tag), this::forgetBlockChunk);
				changed += scanFolder(dimension, entityChunks, (key, tag) -> readEntityChunk(ops, key, tag), key -> {
				});
			}
			return new Scan(changed, failures == failuresBefore);
		}

		private void readBlockChunk(RegistryOps<Tag> ops, ChunkKey key, CompoundTag tag) {
			SavedChunk chunk = new SavedChunk(upgrade(tag, DataFixTypes.CHUNK));
			List<Holder> holders = new ArrayList<>();
			for (CompoundTag blockEntity : chunk.blockEntities().values()) {
				HolderReader.blockEntity(ops, key.dimension(), blockEntity, Provenance.SAVED).ifPresent(holders::add);
			}
			blockChunks.holders.put(key, holders);
			if (chunk.fullyGenerated()) {
				savedInhabited.put(key, chunk.inhabitedTicks());
				savedBlocks.put(key, paletteIds(chunk));
			} else {
				savedInhabited.remove(key);
				savedBlocks.remove(key);
			}
		}

		private void readEntityChunk(RegistryOps<Tag> ops, ChunkKey key, CompoundTag tag) {
			List<Holder> holders = new ArrayList<>();
			for (CompoundTag entity : SavedChunk.entityTags(upgrade(tag, DataFixTypes.ENTITY_CHUNK))) {
				HolderReader.entity(ops, key.dimension(), entity.getStringOr("id", "?"), entity, Provenance.SAVED).ifPresent(holders::add);
			}
			entityChunks.holders.put(key, holders);
		}

		/** Deleted (trimmed with a world editor, say): it will generate afresh, unvisited. */
		private void forgetBlockChunk(ChunkKey key) {
			savedInhabited.remove(key);
			savedBlocks.remove(key);
		}

		/**
		 * Re-reads the chunks of one folder saved since last time, and forgets chunks no longer in it. A region that
		 * can't be read right now keeps what was known about it. Returns how many chunks were re-read.
		 */
		private int scanFolder(ResourceKey<Level> dimension, Folder folder, ChunkHandler handler, Consumer<ChunkKey> forget) {
			Path dir = dimensionDirs.get(dimension).resolve(folder.name);
			List<RegionFiles.Region> regions;
			try {
				regions = RegionFiles.list(dir);
			} catch (IOException e) {
				logFailure("Could not list " + dir, e);
				return 0;
			}

			Set<ChunkKey> present = new HashSet<>();
			int changed = 0;
			for (RegionFiles.Region region : regions) {
				try (FileChannel channel = FileChannel.open(region.file(), StandardOpenOption.READ)) {
					Optional<RegionFiles.Header> header = RegionFiles.readHeader(channel);
					if (header.isEmpty()) {
						keepKnown(folder, dimension, region, present);
						continue;
					}
					changed += scanRegion(channel, dimension, region, header.get(), folder, handler, present);
				} catch (IOException e) {
					logFailure("Could not read region " + region.file(), e);
					keepKnown(folder, dimension, region, present);
				}
			}

			for (ChunkKey key : List.copyOf(folder.timestamps.keySet())) {
				if (key.dimension().equals(dimension) && !present.contains(key)) {
					folder.timestamps.remove(key);
					folder.holders.remove(key);
					forget.accept(key);
				}
			}
			return changed;
		}

		private int scanRegion(FileChannel channel, ResourceKey<Level> dimension, RegionFiles.Region region, RegionFiles.Header header,
			Folder folder, ChunkHandler handler, Set<ChunkKey> present) throws IOException {
			int changed = 0;
			for (int i = 0; i < header.locations().length; i++) {
				int location = header.locations()[i];
				if (location == 0) {
					continue;
				}

				ChunkKey key = new ChunkKey(dimension, region.chunkX(i), region.chunkZ(i));
				present.add(key);
				int timestamp = header.timestamps()[i];
				if (Integer.valueOf(timestamp).equals(folder.timestamps.get(key))) {
					continue;
				}
				// The game may be writing this region right now; a torn read just fails and is retried next time.
				try {
					Optional<CompoundTag> chunk = RegionFiles.readChunk(channel, region, location, key.x(), key.z());
					if (chunk.isPresent()) {
						handler.read(key, chunk.get());
						folder.timestamps.put(key, timestamp);
						changed++;
					}
				} catch (IOException | RuntimeException e) {
					logFailure("Skipping chunk " + key + " for now", e);
				}
			}
			return changed;
		}

		private static void keepKnown(Folder folder, ResourceKey<Level> dimension, RegionFiles.Region region, Set<ChunkKey> present) {
			folder.timestamps.keySet().stream()
				.filter(key -> key.dimension().equals(dimension) && region.contains(key.x(), key.z()))
				.forEach(present::add);
		}

		private int[] paletteIds(SavedChunk chunk) {
			return chunk.paletteBlockIds().stream().mapToInt(name -> blockIds.computeIfAbsent(name, n -> blockIds.size())).sorted().toArray();
		}

		List<ChunkKey> savedChunksContaining(ResourceKey<Level> dimension, Set<String> ids) {
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

		Optional<SavedChunk> readSavedChunk(ChunkKey key) throws IOException {
			requireOpen();
			Path dimensionDir = dimensionDirs.get(key.dimension());
			if (dimensionDir == null) {
				throw new IllegalArgumentException("No dimension " + key.dimension().identifier() + " in this world");
			}
			return RegionFiles.readChunk(dimensionDir.resolve(blockChunks.name), key.x(), key.z())
				.map(tag -> new SavedChunk(upgrade(tag, DataFixTypes.CHUNK)));
		}

		/** Chunks saved by older versions are upgraded with the game's own data fixers; if that fails, use them as they are. */
		private CompoundTag upgrade(CompoundTag chunk, DataFixTypes type) {
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

		/** Warn about the first few failures (a torn read now and then is normal; a failure on every chunk is a bug). */
		private void logFailure(String message, Exception e) {
			if (failures++ < 3) {
				ClaudeChatClient.LOGGER.warn(message, e);
			} else {
				ClaudeChatClient.LOGGER.debug(message, e);
			}
		}

		// --- time spent near chunks ---------------------------------------------------------------------------------

		void record(ServerLevel level, LevelChunk chunk) {
			if (chunk.getInhabitedTime() > 0) {
				ChunkKey key = new ChunkKey(level.dimension(), chunk.getPos().x(), chunk.getPos().z());
				recordedInhabited.merge(key, chunk.getInhabitedTime(), Math::max);
			}
		}

		void loadVisits() {
			try {
				VisitsFile.read(root).forEach((key, ticks) -> recordedInhabited.merge(key, ticks, Math::max));
			} catch (IOException e) {
				ClaudeChatClient.LOGGER.warn("Could not read {}", root.resolve(VisitsFile.NAME), e);
			}
		}

		void saveVisits() {
			Map<ChunkKey, Long> newerThanSave = new HashMap<>();
			recordedInhabited.forEach((key, ticks) -> {
				if (ticks > savedInhabited.getOrDefault(key, -1L)) {
					newerThanSave.put(key, ticks);
				}
			});
			try {
				VisitsFile.write(root, newerThanSave);
			} catch (IOException e) {
				ClaudeChatClient.LOGGER.warn("Could not save {}", root.resolve(VisitsFile.NAME), e);
			}
		}

		// --- live state -------------------------------------------------------------------------------------------

		/** Server thread: what the live chunks hold right now, plus the players. */
		private Live captureLive(Detail detail) {
			ServerTasks.requireServerThread(server);
			RegistryOps<Tag> ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
			Set<ChunkKey> chunks = new HashSet<>();
			Map<ChunkKey, Long> inhabited = new HashMap<>();
			List<Holder> holders = detail == Detail.HOLDERS ? new ArrayList<>() : null;
			for (ServerLevel level : server.getAllLevels()) {
				ResourceKey<Level> dimension = level.dimension();
				for (LevelChunk chunk : LiveChunks.in(level)) {
					ChunkKey key = new ChunkKey(dimension, chunk.getPos().x(), chunk.getPos().z());
					chunks.add(key);
					inhabited.put(key, chunk.getInhabitedTime());
					if (holders != null) {
						addBlockEntities(ops, dimension, chunk, holders);
					}
				}
				if (holders != null) {
					addEntities(ops, level, chunks, holders);
				}
			}

			List<PlayerInfo> players = new ArrayList<>();
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				CompoundTag tag = save(player);
				players.add(new PlayerInfo(player.getUUID(), player.getName().getString(), player.level().dimension(),
					player.getX(), player.getY(), player.getZ(), HolderReader.playerInventory(ops, tag), HolderReader.playerEnderChest(ops, tag)));
			}
			UUID hostId = host(server).map(Entity::getUUID).orElse(null);
			return new Live(chunks, inhabited, holders, players, hostId);
		}

		private void addBlockEntities(RegistryOps<Tag> ops, ResourceKey<Level> dimension, LevelChunk chunk, List<Holder> holders) {
			for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
				try {
					CompoundTag tag = blockEntity.saveWithFullMetadata(server.registryAccess());
					HolderReader.blockEntity(ops, dimension, tag, Provenance.LIVE).ifPresent(holders::add);
				} catch (RuntimeException e) {
					ClaudeChatClient.LOGGER.debug("Skipping block entity at {}", blockEntity.getBlockPos(), e);
				}
			}
		}

		private void addEntities(RegistryOps<Tag> ops, ServerLevel level, Set<ChunkKey> liveChunks, List<Holder> holders) {
			for (Entity entity : level.getAllEntities()) {
				ChunkKey key = new ChunkKey(level.dimension(), entity.chunkPosition().x(), entity.chunkPosition().z());
				if (entity instanceof Player || !liveChunks.contains(key)) {
					continue;
				}
				try {
					String type = EntityType.getKey(entity.getType()).toString();
					HolderReader.entity(ops, level.dimension(), type, save(entity), Provenance.LIVE).ifPresent(holders::add);
				} catch (RuntimeException e) {
					ClaudeChatClient.LOGGER.debug("Skipping entity {}", entity, e);
				}
			}
		}

		private CompoundTag save(Entity entity) {
			TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, server.registryAccess());
			entity.saveWithoutId(output);
			return output.buildResult();
		}
	}
}
