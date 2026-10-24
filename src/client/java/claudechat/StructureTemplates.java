package claudechat;

import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Registry;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pools.ListPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasBinding;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.loader.DirectoryTemplateSource;
import net.minecraft.world.level.levelgen.structure.templatesystem.loader.ResourceManagerTemplateSource;
import net.minecraft.world.level.levelgen.structure.templatesystem.loader.TemplatePathFactory;
import net.minecraft.world.level.levelgen.structure.templatesystem.loader.TemplateSource;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Structure templates: the .nbt files that jigsaw structures (villages, ancient cities, most modded structures) and
 * code-placed ones (igloos, shipwrecks) are built from. They are gzipped NBT, so Claude can't read them as files.
 * One template is read from the game's {@link StructureTemplateManager}, so it is exactly what the game places.
 * <p>
 * For searching by block, every template is summarized once per resource reload, and templates saved with a
 * structure block are summarized again when their file changes. Which structures can place a template is traced
 * through template pools: from each jigsaw structure's start pool, to the templates in it, to the pools their jigsaw
 * blocks point at, and so on.
 */
public final class StructureTemplates {
	/** Where a template comes from. */
	public sealed interface Origin permits Pack, SavedInWorld, GameTestFiles, ManagerOnly {
		/** For tool output: the pack id, or the kind of place. */
		String describe();
	}

	/** A data pack, mod or Minecraft itself, by pack id ("vanilla", a mod id, "file/MyPack"). */
	public record Pack(String id) implements Origin {
		@Override
		public String describe() {
			return id;
		}
	}

	public record SavedInWorld() implements Origin {
		@Override
		public String describe() {
			return "this world (saved with a structure block)";
		}
	}

	public record GameTestFiles() implements Origin {
		@Override
		public String describe() {
			return "game test structures";
		}
	}

	/** Known to the game's template manager without a file: captured by a structure block but not saved, or added by a mod's code. */
	public record ManagerOnly() implements Origin {
		@Override
		public String describe() {
			return "no file (not saved yet, or added by a mod's code)";
		}
	}

	/** A template as the game places it. {@code volume} is its first palette; a structure with several picks one at random. */
	public record Template(Identifier id, Origin origin, int palettes, BlockVolume volume) {
	}

	/**
	 * What the search keeps of each template. Counts are of counted blocks ({@link BlockVolume#counts}); for a template
	 * with several palettes, each block's count, and the total, is the most in any one palette.
	 */
	public record Summary(Identifier id, Origin origin, Vec3i size, Map<Block, Integer> blockCounts, int countedBlocks,
		Set<Identifier> jigsawPools, Map<String, Integer> entityCounts) {
	}

	/** What a search asks for: blocks by the term that named them, text in the template's or a structure's id, and a size limit. */
	public record Query(Map<String, Set<Block>> wanted, String text, int maxSize) {
	}

	/** A search result. {@code score} is matching blocks × their share, so both many matches and a build mostly made of them rank high. */
	public record Hit(Summary template, Map<String, Integer> perTerm, int termsMatched, double share, double score) {
	}

	/** Hits, best first. {@code noneHasAll} says no template has every wanted block, so these have the most. */
	public record SearchResult(List<Hit> hits, boolean noneHasAll) {
	}

	/** Templates with fewer counted blocks than this rarely identify a building (a lone spawner on a jigsaw), so they rank after real ones. */
	private static final int MIN_BUILDING_BLOCKS = 8;

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

	private Build current;

	/** Starts indexing in the background (world opened, or /reload), so the first search doesn't wait for it. */
	public void prepare(MinecraftServer server) {
		build(server);
	}

	/** Stops a build in progress and forgets the index; call when the world closes. */
	public synchronized void stop() {
		if (current != null) {
			current.cancel();
			current = null;
		}
	}

	/** The index for the server's current resources and world saves, waiting for it if it's still being built. */
	public Index index(MinecraftServer server) throws Exception {
		Build build = build(server);
		build.future.get(5, TimeUnit.MINUTES);
		return build.refreshed(server);
	}

