package claudechat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A chunk as saved in its region file (in the current version's layout, after data fixing): generation status,
 * InhabitedTime, the blocks and biomes of each 16-block section, block entities, and stored structure starts. Only this
 * class reads chunk NBT keys.
 */
public final class SavedChunk {
	private final CompoundTag tag;
	private final Map<Integer, CompoundTag> sectionsByY = new HashMap<>();

	SavedChunk(CompoundTag tag) {
		this.tag = tag;
		tag.getListOrEmpty("sections").compoundStream().forEach(section -> sectionsByY.put((int) section.getByteOr("Y", (byte) 0), section));
	}

	/**
	 * Whether the chunk finished generating. Structure searches (ours and /locate's) can leave partly generated
	 * "structure starts" chunks on disk that nobody has ever been near.
	 */
	public boolean fullyGenerated() {
		String status = tag.getStringOr("Status", "");
		return status.equals("minecraft:full") || status.equals("full");
	}

	/** How long players have spent within 128 blocks, in ticks, as of this save. */
	public long inhabitedTicks() {
		return tag.getLongOr("InhabitedTime", 0L);
	}

	/** Every block id in the section palettes. Palettes can list blocks that are no longer there, never the reverse. */
	public Set<String> paletteBlockIds() {
		Set<String> ids = new HashSet<>();
		for (CompoundTag section : sectionsByY.values()) {
			palette(section).forEach(entry -> ids.add(paletteName(entry)));
		}
		return ids;
	}

	/** The y index of each section whose palette lists any of these block ids. */
	public List<Integer> sectionsListing(Set<String> blockIds) {
		List<Integer> ys = new ArrayList<>();
		sectionsByY.forEach((y, section) -> {
			if (palette(section).stream().anyMatch(entry -> blockIds.contains(paletteName(entry)))) {
				ys.add(y);
			}
		});
		return ys;
	}

	/** One section's blocks, decoded; empty if the section isn't saved (all air). */
	public Optional<PalettedContainer<BlockState>> blockStates(int sectionY, PalettedContainerFactory factory) {
		return Optional.ofNullable(sectionsByY.get(sectionY)).flatMap(section -> section.getCompound("block_states"))
			.flatMap(states -> factory.blockStatesContainerCodec().parse(NbtOps.INSTANCE, states).result());
	}

	/** The saved biome at a block position; empty if its section isn't saved. */
	public Optional<Holder<Biome>> biome(int x, int y, int z, PalettedContainerFactory factory) {
		return Optional.ofNullable(sectionsByY.get(SectionPos.blockToSectionCoord(y))).flatMap(section -> section.getCompound("biomes"))
			.flatMap(biomes -> factory.biomeContainerCodec().parse(NbtOps.INSTANCE, biomes).result())
			.map(biomes -> biomes.get(QuartPos.fromBlock(x) & 3, QuartPos.fromBlock(y) & 3, QuartPos.fromBlock(z) & 3));
	}

	/** Block entity data by position. */
	public Map<BlockPos, CompoundTag> blockEntities() {
		Map<BlockPos, CompoundTag> byPos = new HashMap<>();
		tag.getListOrEmpty("block_entities").compoundStream().forEach(blockEntity -> byPos.put(blockEntityPos(blockEntity), blockEntity));
		return byPos;
	}

	/** The saved start of a structure that starts in this chunk. */
	public Optional<CompoundTag> structureStart(Identifier structure) {
		return tag.getCompound("structures").flatMap(structures -> structures.getCompound("starts"))
			.flatMap(starts -> starts.getCompound(structure.toString()));
	}

	/** The entities of an entity chunk's NBT (entities are saved in their own region folder). */
	static List<CompoundTag> entityTags(CompoundTag entityChunk) {
		return entityChunk.getListOrEmpty("Entities").compoundStream().toList();
	}

	static BlockPos blockEntityPos(CompoundTag blockEntity) {
		return new BlockPos(blockEntity.getIntOr("x", 0), blockEntity.getIntOr("y", 0), blockEntity.getIntOr("z", 0));
	}

	private static List<Tag> palette(CompoundTag section) {
		return section.getCompound("block_states").map(states -> List.copyOf(states.getListOrEmpty("palette"))).orElse(List.of());
	}

	/**
	 * The block id of a section palette entry. In 26.x a block in its default state is saved as just its id
	 * ("minecraft:spawner"); other states are {id, properties} compounds (before 26.x, {Name, Properties}).
	 */
	private static String paletteName(Tag entry) {
		if (entry instanceof CompoundTag compound) {
			return compound.getString("id").or(() -> compound.getString("Name")).orElse("");
		}
		return entry.asString().orElse("");
	}
}
