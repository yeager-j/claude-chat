package claudechat;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * What's at one position: the block, its state and data, and the biome. Read live if the chunk is loaded, from the
 * save if it's generated, and otherwise predicted from the seed without generating anything. A prediction has the
 * exact biome but only the base terrain (stone, water, lava or air, before surface blocks, carvers, trees, ores and
 * structures), and says so.
 */
public final class LocationInspector {
	private LocationInspector() {
	}

	/** The facts about a position, from whichever source has them. {@code surfaceY} is the column's top non-air block. */
	public record Inspection(String source, int y, BlockState state, CompoundTag blockEntity, String biome, Integer surfaceY) {
	}

	/**
	 * {@code y} null means the top block of the column. Call from a background thread: it blocks on the server thread
	 * and may read a region file.
	 */
	public static Inspection inspect(WorldIndex index, WorldIndex.Snapshot snapshot, ServerLevel level, int x, Integer y, int z)
		throws Exception {
		if (y == null && level.dimensionType().hasCeiling()) {
			throw new IllegalArgumentException("Give a y: this dimension has a ceiling, so the top block is just the roof.");
		}
		if (y != null && (y < level.getMinY() || y > level.getMaxY())) {
			throw new IllegalArgumentException("y must be between " + level.getMinY() + " and " + level.getMaxY() + " here.");
		}
		Inspection live = level.getServer().submit(() -> {
			LevelChunk chunk = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
			if (chunk == null) {
				return null;
			}
			int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
			int at = y != null ? y : surface;
			BlockPos pos = new BlockPos(x, at, z);
			// Not getBlockEntity(pos): that can create a missing block entity, and inspecting stays read-only.
			BlockEntity blockEntity = chunk.getBlockEntities().get(pos);
			Holder<Biome> biome = chunk.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(at), QuartPos.fromBlock(z));
			return new Inspection("live", at, chunk.getBlockState(pos),
				blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess()), RegistryQuery.id(biome), surface);
		}).get(20, TimeUnit.SECONDS);
		if (live != null) {
			return live;
		}

		String dimension = WorldIndex.dimensionName(level.dimension());
		WorldIndex.ChunkKey key = new WorldIndex.ChunkKey(dimension, SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z), false);
		if (snapshot.inhabited(dimension, key.x(), key.z()) >= 0) {
			CompoundTag chunk = index.readSavedChunk(key);
			if (chunk != null) {
				return fromSave(level, chunk, x, y, z);
			}
		}
		return level.getServer().submit(() -> predict(level, x, y, z)).get(20, TimeUnit.SECONDS);
	}

	/** A generated chunk from its last save: decode just the sections needed. */
	private static Inspection fromSave(ServerLevel level, CompoundTag chunk, int x, Integer y, int z) {
		var factory = level.palettedContainerFactory();
		Map<Integer, CompoundTag> sections = new HashMap<>();
		chunk.getListOrEmpty("sections").compoundStream().forEach(s -> sections.put((int) s.getByteOr("Y", (byte) 0), s));
		int lx = x & 15;
		int lz = z & 15;

		Integer surface = null;
		for (int sy = level.getMaxSectionY(); sy >= level.getMinSectionY() && surface == null; sy--) {
			PalettedContainer<BlockState> states = blockStates(factory, sections.get(sy));
			if (states == null || !states.maybeHas(state -> !state.isAir())) {
				continue;
			}
			for (int ly = 15; ly >= 0; ly--) {
				if (!states.get(lx, ly, lz).isAir()) {
					surface = sy * 16 + ly;
					break;
				}
			}
		}
		int at = y != null ? y : (surface != null ? surface : level.getMinY());
		CompoundTag section = sections.get(SectionPos.blockToSectionCoord(at));
		PalettedContainer<BlockState> states = blockStates(factory, section);
		BlockState state = states == null ? Blocks.AIR.defaultBlockState() : states.get(lx, at & 15, lz);
		String biome = Optional.ofNullable(section).flatMap(s -> s.getCompound("biomes"))
			.flatMap(b -> factory.biomeContainerCodec().parse(NbtOps.INSTANCE, b).result())
			.map((PalettedContainerRO<Holder<Biome>> b) -> RegistryQuery.id(b.get((x >> 2) & 3, (at >> 2) & 3, (z >> 2) & 3)))
			.orElseGet(() -> RegistryQuery.id(seedBiome(level, x, at, z)));
		CompoundTag blockEntity = chunk.getListOrEmpty("block_entities").compoundStream()
			.filter(t -> t.getIntOr("x", 0) == x && t.getIntOr("y", 0) == at && t.getIntOr("z", 0) == z)
			.findFirst().orElse(null);
		return new Inspection("last save", at, state, blockEntity, biome, surface);
	}

	private static PalettedContainer<BlockState> blockStates(PalettedContainerFactory factory, CompoundTag section) {
		return section == null ? null : section.getCompound("block_states")
			.flatMap(states -> factory.blockStatesContainerCodec().parse(NbtOps.INSTANCE, states).result()).orElse(null);
	}

	/** Server thread. Never-generated chunk: the seed gives the exact biome and the base terrain only. */
	private static Inspection predict(ServerLevel level, int x, Integer y, int z) {
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		RandomState randomState = level.getChunkSource().randomState();
		int surface = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState) - 1;
		int at = y != null ? y : surface;
		BlockState state = generator.getBaseColumn(x, z, level, randomState).getBlock(at);
		return new Inspection("predicted from the seed (chunk not generated)", at, state, null,
			RegistryQuery.id(seedBiome(level, x, at, z)), surface);
	}

	private static Holder<Biome> seedBiome(ServerLevel level, int x, int y, int z) {
		RandomState randomState = level.getChunkSource().randomState();
		return level.getChunkSource().getGenerator().getBiomeSource().createUncachedResolver(randomState)
			.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z));
	}
}