	/** A new build when the resources changed (a reload replaces the resource manager) or the last one failed; the old build is cancelled. */
	private synchronized Build build(MinecraftServer server) {
		ResourceManager resources = server.getResourceManager();
		if (current != null && current.resources == resources && !current.future.isCompletedExceptionally()) {
			return current;
		}

		if (current != null) {
			current.cancel();
		}

		current = new Build(server, resources);
		return current;
	}

	/**
	 * A template by id, from the game's template manager (and cached there, as when the game places it), or empty if
	 * no source has it.
	 */
	public static Optional<Template> load(MinecraftServer server, Identifier id) throws IOException {
		Optional<StructureTemplate> template = server.getStructureTemplateManager().get(id);
		if (template.isEmpty()) {
			return Optional.empty();
		}

		StructureNbt.Contents contents = StructureNbt.read(template.get().save(new CompoundTag()));
		return Optional.of(new Template(id, new Sources(server).origin(id), contents.palettes().size(), contents.volume(0)));
	}

	// --- search -----------------------------------------------------------------------------------------------------

	/**
	 * Templates that fit the query, best first: those with the most of the wanted terms, then real buildings before tiny
	 * pieces, then by {@link Hit#score}. With no wanted blocks, every template that passes the text and size filters.
	 */
	public static SearchResult search(Index index, Query query) {
		String text = query.text().trim().toLowerCase(Locale.ROOT);
		Set<Block> anyWanted = new HashSet<>();
		query.wanted().values().forEach(anyWanted::addAll);

		List<Hit> hits = new ArrayList<>();
		for (Summary t : index.templates()) {
			Vec3i size = t.size();
			if (Math.max(size.getX(), Math.max(size.getY(), size.getZ())) > query.maxSize()) {
				continue;
			}

			if (!text.isEmpty() && !t.id().toString().contains(text) && index.structuresUsing(t.id()).stream().noneMatch(s -> s.toString().contains(text))) {
				continue;
			}

			Map<String, Integer> perTerm = new LinkedHashMap<>();
			int termsMatched = 0;
			for (Map.Entry<String, Set<Block>> term : query.wanted().entrySet()) {
				int count = term.getValue().stream().mapToInt(b -> t.blockCounts().getOrDefault(b, 0)).sum();
				perTerm.put(term.getKey(), count);
				termsMatched += count > 0 ? 1 : 0;
			}

			if (!query.wanted().isEmpty() && termsMatched == 0) {
				continue;
			}

			int matchingBlocks = anyWanted.stream().mapToInt(b -> t.blockCounts().getOrDefault(b, 0)).sum();
			double share = t.countedBlocks() == 0 ? 0 : (double) matchingBlocks / t.countedBlocks();
			hits.add(new Hit(t, perTerm, termsMatched, share, matchingBlocks * share));
		}

		hits.sort(Comparator.comparingInt(Hit::termsMatched).reversed()
			.thenComparing(h -> h.template().countedBlocks() < MIN_BUILDING_BLOCKS)
			.thenComparing(Comparator.comparingDouble(Hit::score).reversed())
			.thenComparing(h -> h.template().id().toString()));
		boolean noneHasAll = !hits.isEmpty() && query.wanted().size() > 1 && hits.getFirst().termsMatched() < query.wanted().size();
		return new SearchResult(hits, noneHasAll);
	}

	// --- the index --------------------------------------------------------------------------------------------------

	/** All readable templates, plus which pools list each one and which structures can reach it. */
	public static final class Index {
		private final Map<Identifier, Summary> summaries;
		private final List<Summary> sorted;
		private final Set<Identifier> unreadable;
		private final Usage usage;

		Index(Map<Identifier, Summary> summaries, Set<Identifier> unreadable, Usage usage) {
			this.summaries = Map.copyOf(summaries);
			this.sorted = summaries.values().stream().sorted(Comparator.comparing(s -> s.id().toString())).toList();
			this.unreadable = Set.copyOf(unreadable);
			this.usage = usage;
		}

