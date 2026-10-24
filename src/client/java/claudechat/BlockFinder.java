package claudechat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Finds the blocks nearest a point that match a block id or tag, block state properties ("charges=4") and text in the
 * block entity's data ("skeleton" in a spawner). {@link LiveChunks} are scanned on the server thread; saved chunks
 * are re-read from the region files, but only those whose palettes say they can hold the block (see
 * {@link WorldIndex.Snapshot#savedChunksContaining}), nearest first, stopping once no closer match is possible.
 */
public final class BlockFinder {
	/** Re-reading a chunk takes about a millisecond; this bounds a search for something common or absent. */
	private static final int MAX_SAVED_CHUNKS = 4000;
	private static final Duration LIVE_SCAN_TIMEOUT = Duration.ofSeconds(30);

	public record Query(Set<Block> blocks, Map<String, String> properties, String data) {
		boolean matches(BlockState state) {
			if (!blocks.contains(state.getBlock())) {
				return false;
			}
			for (Map.Entry<String, String> wanted : properties.entrySet()) {
				Property<?> property = state.getBlock().getStateDefinition().getProperty(wanted.getKey());
				if (property == null || !valueName(state, property).equals(wanted.getValue())) {
					return false;
				}
			}
			return true;
		}

		/** Data matching is plain text over the block entity's SNBT, so "skeleton" finds a skeleton spawner. */
		boolean matchesData(CompoundTag blockEntity) {
			return data.isEmpty() || (blockEntity != null && blockEntity.toString().toLowerCase(Locale.ROOT).contains(data));
		}

		Set<String> blockIds() {
			return blocks.stream().map(block -> BuiltInRegistries.BLOCK.getKey(block).toString()).collect(Collectors.toSet());
		}
	}

	public record Found(BlockPos pos, BlockState state, CompoundTag blockEntity, Provenance provenance, double distance) {
		public String id() {
			return BlockFinder.id(state);
		}

		public Map<String, String> properties() {
			return BlockFinder.properties(state);
		}
	}

	public record Result(List<Found> found, int savedChunksRead, boolean truncated) {
	}

	/** Blocks matching a query: an id, a tag, or a word ("anchor" → respawn_anchor); see {@link RegistryQuery#resolve}. */
	public static List<Block> resolve(RegistryAccess access, String query) {
		return RegistryQuery.resolve(access, Registries.BLOCK, query, RegistryQuery.Precedence.ID_FIRST).stream().map(Holder::value).toList();
	}

	public static String id(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}

	/** Every state property, e.g. {"charges": "4"}. */
	public static Map<String, String> properties(BlockState state) {
		Map<String, String> out = new LinkedHashMap<>();
		for (Property<?> property : state.getProperties()) {
			out.put(property.getName(), valueName(state, property));
		}
		return out;
	}

	/**
	 * The {@code limit} nearest matches in {@code level} within {@code maxDistance} of {@code origin}, which must be
	 * in that level. Not on the server thread: it waits for it for the live chunks and reads region files itself.
	 */
	public static Result find(WorldIndex.Snapshot snapshot, ServerLevel level, Query query, Origin origin, int maxDistance,
		int limit) throws Exception {
		MinecraftServer server = level.getServer();
		ServerTasks.requireOffServerThread(server);
		if (!origin.dimension().equals(level.dimension())) {
			throw new IllegalArgumentException("The origin is in " + origin.dimension().identifier() + ", not " + level.dimension().identifier());
		}

		NearestFirst.TopK<Found> nearest = new NearestFirst.TopK<>(limit, maxDistance);
		Set<WorldIndex.ChunkKey> live = ServerTasks.call(server, () -> scanLive(level, query, origin, nearest),
			LIVE_SCAN_TIMEOUT, "Searching the loaded chunks");

		Set<String> ids = query.blockIds();
		List<WorldIndex.ChunkKey> candidates = new ArrayList<>(snapshot.savedChunksContaining(level.dimension(), ids));
		candidates.removeIf(live::contains);
		candidates.sort(Comparator.comparingDouble(key -> chunkDistance(key, origin)));
		PalettedContainerFactory factory = level.palettedContainerFactory();
		int read = 0;
		for (WorldIndex.ChunkKey key : candidates) {
			if (!nearest.couldAccept(chunkDistance(key, origin))) {
				break;
			}
			if (read >= MAX_SAVED_CHUNKS) {
				return new Result(nearest.sorted(), read, true);
			}
			read++;

			Optional<SavedChunk> chunk;
			try {
				chunk = snapshot.readSavedChunk(key);
			} catch (Exception e) {
				ClaudeChatClient.LOGGER.debug("Skipping chunk {} in block search", key, e);
				continue;
			}
			chunk.ifPresent(saved -> scanSaved(saved, key, ids, factory, query, origin, nearest));
		}
		return new Result(nearest.sorted(), read, false);
	}

	/** Server thread: offers the matches in the live chunks, and returns which chunks those are. */
	private static Set<WorldIndex.ChunkKey> scanLive(ServerLevel level, Query query, Origin origin, NearestFirst.TopK<Found> nearest) {
		MinecraftServer server = level.getServer();
		Set<WorldIndex.ChunkKey> keys = new HashSet<>();
		for (LevelChunk chunk : LiveChunks.in(level)) {
			keys.add(new WorldIndex.ChunkKey(level.dimension(), chunk.getPos().x(), chunk.getPos().z()));
			// Not chunk.getBlockEntity(pos): that can create a missing block entity, and searches stay read-only.
			Function<BlockPos, CompoundTag> blockEntity = pos -> {
				BlockEntity found = chunk.getBlockEntities().get(pos);
				return found == null ? null : found.saveWithFullMetadata(server.registryAccess());
			};
			LevelChunkSection[] sections = chunk.getSections();
			for (int i = 0; i < sections.length; i++) {
				if (sections[i].maybeHas(query::matches)) {
					int baseY = chunk.getSectionYFromSectionIndex(i) * 16;
					scanSection(sections[i].getStates(), chunk.getPos().getMinBlockX(), baseY, chunk.getPos().getMinBlockZ(), query,
						origin, nearest, blockEntity, Provenance.LIVE);
				}
			}
		}
		return keys;
	}

	private static void scanSaved(SavedChunk chunk, WorldIndex.ChunkKey key, Set<String> ids, PalettedContainerFactory factory,
		Query query, Origin origin, NearestFirst.TopK<Found> nearest) {
		Map<BlockPos, CompoundTag> blockEntities = chunk.blockEntities();
		for (int sectionY : chunk.sectionsListing(ids)) {
			chunk.blockStates(sectionY, factory).ifPresent(states -> scanSection(states, key.x() * 16, sectionY * 16, key.z() * 16,
				query, origin, nearest, blockEntities::get, Provenance.SAVED));
		}
	}

	private static void scanSection(PalettedContainer<BlockState> states, int baseX, int baseY, int baseZ, Query query,
		Origin origin, NearestFirst.TopK<Found> nearest, Function<BlockPos, CompoundTag> blockEntity, Provenance provenance) {
		if (!states.maybeHas(query::matches)) {
			return;
		}
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					BlockState state = states.get(x, y, z);
					if (!query.matches(state)) {
						continue;
					}
					double distance = origin.distanceTo(baseX + x + 0.5, baseY + y + 0.5, baseZ + z + 0.5);
					if (!nearest.couldAccept(distance)) {
						continue;
					}
					BlockPos pos = new BlockPos(baseX + x, baseY + y, baseZ + z);
					CompoundTag data = blockEntity.apply(pos);
					if (query.matchesData(data)) {
						nearest.offer(new Found(pos, state, data, provenance, distance), distance);
					}
				}
			}
		}
	}

	/** Horizontal distance from the origin to the nearest point of a chunk: no block in it can be closer. */
	private static double chunkDistance(WorldIndex.ChunkKey key, Origin origin) {
		double dx = Math.max(0, Math.max(key.x() * 16 - origin.x(), origin.x() - (key.x() * 16 + 16)));
		double dz = Math.max(0, Math.max(key.z() * 16 - origin.z(), origin.z() - (key.z() * 16 + 16)));
		return Math.sqrt(dx * dx + dz * dz);
	}

	private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
		return property.getName(state.getValue(property));
	}
}
