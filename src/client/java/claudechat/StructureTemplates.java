package claudechat;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Registry;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pools.ListPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasBinding;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;

/**
 * Structure templates: the .nbt files that jigsaw structures (villages, ancient cities, most modded structures) and
 * code-placed ones (igloos, shipwrecks) are built from. They are gzipped NBT, so Claude can't read them as files.
 * Templates are read the way the game reads them: through the server's resource manager (world data packs override
 * mods, which override Minecraft), plus ones saved in the world with a structure block, and data-fixed to the
 * current version so old files use today's block names.
 * <p>
 * For searching by block, every template is summarized once per resource reload. Which structures can place a
 * template is traced through template pools: from each jigsaw structure's start pool, to the templates in it, to the
 * pools their jigsaw blocks point at, and so on.
 */
public final class StructureTemplates {
	private static final FileToIdConverter RESOURCE_LISTER = new FileToIdConverter("structure", ".nbt");

	public record BlockInfo(BlockPos pos, BlockState state, CompoundTag nbt) {
	}

	public record EntityInfo(BlockPos pos, String type) {
	}

	/** A loaded template; only the first palette (structures with several pick one at random). */
	public record Template(Identifier id, String pack, Vec3i size, int palettes, List<BlockInfo> blocks, List<EntityInfo> entities) {
	}

	/** What the search keeps of each template. Counts leave out air and jigsaw blocks (connectors that turn into air). */
	public record Summary(Identifier id, String pack, Vec3i size, Map<Block, Integer> blockCounts, int solidBlocks,
		Set<Identifier> jigsawPools, Map<String, Integer> entityCounts) {
	}

	/** All templates, plus which pools list each one and which structures can reach it. */
	public record Index(List<Summary> templates, Map<Identifier, Set<Identifier>> pools, Map<Identifier, Set<Identifier>> structures,
		int failed) {
		public Summary get(Identifier id) {
			return templates.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
		}
	}