		/** An index of the summaries, traced through the server's template pools and structures. */
		static Index traced(MinecraftServer server, Map<Identifier, Summary> summaries, Set<Identifier> unreadable) {
			return new Index(summaries, unreadable, Usage.trace(server, summaries));
		}

		/** By id. */
		public List<Summary> templates() {
			return sorted;
		}

		/** Templates listed by the game that no source could read. */
		public Set<Identifier> unreadable() {
			return unreadable;
		}

		/** Structures that can place the template, traced through pools; empty for templates placed by code. */
		public Set<Identifier> structuresUsing(Identifier template) {
			return usage.structuresOf().getOrDefault(template, Set.of());
		}

		/** Template pools that list the template. */
		public Set<Identifier> poolsListing(Identifier template) {
			return usage.poolsOf().getOrDefault(template, Set.of());
		}

		/** Up to {@code limit} template ids containing the last part of {@code id}'s path, for "did you mean". */
		public List<Identifier> similarTo(Identifier id, int limit) {
			String last = id.getPath().substring(id.getPath().lastIndexOf('/') + 1);
			return sorted.stream().map(Summary::id).filter(t -> t.toString().contains(last)).limit(limit).toList();
		}

		Map<Identifier, Summary> summaries() {
			return summaries;
		}
	}

	/** Template → the pools that list it, and template → the structures that can place it. */
	record Usage(Map<Identifier, Set<Identifier>> poolsOf, Map<Identifier, Set<Identifier>> structuresOf) {
		static final Usage NONE = new Usage(Map.of(), Map.of());

