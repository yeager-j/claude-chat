package claudechat;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Which chunks the world tools read live from the game rather than from the save: chunks loaded at full status that
 * are within view distance of a player in their dimension or are ticking (which includes force-loaded ones). Every
 * other chunk comes from the region files, which can be an autosave (5 minutes) behind. The one place this is decided.
 */
final class LiveChunks {
	private LiveChunks() {
	}

	/** Server thread. Every live chunk of a dimension. */
	static Collection<LevelChunk> in(ServerLevel level) {
		return byPosition(level).values();
	}

	/** Server thread. The chunk if it's live. */
	static Optional<LevelChunk> at(ServerLevel level, int chunkX, int chunkZ) {
		return Optional.ofNullable(byPosition(level).get(ChunkPos.pack(chunkX, chunkZ)));
	}

	private static Map<Long, LevelChunk> byPosition(ServerLevel level) {
		ServerTasks.requireServerThread(level.getServer());
		Map<Long, LevelChunk> chunks = new LinkedHashMap<>();
		level.getChunkSource().chunkMap.forEachBlockTickingChunk(chunk -> chunks.put(chunk.getPos().pack(), chunk));

		int radius = level.getServer().getPlayerList().getViewDistance() + 1;
		for (ServerPlayer player : level.players()) {
			int px = player.chunkPosition().x();
			int pz = player.chunkPosition().z();
			for (int cx = px - radius; cx <= px + radius; cx++) {
				for (int cz = pz - radius; cz <= pz + radius; cz++) {
					long key = ChunkPos.pack(cx, cz);
					if (!chunks.containsKey(key)) {
						LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
						if (chunk != null) {
							chunks.put(key, chunk);
						}
					}
				}
			}
		}
		return chunks;
	}
}
