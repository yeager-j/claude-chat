package claudechat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import java.time.Duration;
import java.util.Optional;

/**
 * What's at one position: the block, its state and data, and the biome. Read live if the chunk is one of the
 * {@link LiveChunks}, from the save if it's generated, and otherwise predicted from the seed without generating
 * anything. A prediction has the exact biome but only the base terrain (stone, water, lava or air, before surface
 * blocks, carvers, trees, ores and structures), and says so.
 */
public final class LocationInspector {
	private static final Duration SERVER_TIMEOUT = Duration.ofSeconds(20);

	private LocationInspector() {
	}

	/**
	 * The facts about a position, from whichever source has them. {@code surfaceY} is the column's top non-air block;
	 * null in a dimension with a ceiling, where that's just the roof, or if the column is empty.
	 */
	public record Inspection(Provenance source, int y, BlockState state, CompoundTag blockEntity, String biome, Integer surfaceY) {
	}

	/**
	 * {@code y} null means the top block of the column. Not on the server thread: it waits for it and may read a
	 * region file.
	 */
	public static Inspection inspect(WorldIndex.Snapshot snapshot, ServerLevel level, int x, Integer y, int z) throws Exception {
		boolean hasCeiling = level.dimensionType().hasCeiling();
		if (y == null && hasCeiling) {
			throw new IllegalArgumentException("Give a y: this dimension has a ceiling, so the top block is just the roof.");
		}
		if (y != null && (y < level.getMinY() || y > level.getMaxY())) {
			throw new IllegalArgumentException("y must be between " + level.getMinY() + " and " + level.getMaxY() + " here.");
		}

		Inspection inspection = read(snapshot, level, x, y, z);
		return hasCeiling ? withoutSurface(inspection) : inspection;
	}

	private static Inspection read(WorldIndex.Snapshot snapshot, ServerLevel level, int x, Integer y, int z) throws Exception {
		int chunkX = SectionPos.blockToSectionCoord(x);
		int chunkZ = SectionPos.blockToSectionCoord(z);
		Optional<Inspection> live = ServerTasks.call(level.getServer(),
			() -> LiveChunks.at(level, chunkX, chunkZ).map(chunk -> fromLive(level, chunk, x, y, z)), SERVER_TIMEOUT, "Reading the block");
		if (live.isPresent()) {
			return live.get();
		}

		if (snapshot.visit(level.dimension(), chunkX, chunkZ).generated()) {
			Optional<SavedChunk> saved = snapshot.readSavedChunk(new WorldIndex.ChunkKey(level.dimension(), chunkX, chunkZ));
			if (saved.isPresent()) {
				return fromSave(level, saved.get(), x, y, z);
			}
		}
		return ServerTasks.call(level.getServer(), () -> predict(level, x, y, z), SERVER_TIMEOUT, "Predicting the block");
	}

	private static Inspection withoutSurface(Inspection inspection) {
		return new Inspection(inspection.source(), inspection.y(), inspection.state(), inspection.blockEntity(), inspection.biome(), null);
	}

	/** Server thread. */
	private static Inspection fromLive(ServerLevel level, LevelChunk chunk, int x, Integer y, int z) {
		int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
		int at = y != null ? y : surface;
		BlockPos pos = new BlockPos(x, at, z);
		// Not getBlockEntity(pos): that can create a missing block entity, and inspecting stays read-only.
		BlockEntity blockEntity = chunk.getBlockEntities().get(pos);
		Holder<Biome> biome = chunk.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(at), QuartPos.fromBlock(z));
		return new Inspection(Provenance.LIVE, at, chunk.getBlockState(pos),
			blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess()), RegistryQuery.id(biome), surface);
	}

	/** A generated chunk from its last save: decode just the sections needed. */
	private static Inspection fromSave(ServerLevel level, SavedChunk chunk, int x, Integer y, int z) {
		PalettedContainerFactory factory = level.palettedContainerFactory();
		Integer surface = surface(level, chunk, factory, x & 15, z & 15);
		int at = y != null ? y : (surface != null ? surface : level.getMinY());
		BlockState state = chunk.blockStates(SectionPos.blockToSectionCoord(at), factory)
			.map(states -> states.get(x & 15, at & 15, z & 15)).orElse(Blocks.AIR.defaultBlockState());
		String biome = RegistryQuery.id(chunk.biome(x, at, z, factory).orElseGet(() -> seedBiome(level, x, at, z)));
		CompoundTag blockEntity = chunk.blockEntities().get(new BlockPos(x, at, z));
		return new Inspection(Provenance.SAVED, at, state, blockEntity, biome, surface);
	}

	/** The highest non-air block of a column in a saved chunk, or null if it's all air. */
	private static Integer surface(ServerLevel level, SavedChunk chunk, PalettedContainerFactory factory, int localX, int localZ) {
		for (int sectionY = level.getMaxSectionY(); sectionY >= level.getMinSectionY(); sectionY--) {
			Optional<PalettedContainer<BlockState>> states = chunk.blockStates(sectionY, factory);
			if (states.isEmpty() || !states.get().maybeHas(state -> !state.isAir())) {
				continue;
			}
			for (int localY = 15; localY >= 0; localY--) {
				if (!states.get().get(localX, localY, localZ).isAir()) {
					return sectionY * 16 + localY;
				}
			}
		}
		return null;
	}

	/** Server thread. Never-generated chunk: the seed gives the exact biome and the base terrain only. */
	private static Inspection predict(ServerLevel level, int x, Integer y, int z) {
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		RandomState randomState = level.getChunkSource().randomState();
		int surface = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState) - 1;
		int at = y != null ? y : surface;
		BlockState state = generator.getBaseColumn(x, z, level, randomState).getBlock(at);
		return new Inspection(Provenance.PREDICTED, at, state, null, RegistryQuery.id(seedBiome(level, x, at, z)), surface);
	}

	private static Holder<Biome> seedBiome(ServerLevel level, int x, int y, int z) {
		RandomState randomState = level.getChunkSource().randomState();
		return level.getChunkSource().getGenerator().getBiomeSource().createUncachedResolver(randomState)
			.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z));
	}
}