		/**
		 * Traces each jigsaw structure from its start pool through the pools its templates' jigsaw blocks point at.
		 * Structures placed by code (igloos, shipwrecks, many mods' own structures) name their templates in Java, so they
		 * don't show up here.
		 */
		static Usage trace(MinecraftServer server, Map<Identifier, Summary> summaries) {
			Map<Identifier, Set<Identifier>> poolsOf = new HashMap<>();
			Map<Identifier, Set<Identifier>> structuresOf = new HashMap<>();
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
						Summary summary = summaries.get(template);
						if (summary != null) {
							queue.addAll(summary.jigsawPools());
						}
					}
				}
			}

			return new Usage(poolsOf, structuresOf);
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
	}

	/** Summarizes a template, or empty if no source can read it. */
	private static Optional<Summary> summarize(Sources sources, Identifier id) {
		Optional<Sources.Loaded> loaded = sources.load(id);
		if (loaded.isEmpty()) {
			return Optional.empty();
		}

		try {
			return Optional.of(summarize(id, loaded.get().origin(), StructureNbt.read(loaded.get().template().save(new CompoundTag()))));
		} catch (IOException | RuntimeException e) {
			ClaudeChatClient.LOGGER.warn("Could not read structure template {}: {}", id, e.toString());
			return Optional.empty();
		}
	}

	static Summary summarize(Identifier id, Origin origin, StructureNbt.Contents contents) throws IOException {
		Map<Block, Integer> counts = new HashMap<>();
		int counted = 0;
		BlockVolume first = null;
		for (int p = 0; p < Math.max(1, contents.palettes().size()); p++) {
			BlockVolume volume = contents.volume(p);
			first = first == null ? volume : first;
			Map<Block, Integer> paletteCounts = volume.countedBlocks();
			paletteCounts.forEach((block, count) -> counts.merge(block, count, Math::max));
			counted = Math.max(counted, paletteCounts.values().stream().mapToInt(Integer::intValue).sum());
		}

		// Jigsaw connectors sit at the same positions, with the same data, in every palette.
		Set<Identifier> jigsawPools = new HashSet<>();
		for (BlockVolume.BlockEntity be : first.blockEntities()) {
			if (be.state().is(Blocks.JIGSAW)) {
				Identifier pool = Identifier.tryParse(be.data().getStringOr("pool", ""));
				if (pool != null && !pool.getPath().isEmpty()) {
					jigsawPools.add(pool);
				}
			}
		}

		Map<String, Integer> entities = new HashMap<>();
		first.entities().forEach(e -> entities.merge(e.type(), 1, Integer::sum));
		return new Summary(id, origin, first.size(), counts, counted, jigsawPools, entities);
	}

	/**
	 * One index build, for one resource manager. World-saved templates are checked against their files on every use,
	 * so a structure block save shows up in the next search.
	 */
	private static final class Build {
		final ResourceManager resources;
		final CompletableFuture<Index> future;
		private final AtomicBoolean cancelled = new AtomicBoolean();
		private final Sources sources;
		private Map<Identifier, FileTime> worldFiles;
		private Index latest;

		Build(MinecraftServer server, ResourceManager resources) {
			this.resources = resources;
			this.sources = new Sources(server);
			// Taken before reading, so a save during the build is picked up by the first refresh.
			this.worldFiles = sources.worldFiles();
			this.future = CompletableFuture.supplyAsync(() -> {
				long start = System.nanoTime();
				Index built = build(server);
				ClaudeChatClient.LOGGER.info("Indexed {} structure templates in {} ms ({} unreadable{})", built.templates().size(),
					(System.nanoTime() - start) / 1_000_000, built.unreadable().size(),
					built.unreadable().isEmpty() ? "" : ": " + built.unreadable().stream().limit(10).map(Identifier::toString).sorted().toList());
				return built;
			}, POOL);
		}

		/** Stops the build at the next template; it holds the server until then. */
		void cancel() {
			cancelled.set(true);
			future.cancel(false);
		}

		private Index build(MinecraftServer server) {
			List<Identifier> ids = server.getStructureTemplateManager().listTemplates().toList();
			Map<Identifier, Summary> summaries = new ConcurrentHashMap<>();
			Set<Identifier> unreadable = ConcurrentHashMap.newKeySet();
			// Independent per file; this runs inside POOL, so the parallel stream uses its threads.
			ids.parallelStream().forEach(id -> {
				if (cancelled.get()) {
					throw new CancellationException("Template index build cancelled");
				}

				summarize(sources, id).ifPresentOrElse(s -> summaries.put(id, s), () -> unreadable.add(id));
			});
			return Index.traced(server, summaries, unreadable);
		}

		/** The built index, with world-saved templates whose files changed since summarized again. */
		synchronized Index refreshed(MinecraftServer server) {
			Index index = latest != null ? latest : future.join();
			Map<Identifier, FileTime> now = sources.worldFiles();
			if (now.equals(worldFiles)) {
				latest = index;
				return index;
			}

			Set<Identifier> changed = new HashSet<>(now.keySet());
			changed.addAll(worldFiles.keySet());
			changed.removeIf(id -> Optional.ofNullable(now.get(id)).equals(Optional.ofNullable(worldFiles.get(id))));
			Map<Identifier, Summary> summaries = new HashMap<>(index.summaries());
			Set<Identifier> unreadable = new HashSet<>(index.unreadable());
			for (Identifier id : changed) {
				// A deleted world file uncovers the pack's template of the same id, if there is one.
				summaries.remove(id);
				unreadable.remove(id);
				summarize(sources, id).ifPresentOrElse(s -> summaries.put(id, s), () -> {
					if (now.containsKey(id)) {
						unreadable.add(id);
					}
				});
			}

			worldFiles = now;
			latest = Index.traced(server, summaries, unreadable);
			return latest;
		}
	}

	/**
	 * The game's template sources in {@link StructureTemplateManager}'s order: templates saved in the world, game test
	 * files (only when running game tests), then data packs, mods and Minecraft through the resource manager. Reading
	 * and data fixing are the game's own; the index reads through these rather than the manager so that it doesn't fill
	 * the manager's cache, which would keep thousands of templates in memory for the rest of the session. Only ids
	 * none of them has go to the manager itself. The pack id, which the manager doesn't keep, comes from the resource
	 * manager.
	 */
	private static final class Sources {
		record Loaded(StructureTemplate template, Origin origin) {
		}

		/** structure/<path>.nbt, the layout of both world saves and packs (the manager's own lister for packs is private). */
		private static final FileToIdConverter TEMPLATE_FILES = StructureTemplateManager.WORLD_STRUCTURE_LISTER;

		private final StructureTemplateManager manager;
		private final ResourceManager resources;
		private final TemplatePathFactory worldPaths;
		private final TemplateSource world;
		private final TemplatePathFactory gameTestPaths;
		private final TemplateSource gameTests;
		private final TemplateSource packs;

		Sources(MinecraftServer server) {
			DataFixer fixer = server.getFixerUpper();
			HolderGetter<Block> blocks = server.registryAccess().lookupOrThrow(Registries.BLOCK);
			Path generated = server.getWorldPath(LevelResource.GENERATED_DIR).normalize();
			this.manager = server.getStructureTemplateManager();
			this.resources = server.getResourceManager();
			this.worldPaths = new TemplatePathFactory(generated);
			this.world = new DirectoryTemplateSource(fixer, blocks, generated, TEMPLATE_FILES, false);
			Path gameTestDir = StructureUtils.testStructuresSourceDir;
			this.gameTestPaths = gameTestDir == null ? null : new TemplatePathFactory(gameTestDir, PackType.SERVER_DATA);
			this.gameTests = gameTestDir == null ? null : new DirectoryTemplateSource(fixer, blocks, gameTestDir, PackType.SERVER_DATA,
				StructureTemplateManager.RESOURCE_TEXT_STRUCTURE_LISTER, true);
			this.packs = new ResourceManagerTemplateSource(fixer, blocks, resources, TEMPLATE_FILES);
		}

		/** The first source that reads the template, as the game's manager picks it. */
		Optional<Loaded> load(Identifier id) {
			Optional<StructureTemplate> fromWorld = world.load(id);
			if (fromWorld.isPresent()) {
				return Optional.of(new Loaded(fromWorld.get(), new SavedInWorld()));
			}

			if (gameTests != null) {
				Optional<StructureTemplate> fromTests = gameTests.load(id);
				if (fromTests.isPresent()) {
					return Optional.of(new Loaded(fromTests.get(), new GameTestFiles()));
				}
			}

			Optional<StructureTemplate> fromPacks = packs.load(id);
			if (fromPacks.isPresent()) {
				return Optional.of(new Loaded(fromPacks.get(), packOrigin(id)));
			}

			// Mods can add sources to the manager (Fabric's game test API does); ask it for the few ids none of these has.
			return manager.get(id).map(template -> new Loaded(template, new ManagerOnly()));
		}

		/** Which source has a file for the template, in the game's order; {@link ManagerOnly} if none. */
		Origin origin(Identifier id) {
			if (fileExists(worldPaths, id, TEMPLATE_FILES)) {
				return new SavedInWorld();
			}

			if (gameTestPaths != null && fileExists(gameTestPaths, id, StructureTemplateManager.RESOURCE_TEXT_STRUCTURE_LISTER)) {
				return new GameTestFiles();
			}

			return resources.getResource(packFile(id)).isPresent() ? packOrigin(id) : new ManagerOnly();
		}

		/** Modification times of the templates saved in the world. */
		Map<Identifier, FileTime> worldFiles() {
			Map<Identifier, FileTime> stamps = new HashMap<>();
			world.list().forEach(id -> {
				try {
					stamps.put(id, Files.getLastModifiedTime(worldPaths.createAndValidatePathToStructure(id, TEMPLATE_FILES)));
				} catch (IOException | RuntimeException e) {
					ClaudeChatClient.LOGGER.debug("Could not check saved structure {}", id, e);
				}
			});
			return stamps;
		}

		private Origin packOrigin(Identifier id) {
			return new Pack(resources.getResource(packFile(id)).map(Resource::sourcePackId).orElse("?"));
		}

		private static Identifier packFile(Identifier id) {
			return TEMPLATE_FILES.idToFile(id);
		}

		private static boolean fileExists(TemplatePathFactory paths, Identifier id, FileToIdConverter lister) {
			try {
				return Files.isRegularFile(paths.createAndValidatePathToStructure(id, lister));
			} catch (RuntimeException invalidPath) {
				return false;
			}
		}
	}
}