	/**
	 * Data fixing thousands of old files takes a while (about 20 s of all cores for 7000 templates), so it runs on
	 * low-priority threads, half the cores at most, and the game keeps running smoothly meanwhile.
	 */
	private static final ForkJoinPool POOL = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors() / 2), pool -> {
		ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
		thread.setName("Claude Chat templates " + thread.getPoolIndex());
		thread.setPriority(Thread.MIN_PRIORITY);
		thread.setDaemon(true);
		return thread;
	}, null, false);

	private ResourceManager indexedFor;
	private CompletableFuture<Index> index;

	/** Starts indexing in the background (world opened, or /reload), so the first search doesn't wait for it. */
	public void prepare(MinecraftServer server) {
		future(server);
	}

	public synchronized void clear() {
		index = null;
		indexedFor = null;
	}

	/** The index for the server's current resources, waiting for it if it's still being built. */
	public Index index(MinecraftServer server) throws Exception {
		return future(server).get(5, TimeUnit.MINUTES);
	}

	private synchronized CompletableFuture<Index> future(MinecraftServer server) {
		ResourceManager resources = server.getResourceManager();
		if (index == null || indexedFor != resources || index.isCompletedExceptionally()) {
			indexedFor = resources;
			index = CompletableFuture.supplyAsync(() -> {
				long start = System.nanoTime();
				Index built = build(server, resources);
				ClaudeChatClient.LOGGER.info("Indexed {} structure templates in {} ms ({} unreadable)", built.templates().size(),
					(System.nanoTime() - start) / 1_000_000, built.failed());
				return built;
			}, POOL);
		}
		return index;
	}

	private static Index build(MinecraftServer server, ResourceManager resources) {
		HolderGetter<Block> blocks = server.registryAccess().lookupOrThrow(Registries.BLOCK);
		List<Identifier> ids = server.getStructureTemplateManager().listTemplates().distinct().toList();
		Map<Identifier, Summary> summaries = new ConcurrentHashMap<>();
		// Independent per file; this runs inside POOL, so the parallel stream uses its threads.
		ids.parallelStream().forEach(id -> {
			try {
				load(server, resources, blocks, id).ifPresent(t -> summaries.put(id, summarize(t)));
			} catch (Exception e) {
				ClaudeChatClient.LOGGER.debug("Could not read structure template {}", id, e);
			}
		});
		List<Summary> sorted = summaries.values().stream().sorted((a, b) -> a.id().toString().compareTo(b.id().toString())).toList();
		Map<Identifier, Set<Identifier>> pools = new HashMap<>();
		Map<Identifier, Set<Identifier>> structures = new HashMap<>();
		trace(server, summaries, pools, structures);
		return new Index(sorted, pools, structures, ids.size() - summaries.size());
	}

	/** A template by id, read fresh (not cached), or empty if no pack has it. */
	public static Optional<Template> load(MinecraftServer server, Identifier id) throws IOException {
		return load(server, server.getResourceManager(), server.registryAccess().lookupOrThrow(Registries.BLOCK), id);
	}

	private static Optional<Template> load(MinecraftServer server, ResourceManager resources, HolderGetter<Block> blocks, Identifier id)
		throws IOException {
		// The game checks the world's own saved templates first.
		Path generated = server.getWorldPath(LevelResource.GENERATED_DIR).normalize();
		Path file = generated.resolve(id.getNamespace()).resolve("structure").resolve(id.getPath() + ".nbt").normalize();
		if (file.startsWith(generated) && Files.isRegularFile(file)) {
			try (InputStream in = Files.newInputStream(file)) {
				return Optional.of(parse(server, blocks, id, "this world (saved with a structure block)", in));
			}
		}
		Optional<Resource> resource = resources.getResource(RESOURCE_LISTER.idToFile(id));
		if (resource.isEmpty()) {
			return Optional.empty();
		}
		try (InputStream in = resource.get().open()) {
			return Optional.of(parse(server, blocks, id, resource.get().sourcePackId(), in));
		}
	}

	private static Template parse(MinecraftServer server, HolderGetter<Block> blocks, Identifier id, String pack, InputStream in) throws IOException {
		CompoundTag raw = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
		CompoundTag tag = DataFixTypes.STRUCTURE.updateToCurrentVersion(server.getFixerUpper(), raw, NbtUtils.getDataVersion(raw, 500));
		ListTag sizeTag = tag.getListOrEmpty("size");
		Vec3i size = new Vec3i(sizeTag.getIntOr(0, 0), sizeTag.getIntOr(1, 0), sizeTag.getIntOr(2, 0));
		Optional<ListTag> paletteList = tag.getList("palettes");
		ListTag palette = paletteList.map(list -> list.getListOrEmpty(0)).orElseGet(() -> tag.getListOrEmpty("palette"));
		List<BlockState> states = new ArrayList<>();
		for (int i = 0; i < palette.size(); i++) {
			states.add(NbtUtils.readBlockState(blocks, palette.getCompoundOrEmpty(i)));
		}
		List<BlockInfo> blockInfos = new ArrayList<>();
		tag.getListOrEmpty("blocks").compoundStream().forEach(b -> {
			ListTag pos = b.getListOrEmpty("pos");
			int state = b.getIntOr("state", 0);
			blockInfos.add(new BlockInfo(new BlockPos(pos.getIntOr(0, 0), pos.getIntOr(1, 0), pos.getIntOr(2, 0)),
				state >= 0 && state < states.size() ? states.get(state) : Blocks.AIR.defaultBlockState(), b.getCompound("nbt").orElse(null)));
		});
		List<EntityInfo> entities = new ArrayList<>();
		tag.getListOrEmpty("entities").compoundStream().forEach(e -> {
			ListTag pos = e.getListOrEmpty("blockPos");
			entities.add(new EntityInfo(new BlockPos(pos.getIntOr(0, 0), pos.getIntOr(1, 0), pos.getIntOr(2, 0)),
				e.getCompoundOrEmpty("nbt").getStringOr("id", "?")));
		});
		return new Template(id, pack, size, paletteList.map(ListTag::size).orElse(1), blockInfos, entities);
	}

	private static Summary summarize(Template t) {
		Map<Block, Integer> counts = new HashMap<>();
		Set<Identifier> jigsawPools = new HashSet<>();
		int solid = 0;
		for (BlockInfo b : t.blocks()) {
			if (b.state().is(Blocks.JIGSAW)) {
				String poolName = b.nbt() == null ? "" : b.nbt().getStringOr("pool", "");
				Identifier pool = poolName.isEmpty() ? null : Identifier.tryParse(poolName);
				if (pool != null) {
					jigsawPools.add(pool);
				}
				continue;
			}
			if (b.state().isAir()) {
				continue;
			}
			solid++;
			counts.merge(b.state().getBlock(), 1, Integer::sum);
		}
		Map<String, Integer> entities = new HashMap<>();
		t.entities().forEach(e -> entities.merge(e.type(), 1, Integer::sum));
		return new Summary(t.id(), t.pack(), t.size(), counts, solid, jigsawPools, entities);
	}

	/**
	 * Fills template → pools that list it, and template → structures that can place it. Structures placed by code
	 * (igloos, shipwrecks, many mods' own structures) name their templates in Java, so they don't show up here.
	 */
	private static void trace(MinecraftServer server, Map<Identifier, Summary> templates, Map<Identifier, Set<Identifier>> poolsOf,
		Map<Identifier, Set<Identifier>> structuresOf) {
		Registry<StructureTemplatePool> poolRegistry = server.registryAccess().lookupOrThrow(Registries.TEMPLATE_POOL);
		Map<Identifier, Set<Identifier>> poolTemplates = new HashMap<>();
		Map<Identifier, Identifier> fallbacks = new HashMap<>();
		for (var entry : poolRegistry.entrySet()) {
			Identifier poolId = entry.getKey().identifier();
			Set<Identifier> inPool = new LinkedHashSet<>();
			for (Pair<StructurePoolElement, Integer> element : entry.getValue().getTemplates()) {
				templatesOf(element.getFirst(), inPool);
			}
			poolTemplates.put(poolId, inPool);
			inPool.forEach(t -> poolsOf.computeIfAbsent(t, k -> new TreeSet<>()).add(poolId));
			entry.getValue().getFallback().unwrapKey().ifPresent(key -> fallbacks.put(poolId, key.identifier()));
		}
		Registry<Structure> structureRegistry = server.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		for (var entry : structureRegistry.entrySet()) {
			if (!(entry.getValue() instanceof JigsawStructure jigsaw)) {
				continue;
			}
			Deque<Identifier> queue = new ArrayDeque<>();
			jigsaw.getStartPool().unwrapKey().ifPresent(key -> queue.add(key.identifier()));
			for (PoolAliasBinding alias : jigsaw.getPoolAliases()) {
				alias.allTargets().forEach(key -> queue.add(key.identifier()));
			}
			Set<Identifier> seenPools = new HashSet<>();
			while (!queue.isEmpty()) {
				Identifier pool = queue.poll();
				if (!seenPools.add(pool)) {
					continue;
				}
				Optional.ofNullable(fallbacks.get(pool)).ifPresent(queue::add);
				for (Identifier template : poolTemplates.getOrDefault(pool, Set.of())) {
					structuresOf.computeIfAbsent(template, k -> new TreeSet<>()).add(entry.getKey().identifier());
					Summary summary = templates.get(template);
					if (summary != null) {
						queue.addAll(summary.jigsawPools());
					}
				}
			}
		}
	}

	private static void templatesOf(StructurePoolElement element, Collection<Identifier> out) {
		if (element instanceof ListPoolElement list) {
			list.getElements().forEach(e -> templatesOf(e, out));
		} else if (element instanceof SinglePoolElement single) {
			try {
				out.add(single.getTemplateLocation());
			} catch (RuntimeException inlineTemplate) {
				// A template defined inline in code has no id.
			}
		}
	}

	/** "minecraft:stone_bricks". */
	public static String id(Block block) {
		return BuiltInRegistries.BLOCK.getKey(block).toString();
	}
}
