package claudechat;

import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Registry;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
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
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.loader.DirectoryTemplateSource;
import net.minecraft.world.level.levelgen.structure.templatesystem.loader.TemplatePathFactory;
import net.minecraft.world.level.levelgen.structure.templatesystem.loader.TemplateSource;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Structure templates: the .nbt files that jigsaw structures (villages, ancient cities, most modded structures) and
 * code-placed ones (igloos, shipwrecks) are built from. They are gzipped NBT, so Claude can't read them as files.
 * A template is read from the game's own sources in the game's order, so it is what the game places; files are read
 * within the limits on imported files ({@link NbtBudget}), since a pack can be anyone's.
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

	/** Threads reading templates for the index at once, whatever the number of cores. */
	private static final int MAX_THREADS = 4;

	/**
	 * Data fixing thousands of old files takes a while (about 20 s of all cores for 7000 templates), so it runs on
	 * low-priority threads, half the cores at most, and the game keeps running smoothly meanwhile. There are at most
	 * {@link #MAX_THREADS} of them, since each may hold a template's read within {@link Build#PARALLEL_BYTES}, its fixed
	 * copy and its box at once.
	 */
	private static final ForkJoinPool POOL = new ForkJoinPool(Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, MAX_THREADS), pool -> {
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
	 * A template by id, from the first of the game's sources that has it (see {@link Sources}), or empty if none has it.
	 *
	 * @throws TooLargeException if the template is over {@link NbtBudget#FILE} or another limit on imported files
	 */
	public static Optional<Template> load(MinecraftServer server, Identifier id) throws IOException {
		try {
			Optional<Sources.Loaded> loaded = new Sources(server).load(id, ReadLimits.FILE);
			if (loaded.isEmpty()) {
				return Optional.empty();
			}

			StructureNbt.Contents contents = StructureNbt.read(loaded.get().structure());
			return Optional.of(new Template(id, loaded.get().origin(), contents.palettes().size(), contents.volume(0)));
		} catch (TooLargeException e) {
			throw new TooLargeException("Structure template " + id + " is too large to read: " + e.getMessage());
		}
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
		private final Optional<String> incomplete;
		private final Usage usage;

		Index(Map<Identifier, Summary> summaries, Set<Identifier> unreadable, Usage usage) {
			this(summaries, unreadable, Optional.empty(), usage);
		}

		Index(Map<Identifier, Summary> summaries, Set<Identifier> unreadable, Optional<String> incomplete, Usage usage) {
			this.summaries = Map.copyOf(summaries);
			this.sorted = summaries.values().stream().sorted(Comparator.comparing(s -> s.id().toString())).toList();
			this.unreadable = Set.copyOf(unreadable);
			this.incomplete = incomplete;
			this.usage = usage;
		}

		/** An index of the summaries, traced through the server's template pools and structures. */
		static Index traced(MinecraftServer server, Map<Identifier, Summary> summaries, Set<Identifier> unreadable, Optional<String> incomplete) {
			return new Index(summaries, unreadable, incomplete, Usage.trace(server, summaries));
		}

		/** By id. */
		public List<Summary> templates() {
			return sorted;
		}

		/** Templates listed by the game that no source could read. */
		public Set<Identifier> unreadable() {
			return unreadable;
		}

		/** Why the build stopped before reading every template (see {@link BuildBudget}), or empty if it read them all. */
		public Optional<String> incomplete() {
			return incomplete;
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

	/**
	 * What reading one template may use: its NBT, and the positions of its box, checked before the template is data
	 * fixed. Summaries are charged to {@code shared}, the allowance of the build they are for.
	 */
	private record ReadLimits(NbtBudget nbt, long maxPositions, BuildBudget shared) {
		/** A template read for a tool call: the limits on one imported file. */
		static final ReadLimits FILE = new ReadLimits(NbtBudget.FILE, BlockVolume.MAX_POSITIONS, BuildBudget.UNLIMITED);
	}

	/**
	 * The allowance one index build shares among all its templates, on top of each template's own limits: a pack can
	 * hold any number of templates that are each within them. The build reads templates while the allowance lasts and
	 * keeps what it has read by then; a template that is admitted is charged after it's read, so the build goes over by
	 * at most one template per thread.
	 * <p>
	 * Work is counted in the units summarizing costs: positions of a template's box, plus its block entries, palette
	 * states and entities, which the data fixers and the summary walk.
	 */
	static final class BuildBudget {
		/** Templates one build reads. A heavily modded game has about 7,000. */
		static final int MAX_TEMPLATES = 1 << 16;

		/** Work one build does: a thousand templates of a million positions each. */
		static final long MAX_WORK = 1L << 30;

		/** Time one build takes; a search waits five minutes for it. Those 7,000 templates take about 20 s of all cores. */
		static final Duration MAX_TIME = Duration.ofMinutes(3);

		static final BuildBudget UNLIMITED = new BuildBudget(Long.MAX_VALUE, null);

		private final long maxWork;
		private final Long deadline;
		private final AtomicLong work = new AtomicLong();
		private volatile String exhausted;

		/** {@code time} null for no time limit. */
		BuildBudget(long maxWork, Duration time) {
			this.maxWork = maxWork;
			this.deadline = time == null ? null : System.nanoTime() + time.toNanos();
		}

		static BuildBudget forBuild() {
			return new BuildBudget(MAX_WORK, MAX_TIME);
		}

		/** Adds a template's work (see {@link #work}). */
		void charge(long units) {
			work.accumulateAndGet(units, (a, b) -> a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b);
		}

		/** Empty while templates may still be read, else why the build stops early. Once exhausted, it stays so. */
		Optional<String> exhausted() {
			if (exhausted == null) {
				if (work.get() > maxWork) {
					exhausted = "stopped after the work limit of one index build";
				} else if (deadline != null && System.nanoTime() - deadline > 0) {
					exhausted = "stopped after " + MAX_TIME.toSeconds() + " s, the time limit of one index build";
				}
			}

			return Optional.ofNullable(exhausted);
		}

		/** What summarizing a template costs, in the units of {@link #MAX_WORK}. */
		static long work(StructureNbt.Contents contents) {
			long states = contents.palettes().stream().mapToLong(List::size).sum();
			return BlockVolume.positions(contents.size()) + contents.blocks().size() + states + contents.entities().size();
		}
	}

	/**
	 * Summarizes a template, or empty if no source has it or it can't be read (which is logged).
	 *
	 * @throws TooLargeException if it is over {@code limits}
	 */
	private static Optional<Summary> summarize(Sources sources, Identifier id, ReadLimits limits) throws TooLargeException {
		try {
			Optional<Sources.Loaded> loaded = sources.load(id, limits);
			if (loaded.isEmpty()) {
				return Optional.empty();
			}

			StructureNbt.Contents contents = StructureNbt.read(loaded.get().structure());
			TooLargeException.requireAtMost(BlockVolume.requireFits(contents.size()), limits.maxPositions(), "positions");
			limits.shared().charge(BuildBudget.work(contents));
			return Optional.of(summarize(id, loaded.get().origin(), contents, limits.nbt().cancelled()));
		} catch (TooLargeException | CancellationException e) {
			throw e;
		} catch (IOException | RuntimeException e) {
			ClaudeChatClient.LOGGER.warn("Could not read structure template {}: {}", id, e.toString());
			return Optional.empty();
		}
	}

	/** {@link #summarize(Sources, Identifier, ReadLimits)}, where a template over the limits is logged as unreadable. */
	private static Optional<Summary> summarizeOrSkip(Sources sources, Identifier id, ReadLimits limits) {
		try {
			return summarize(sources, id, limits);
		} catch (TooLargeException e) {
			ClaudeChatClient.LOGGER.warn("Structure template {} is too large to read: {}", id, e.getMessage());
			return Optional.empty();
		}
	}

	static Summary summarize(Identifier id, Origin origin, StructureNbt.Contents contents) throws IOException {
		return summarize(id, origin, contents, () -> false);
	}

	/**
	 * Summarizes a template in one pass over its box, whatever its number of palettes: block entries hold palette
	 * indexes, so the box is filled with indexes once, and each palette then only names the blocks of the indexes used.
	 * (A volume per palette would cost palettes × positions.) Stops, with a {@link CancellationException}, once
	 * {@code cancelled} says so.
	 *
	 * @throws IOException if an entry's state isn't in every palette or it lies outside the size
	 */
	static Summary summarize(Identifier id, Origin origin, StructureNbt.Contents contents, BooleanSupplier cancelled) throws IOException {
		Vec3i size = contents.size();
		List<List<BlockState>> palettes = contents.palettes().isEmpty() ? List.of(List.of()) : contents.palettes();
		for (List<BlockState> palette : palettes) {
			TooLargeException.requireAtMost(palette.size(), BlockVolume.MAX_STATES, "block states in a palette");
		}

		int states = palettes.stream().mapToInt(List::size).min().orElse(0);
		short[] cells = new short[(int) BlockVolume.requireFits(size)];
		Arrays.fill(cells, ABSENT);
		int done = 0;
		for (StructureNbt.Entry entry : contents.blocks()) {
			checkCancelled(done++, cancelled);
			BlockPos pos = entry.pos();
			if (pos.getX() < 0 || pos.getY() < 0 || pos.getZ() < 0 || pos.getX() >= size.getX() || pos.getY() >= size.getY() || pos.getZ() >= size.getZ()) {
				throw new IOException("Block at " + pos.toShortString() + " is outside the size " + size.toShortString());
			}

			if (entry.state() < 0 || entry.state() >= states) {
				throw new IOException("Block at " + pos.toShortString() + " has state " + entry.state() + ", but a palette has " + states);
			}

			cells[cell(size, pos)] = (short) entry.state();
		}

		int[] uses = new int[states];
		for (int i = 0; i < cells.length; i++) {
			checkCancelled(i, cancelled);
			if (cells[i] != ABSENT) {
				uses[cells[i]]++;
			}
		}

		Map<Block, Integer> counts = new HashMap<>();
		int counted = 0;
		for (List<BlockState> palette : palettes) {
			Map<Block, Integer> paletteCounts = new HashMap<>();
			for (int s = 0; s < states; s++) {
				if (uses[s] > 0 && BlockVolume.counts(palette.get(s))) {
					paletteCounts.merge(palette.get(s).getBlock(), uses[s], Integer::sum);
				}
			}

			paletteCounts.forEach((block, count) -> counts.merge(block, count, Math::max));
			counted = Math.max(counted, paletteCounts.values().stream().mapToInt(Integer::intValue).sum());
		}

		// Jigsaw connectors sit at the same positions, with the same data, in every palette.
		Set<Identifier> jigsawPools = new HashSet<>();
		for (StructureNbt.Entry entry : contents.blocks()) {
			if (entry.nbt() != null && palettes.getFirst().get(cells[cell(size, entry.pos())]).is(Blocks.JIGSAW)) {
				Identifier pool = Identifier.tryParse(entry.nbt().getStringOr("pool", ""));
				if (pool != null && !pool.getPath().isEmpty()) {
					jigsawPools.add(pool);
				}
			}
		}

		Map<String, Integer> entities = new HashMap<>();
		contents.entities().forEach(e -> entities.merge(e.type(), 1, Integer::sum));
		return new Summary(id, origin, size, counts, counted, jigsawPools, entities);
	}

	/** A position not set by any block entry, in {@link #summarize}'s box. */
	private static final short ABSENT = -1;

	/** A position's index in {@link #summarize}'s box, which has room for every position of a box within {@link BlockVolume#MAX_POSITIONS}. */
	private static int cell(Vec3i size, BlockPos pos) {
		return (pos.getY() * size.getZ() + pos.getZ()) * size.getX() + pos.getX();
	}

	/** Checks for cancellation every 65,536 steps of a loop. */
	private static void checkCancelled(int step, BooleanSupplier cancelled) {
		if ((step & 0xFFFF) == 0 && cancelled.getAsBoolean()) {
			throw new CancellationException("Summarizing a structure template was cancelled");
		}
	}

	/**
	 * One index build, for one resource manager. World-saved templates are checked against their files on every use,
	 * so a structure block save shows up in the next search.
	 */
	private static final class Build {
		/**
		 * Templates are read in parallel within these limits, so that the reads in flight together stay small: 32 MiB of
		 * NBT and a 4M-position box (8 MB per volume) per thread. Nearly every template fits; the largest seen in a
		 * heavily modded game needs 59 MB. Those that don't fit are read afterwards, one at a time, within {@link #fullLimits}.
		 */
		private static final long PARALLEL_BYTES = 32L << 20;
		private static final long PARALLEL_POSITIONS = 1L << 22;

		final ResourceManager resources;
		final CompletableFuture<Index> future;
		private final AtomicBoolean cancelled = new AtomicBoolean();
		/** Shared by both passes, so a template retried within the full limits is charged to the same allowance. */
		private final BuildBudget budget = BuildBudget.forBuild();
		private final ReadLimits parallelLimits = new ReadLimits(new NbtBudget(PARALLEL_BYTES, cancelled::get), PARALLEL_POSITIONS, budget);
		private final ReadLimits fullLimits = new ReadLimits(new NbtBudget(NbtBudget.FILE_BYTES, cancelled::get), BlockVolume.MAX_POSITIONS, budget);
		/** For templates saved again after the build: each one is the player's own save, read within the full limits of one file. */
		private final ReadLimits refreshLimits = new ReadLimits(new NbtBudget(NbtBudget.FILE_BYTES, cancelled::get), BlockVolume.MAX_POSITIONS,
			BuildBudget.UNLIMITED);
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
				ClaudeChatClient.LOGGER.info("Indexed {} structure templates in {} ms ({} unreadable{}){}", built.templates().size(),
					(System.nanoTime() - start) / 1_000_000, built.unreadable().size(),
					built.unreadable().isEmpty() ? "" : ": " + built.unreadable().stream().limit(10).map(Identifier::toString).sorted().toList(),
					built.incomplete().map(reason -> "; " + reason + ", so the rest are left out").orElse(""));
				return built;
			}, POOL);
		}

		/**
		 * Stops the build: before the next template, or within a template's read, which checks as it goes. Data fixing a
		 * template that has been read can't be stopped; it is bounded by the read's limits.
		 */
		void cancel() {
			cancelled.set(true);
			future.cancel(false);
		}

		private Index build(MinecraftServer server) {
			List<Identifier> listed = server.getStructureTemplateManager().listTemplates().limit(BuildBudget.MAX_TEMPLATES + 1L).toList();
			List<Identifier> ids = listed.subList(0, Math.min(listed.size(), BuildBudget.MAX_TEMPLATES));
			Optional<String> tooMany = listed.size() > ids.size()
				? Optional.of("stopped after " + BuildBudget.MAX_TEMPLATES + " templates, the limit of one index build")
				: Optional.empty();

			Map<Identifier, Summary> summaries = new ConcurrentHashMap<>();
			Set<Identifier> unreadable = ConcurrentHashMap.newKeySet();
			Set<Identifier> large = ConcurrentHashMap.newKeySet();
			// Independent per file; this runs inside POOL, so the parallel stream uses its threads.
			ids.parallelStream().forEach(id -> {
				requireNotCancelled();
				if (budget.exhausted().isPresent()) {
					return;
				}

				try {
					record(id, summarize(sources, id, parallelLimits), summaries, unreadable);
				} catch (TooLargeException e) {
					large.add(id);
				}
			});

			for (Identifier id : large) {
				requireNotCancelled();
				if (budget.exhausted().isPresent()) {
					break;
				}

				record(id, summarizeOrSkip(sources, id, fullLimits), summaries, unreadable);
			}

			Optional<String> stoppedEarly = budget.exhausted().or(() -> tooMany);
			return Index.traced(server, summaries, unreadable, stoppedEarly);
		}

		private void requireNotCancelled() {
			if (cancelled.get()) {
				throw new CancellationException("Template index build cancelled");
			}
		}

		private static void record(Identifier id, Optional<Summary> summary, Map<Identifier, Summary> summaries, Set<Identifier> unreadable) {
			summary.ifPresentOrElse(s -> summaries.put(id, s), () -> unreadable.add(id));
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
				summarizeOrSkip(sources, id, refreshLimits).ifPresentOrElse(s -> summaries.put(id, s), () -> {
					if (now.containsKey(id)) {
						unreadable.add(id);
					}
				});
			}

			worldFiles = now;
			latest = Index.traced(server, summaries, unreadable, index.incomplete());
			return latest;
		}
	}

	/**
	 * The game's template sources in {@link StructureTemplateManager}'s order: templates saved in the world, game test
	 * files (only when running game tests), then data packs, mods and Minecraft through the resource manager. As in the
	 * game, a file that can't be read is skipped for the next source.
	 * <p>
	 * World and pack files are read here, within an {@link NbtBudget}, since the game reads them with no limit; data
	 * fixing is the game's own. Reading here rather than through the manager also keeps the manager's cache from filling
	 * with thousands of templates for the rest of the session. Game test files (text, written by this mod's developers)
	 * and ids none of the sources has (a mod's code can add sources) are left to the game.
	 */
	private static final class Sources {
		/** A template as an up-to-date structure tag (see {@link StructureNbt#upgrade}), and where it came from. */
		record Loaded(CompoundTag structure, Origin origin) {
		}

		/** structure/<path>.nbt, the layout of both world saves and packs (the manager's own lister for packs is private). */
		private static final FileToIdConverter TEMPLATE_FILES = StructureTemplateManager.WORLD_STRUCTURE_LISTER;

		private final StructureTemplateManager manager;
		private final ResourceManager resources;
		private final TemplatePathFactory worldPaths;
		/** Lists the world's templates; they are read through {@link #worldPaths}. */
		private final TemplateSource world;
		private final TemplateSource gameTests;

		Sources(MinecraftServer server) {
			DataFixer fixer = server.getFixerUpper();
			HolderGetter<Block> blocks = server.registryAccess().lookupOrThrow(Registries.BLOCK);
			Path generated = server.getWorldPath(LevelResource.GENERATED_DIR).normalize();
			this.manager = server.getStructureTemplateManager();
			this.resources = server.getResourceManager();
			this.worldPaths = new TemplatePathFactory(generated);
			this.world = new DirectoryTemplateSource(fixer, blocks, generated, TEMPLATE_FILES, false);
			Path gameTestDir = StructureUtils.testStructuresSourceDir;
			this.gameTests = gameTestDir == null ? null : new DirectoryTemplateSource(fixer, blocks, gameTestDir, PackType.SERVER_DATA,
				StructureTemplateManager.RESOURCE_TEXT_STRUCTURE_LISTER, true);
		}

		/**
		 * The first source that reads the template, as the game's manager picks it.
		 *
		 * @throws TooLargeException if the file found is over {@code limits} or another limit on imported files
		 */
		Optional<Loaded> load(Identifier id, ReadLimits limits) throws TooLargeException {
			Optional<Path> worldFile = worldFile(id);
			if (worldFile.isPresent()) {
				Optional<CompoundTag> structure = readOrSkip(id, () -> limits.nbt().read(worldFile.get()), limits.maxPositions());
				if (structure.isPresent()) {
					return Optional.of(new Loaded(structure.get(), new SavedInWorld()));
				}
			}

			if (gameTests != null) {
				Optional<StructureTemplate> fromTests = gameTests.load(id);
				if (fromTests.isPresent()) {
					return Optional.of(new Loaded(fromTests.get().save(new CompoundTag()), new GameTestFiles()));
				}
			}

			Optional<Resource> packFile = resources.getResource(TEMPLATE_FILES.idToFile(id));
			if (packFile.isPresent()) {
				Optional<CompoundTag> structure = readOrSkip(id, () -> {
					try (InputStream in = packFile.get().open()) {
						return limits.nbt().read(in);
					}
				}, limits.maxPositions());
				if (structure.isPresent()) {
					return Optional.of(new Loaded(structure.get(), new Pack(packFile.get().sourcePackId())));
				}
			}

			// Mods can add sources to the manager (Fabric's game test API does); ask it for the few ids none of these has.
			return manager.get(id).map(template -> new Loaded(template.save(new CompoundTag()), new ManagerOnly()));
		}

		/**
		 * Reads a template file and upgrades it, or empty if it can't be read (logged). A file over the limits is an error
		 * rather than skipped: the game would read it. Its box is checked against {@code maxPositions} before the fixers
		 * run, so a template the index will retry with more room isn't fixed twice.
		 */
		private static Optional<CompoundTag> readOrSkip(Identifier id, IoSupplier<CompoundTag> file, long maxPositions) throws TooLargeException {
			try {
				CompoundTag structure = file.get();
				ListTag size = structure.getListOrEmpty("size");
				Vec3i box = new Vec3i(size.getIntOr(0, 0), size.getIntOr(1, 0), size.getIntOr(2, 0));
				TooLargeException.requireAtMost(BlockVolume.requireFits(box), maxPositions, "positions");
				return Optional.of(StructureNbt.upgrade(structure, StructureNbt.dataVersion(structure)));
			} catch (TooLargeException | CancellationException e) {
				throw e;
			} catch (IOException | RuntimeException e) {
				ClaudeChatClient.LOGGER.warn("Could not read structure template {}: {}", id, e.toString());
				return Optional.empty();
			}
		}

		private Optional<Path> worldFile(Identifier id) {
			try {
				return Optional.of(worldPaths.createAndValidatePathToStructure(id, TEMPLATE_FILES)).filter(Files::isRegularFile);
			} catch (RuntimeException invalidPath) {
				return Optional.empty();
			}
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
	}
}
