package claudechat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Finds the blocks nearest the player that match a block id or tag, block state properties ("charges=4") and text in
 * the block entity's data ("skeleton" in a spawner). Loaded chunks are scanned live on the server thread; saved chunks
 * are re-read from the region files, but only those whose palettes say they can hold the block (see
 * {@link WorldIndex#savedChunksContaining}), nearest first, stopping once no closer match is possible.
 */
public final class BlockFinder {
	/** Re-reading a chunk takes about a millisecond; this bounds a search for something common or absent. */
	private static final int MAX_SAVED_CHUNKS = 4000;

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
	}

	public record Found(BlockPos pos, BlockState state, CompoundTag blockEntity, boolean live, double distance) {
		public String id() {
			return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		}

		/** Every state property, e.g. {"charges": "4"}. */
		public Map<String, String> properties() {
			Map<String, String> out = new LinkedHashMap<>();
			for (Property<?> property : state.getProperties()) {
				out.put(property.getName(), valueName(state, property));
			}
			return out;
		}
	}

	public record Result(List<Found> found, int savedChunksRead, boolean truncated) {
	}

	/**
	 * Blocks matching a query: an id ("minecraft:spawner"), a tag ("#minecraft:beds"), or a word tried as a tag (also
	 * plural), then an id, then a substring of ids ("anchor" → respawn_anchor).
	 */
	public static List<Block> resolve(RegistryAccess access, String query) {
		HolderLookup.RegistryLookup<Block> registry = access.lookupOrThrow(Registries.BLOCK);
		String q = query.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
		if (q.startsWith("#")) {
			Identifier id = Identifier.tryParse(q.substring(1));
			return id == null ? List.of() : registry.get(TagKey.create(Registries.BLOCK, id))
				.map(set -> set.stream().map(Holder::value).toList()).orElse(List.of());
		}
		Identifier asId = q.contains(":") ? Identifier.tryParse(q) : Identifier.withDefaultNamespace(q);
		if (asId == null) {
			return List.of();
		}
		var exact = registry.get(ResourceKey.create(Registries.BLOCK, asId));
		if (exact.isPresent()) {
			return List.of(exact.get().value());
		}
		for (Identifier tagId : List.of(asId, asId.withSuffix("s"))) {
			var tag = registry.get(TagKey.create(Registries.BLOCK, tagId));
			if (tag.isPresent() && tag.get().size() > 0) {
				return tag.get().stream().map(Holder::value).toList();
			}
		}
		if (q.contains(":")) {
			return List.of();
		}
		return registry.listElements().filter(h -> h.key().identifier().getPath().contains(q)).map(Holder::value).toList();
	}

	/**
	 * The {@code limit} nearest matches in {@code level} within {@code maxDistance} of {@code origin}. Call from a
	 * background thread: it blocks on the server thread for the loaded chunks and reads region files itself.
	 */
	public static Result find(WorldIndex index, ServerLevel level, Query query, double ox, double oy, double oz, int maxDistance,
		int limit) throws Exception {
		MinecraftServer server = level.getServer();
		String dimension = WorldIndex.dimensionName(level.dimension());
		Nearest nearest = new Nearest(limit, maxDistance);

		Set<WorldIndex.ChunkKey> loaded = server.submit(() -> {
			Set<WorldIndex.ChunkKey> keys = new HashSet<>();
			for (LevelChunk chunk : WorldIndex.loadedChunks(server, level)) {
				keys.add(new WorldIndex.ChunkKey(dimension, chunk.getPos().x(), chunk.getPos().z(), false));
				LevelChunkSection[] sections = chunk.getSections();
				for (int i = 0; i < sections.length; i++) {
					if (!sections[i].maybeHas(query::matches)) {
						continue;
					}
					int baseY = chunk.getSectionYFromSectionIndex(i) * 16;
					scanSection(sections[i].getStates(), chunk.getPos().getMinBlockX(), baseY, chunk.getPos().getMinBlockZ(), query,
						ox, oy, oz, nearest, pos -> {
							// Not chunk.getBlockEntity(pos): that can create a missing block entity, and searches stay read-only.
							BlockEntity blockEntity = chunk.getBlockEntities().get(pos);
							return blockEntity == null ? null : blockEntity.saveWithFullMetadata(server.registryAccess());
						}, true);
				}
			}
			return keys;
		}).get(30, TimeUnit.SECONDS);

		Set<String> ids = query.blocks().stream().map(b -> BuiltInRegistries.BLOCK.getKey(b).toString()).collect(Collectors.toSet());
		List<WorldIndex.ChunkKey> candidates = new ArrayList<>(index.savedChunksContaining(dimension, ids));
		candidates.removeIf(loaded::contains);
		candidates.sort(Comparator.comparingDouble(key -> chunkDistance(key, ox, oz)));
		var codec = level.palettedContainerFactory().blockStatesContainerCodec();
		int read = 0;
		boolean truncated = false;
		for (WorldIndex.ChunkKey key : candidates) {
			if (!nearest.couldAccept(chunkDistance(key, ox, oz))) {
				break;
			}
			if (read >= MAX_SAVED_CHUNKS) {
				truncated = true;
				break;
			}
			read++;
			CompoundTag chunk;
			try {
				chunk = index.readSavedChunk(key);
			} catch (Exception e) {
				ClaudeChatClient.LOGGER.debug("Skipping chunk {} in block search", key, e);
				continue;
			}
			if (chunk == null) {
				continue;
			}
			Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();
			chunk.getListOrEmpty("block_entities").compoundStream().forEach(tag -> blockEntities.put(
				new BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0)), tag));
			chunk.getListOrEmpty("sections").compoundStream().forEach(section -> section.getCompound("block_states")
				.filter(states -> states.getListOrEmpty("palette").stream().anyMatch(entry -> ids.contains(WorldIndex.paletteName(entry))))
				.flatMap(states -> codec.parse(NbtOps.INSTANCE, states).result())
				.ifPresent(states -> scanSection(states, key.x() * 16, section.getByteOr("Y", (byte) 0) * 16, key.z() * 16, query,
					ox, oy, oz, nearest, blockEntities::get, false)));
		}
		return new Result(nearest.sorted(), read, truncated);
	}

	private static void scanSection(PalettedContainer<BlockState> states, int baseX, int baseY, int baseZ, Query query,
		double ox, double oy, double oz, Nearest nearest, Function<BlockPos, CompoundTag> blockEntity, boolean live) {
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
					double distance = Math.sqrt(Math.pow(baseX + x + 0.5 - ox, 2) + Math.pow(baseY + y + 0.5 - oy, 2)
						+ Math.pow(baseZ + z + 0.5 - oz, 2));
					if (!nearest.couldAccept(distance)) {
						continue;
					}
					BlockPos pos = new BlockPos(baseX + x, baseY + y, baseZ + z);
					CompoundTag data = blockEntity.apply(pos);
					if (query.matchesData(data)) {
						nearest.offer(new Found(pos, state, data, live, distance));
					}
				}
			}
		}
	}

	/** Horizontal distance from the origin to the nearest point of a chunk: no block in it can be closer. */
	private static double chunkDistance(WorldIndex.ChunkKey key, double ox, double oz) {
		double dx = Math.max(0, Math.max(key.x() * 16 - ox, ox - (key.x() * 16 + 16)));
		double dz = Math.max(0, Math.max(key.z() * 16 - oz, oz - (key.z() * 16 + 16)));
		return Math.sqrt(dx * dx + dz * dz);
	}

	/** The closest {@code limit} matches so far, so searching for something common ("stone") stays cheap. */
	private static final class Nearest {
		private final PriorityQueue<Found> queue = new PriorityQueue<>(Comparator.comparingDouble(Found::distance).reversed());
		private final int limit;
		private final double maxDistance;

		Nearest(int limit, double maxDistance) {
			this.limit = limit;
			this.maxDistance = maxDistance;
		}

		boolean couldAccept(double distance) {
			return distance <= maxDistance && (queue.size() < limit || distance < queue.peek().distance());
		}

		void offer(Found found) {
			queue.add(found);
			if (queue.size() > limit) {
				queue.poll();
			}
		}

		List<Found> sorted() {
			List<Found> list = new ArrayList<>(queue);
			list.sort(Comparator.comparingDouble(Found::distance));
			return list;
		}
	}

	private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
		return property.getName(state.getValue(property));
	}
}
