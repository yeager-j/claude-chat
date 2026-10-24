package claudechat;

import claudechat.mixin.FeaturePoolElementAccessor;
import claudechat.mixin.TemplateStructurePieceAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.pools.FeaturePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.ListPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Finds many structures, not just the nearest one like /locate. Uses the same search as /locate: walk the structure's
 * placement grid outward in rings and ask the game, from the seed and biomes and the structure starts already saved,
 * whether the structure starts in each candidate chunk. Unlike /locate it never generates a chunk to find out: where
 * nothing is saved yet, it builds the structure in memory exactly as generation would. Each hit gets its pieces (from
 * the saved start where there is one), the biomes they sit in, and how long players have spent nearby, so callers can
 * skip structures the player has already visited.
 * <p>
 * The search runs on the server thread, which owns the game's structure caches, in short slices so the game keeps
 * ticking; a search that runs too long is stopped.
 */
public final class StructureFinder {
	/** Spacing of the biome samples inside each piece, plus its corners. Biomes are stored per 4 blocks. */
	private static final int SAMPLE_STEP = 8;
	private static final int MAX_SAMPLES = 20_000;
	/** Sample positions visited per structure, counting ones already sampled where pieces overlap. */
	private static final int MAX_SAMPLE_VISITS = 200_000;
	private static final int MAX_CHECKS = 60_000;
	private static final int MAX_RINGS = 200;
	/** How far (in chunks) a structure's pieces can reach from its start chunk: jigsaw structures are capped at 128 blocks. */
	private static final int PIECE_REACH = 8;
	/*
	 * Limits on a start read from the world, whose pieces' boxes come from the save as they are: a crafted world could
	 * otherwise make one structure's loops below span the whole world on the server thread. Far beyond any real
	 * structure: jigsaw pieces stay within 128 blocks of the start, and vanilla's largest starts have a few hundred pieces.
	 */
	/** Pieces in a saved start. */
	static final int MAX_SAVED_PIECES = 4096;
	/** Blocks a saved piece may reach from its start chunk across, and above or below the world's height. */
	static final int MAX_SAVED_REACH = 512;
	/** Chunks a saved start's pieces cover, each piece's counted on its own (overlaps count again). */
	static final long MAX_SAVED_CHUNK_COVER = 65_536;
	/** How long the search may hold the server thread at a time, and how long it may take in all. */
	private static final Duration SLICE = Duration.ofMillis(15);
	private static final Duration SLICE_TIMEOUT = Duration.ofSeconds(20);
	private static final Duration TIMEOUT = Duration.ofSeconds(110);

	private StructureFinder() {
	}

	/**
	 * Where a structure's pieces actually are, and which biomes they sit in: {@code biomeSamples} counts sampled
	 * 4×4×4 biome cells inside the pieces per biome id. Pieces, not the overall bounding box, because sprawling
	 * structures like fortresses leave most of their box empty.
	 */
	public record Footprint(BoundingBox bounds, Map<String, Integer> biomeSamples, int totalSamples) {
		/** Percent of the sampled cells in any of these biomes. */
		public double share(Set<String> biomes) {
			if (totalSamples == 0) {
				return 0;
			}
			int matching = biomeSamples.entrySet().stream().filter(e -> biomes.contains(e.getKey())).mapToInt(Map.Entry::getValue).sum();
			return 100.0 * matching / totalSamples;
		}
	}

	/**
	 * A structure found: its /locate position, its start with every piece (as saved, or as generation would build it),
	 * the most time players have spent near any of its chunks, and its footprint.
	 */
	public record Found(String structure, int x, int z, double distance, Visit visit, StructureStart start, Footprint footprint) {
		/** The pieces by name with their counts; see {@link StructureFinder#pieces}. */
		public Map<String, Integer> pieces() {
			return StructureFinder.pieces(start);
		}
	}

	/**
	 * What to look for. {@code pieceTerms} must each be part of some piece's name ("ship" matches "end_city/ship");
	 * with {@code biomes}, some piece must be in one of them and at least {@code minBiomeShare} percent of the
	 * structure in them combined.
	 */
	public record Request(List<Holder<Structure>> structures, int minDistance, int maxDistance, int limit, Visit.Filter visited,
		List<String> pieceTerms, Set<String> biomes, int minBiomeShare) {
		boolean hasPieces(Set<String> pieceNames) {
			return pieceTerms.stream().allMatch(term -> pieceNames.stream().anyMatch(name -> name.contains(term)));
		}

		boolean inBiomes(Footprint footprint) {
			double share = footprint.share(biomes);
			return share > 0 && share >= minBiomeShare;
		}
	}

	/**
	 * The structures nearest {@code origin} (in {@code level}) that pass the request, nearest first. Not on the server
	 * thread: it hands the server short slices of work and waits. {@code snapshot} gives how long players spent near
	 * each chunk, and the saved structure starts.
	 */
	public static List<Found> find(ServerLevel level, Origin origin, Request request, WorldIndex.Snapshot snapshot) throws Exception {
		MinecraftServer server = level.getServer();
		ServerTasks.requireOffServerThread(server);
		if (!origin.dimension().equals(level.dimension())) {
			throw new IllegalArgumentException("The origin is in " + origin.dimension().identifier() + ", not " + level.dimension().identifier());
		}

		Search search = ServerTasks.call(server, () -> new Search(level, origin, request, snapshot), SLICE_TIMEOUT, "Starting the structure search");
		long deadline = System.nanoTime() + TIMEOUT.toNanos();
		while (!ServerTasks.call(server, search::advance, SLICE_TIMEOUT, "The structure search")) {
			if (System.nanoTime() > deadline) {
				throw new IllegalStateException("The structure search took longer than " + TIMEOUT.toSeconds()
					+ " s and was stopped. Try a smaller max_distance, or fewer structure types.");
			}
			if (Thread.interrupted()) {
				throw new InterruptedException("The structure search was cancelled.");
			}
		}
		return search.results();
	}

	/** The pieces by name with their counts, sorted by name, e.g. {"end_city/ship": 1, "end_city/fat_tower_top": 2}. Empty for null. */
	public static Map<String, Integer> pieces(StructureStart start) {
		Map<String, Integer> pieces = new TreeMap<>();
		if (start != null) {
			for (StructurePiece piece : start.getPieces()) {
				pieces.merge(pieceName(piece), 1, Integer::sum);
			}
		}
		return pieces;
	}

	/**
	 * A readable name for a piece: its template ("end_city/ship", "village/plains/houses/plains_temple_3"), the placed
	 * feature it puts down ("feature/pile_hay"), or for hand-coded pieces, its class ("stronghold/library",
	 * "nether_fortress/monster_throne").
	 */
	public static String pieceName(StructurePiece piece) {
		if (piece instanceof PoolElementStructurePiece pool) {
			return elementName(pool.getElement());
		}
		if (piece instanceof TemplateStructurePiece template) {
			try {
				return ToolFormat.id(((TemplateStructurePieceAccessor) template).claudechat$templateLocation());
			} catch (RuntimeException e) {
				// a modded piece with an unusual template name; fall back to its class
			}
		}
		return className(piece.getClass());
	}

	private static String elementName(StructurePoolElement element) {
		if (element instanceof SinglePoolElement single) {
			try {
				return ToolFormat.id(single.getTemplateLocation());
			} catch (RuntimeException e) {
				return "inline_template"; // built from a template object rather than a file
			}
		}
		if (element instanceof FeaturePoolElement feature) {
			return "feature/" + ((FeaturePoolElementAccessor) feature).claudechat$feature().unwrapKey()
				.map(key -> ToolFormat.id(key.identifier())).orElse("unnamed");
		}
		if (element instanceof ListPoolElement list) {
			return String.join("+", list.getElements().stream().map(StructureFinder::elementName).toList());
		}
		return className(element.getClass());
	}

	/**
	 * NetherFortressPieces.MonsterThrone → "nether_fortress/monster_throne". A start piece that extends another piece
	 * (a fortress's StartPiece is a BridgeCrossing, a stronghold's a StairsDown) takes that name: it's the same room,
	 * and it's what a saved structure reloads as.
	 */
	static String className(Class<?> type) {
		Class<?> parent = type.getSuperclass();
		while (parent != null && parent.getEnclosingClass() != null && parent.getEnclosingClass() == type.getEnclosingClass()
			&& !Modifier.isAbstract(parent.getModifiers())) {
			type = parent;
			parent = type.getSuperclass();
		}
		String name = snakeCase(type.getSimpleName().isEmpty() ? type.getName().replaceAll(".*[.$]", "") : type.getSimpleName());
		Class<?> outer = type.getEnclosingClass();
		return outer == null ? name : snakeCase(outer.getSimpleName().replaceFirst("Pieces$", "")) + "/" + name;
	}

	private static String snakeCase(String camel) {
		return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
	}

	/**
	 * Why a stored start's piece boxes are not a structure's, or null if they could be: every box the right way round,
	 * within {@link #MAX_SAVED_REACH} of the start chunk and of the world's height, and together covering at most
	 * {@link #MAX_SAVED_CHUNK_COVER} chunks. Checked before anything loops over the boxes on the server thread.
	 */
	static String savedGeometryProblem(List<BoundingBox> boxes, ChunkPos start, int minY, int maxY) {
		if (boxes.size() > MAX_SAVED_PIECES) {
			return boxes.size() + " pieces, more than " + MAX_SAVED_PIECES;
		}
		long west = (long) start.getMinBlockX() - MAX_SAVED_REACH;
		long east = (long) start.getMinBlockX() + 15 + MAX_SAVED_REACH;
		long north = (long) start.getMinBlockZ() - MAX_SAVED_REACH;
		long south = (long) start.getMinBlockZ() + 15 + MAX_SAVED_REACH;
		long bottom = (long) minY - MAX_SAVED_REACH;
		long top = (long) maxY + MAX_SAVED_REACH;
		long chunks = 0;
		for (BoundingBox box : boxes) {
			if (box.minX() > box.maxX() || box.minY() > box.maxY() || box.minZ() > box.maxZ()) {
				return "a piece's box is inside out: " + box;
			}
			if (box.minX() < west || box.maxX() > east || box.minZ() < north || box.maxZ() > south
				|| box.minY() < bottom || box.maxY() > top) {
				return "a piece reaches more than " + MAX_SAVED_REACH + " blocks from its start: " + box;
			}
			chunks += ((long) SectionPos.blockToSectionCoord(box.maxX()) - SectionPos.blockToSectionCoord(box.minX()) + 1)
				* ((long) SectionPos.blockToSectionCoord(box.maxZ()) - SectionPos.blockToSectionCoord(box.minZ()) + 1);
			if (chunks > MAX_SAVED_CHUNK_COVER) {
				return "its pieces cover more than " + MAX_SAVED_CHUNK_COVER + " chunks";
			}
		}
		return null;
	}

	/** min, min + step, ..., and always max, so a piece's corners are always sampled. */
	static int[] steps(int min, int max) {
		if (min > max) {
			throw new IllegalArgumentException("min " + min + " > max " + max);
		}
		int count = (int) (((long) max - min) / SAMPLE_STEP + 1);
		int[] steps = new int[count + 1];
		for (int i = 0; i < count; i++) {
			steps[i] = min + i * SAMPLE_STEP;
		}
		steps[count] = max;
		return steps;
	}

	// --- the search -------------------------------------------------------------------------------------------------

	/** One structure that may start in one chunk, with what's been worked out about it so far. */
	private static final class Candidate {
		final Holder<Structure> structure;
		final String id;
		final StructurePlacement placement;
		final StructureSet set;
		final ChunkPos chunk;
		final BlockPos locatePos;
		final double distance;
		StructureStart start;
		Visit visit;
		Footprint footprint;

		Candidate(Holder<Structure> structure, StructurePlacement placement, StructureSet set, ChunkPos chunk, Origin origin) {
			this.structure = structure;
			this.id = RegistryQuery.id(structure);
			this.placement = placement;
			this.set = set;
			this.chunk = chunk;
			this.locatePos = placement.getLocatePos(chunk);
			this.distance = origin.horizontalDistanceTo(locatePos.getX(), locatePos.getZ());
		}
	}

	/** The structures of one structure set that the request asks for; they share its placement. */
	private record Group(StructureSet set, List<Holder<Structure>> structures) {
	}

	/** A search in progress. Server thread only: {@link #advance} does a slice of it. */
	private static final class Search {
		private final ServerLevel level;
		private final Origin origin;
		private final Request request;
		private final WorldIndex.Snapshot snapshot;
		private final Visit.ByChunk visits;
		private final ChunkGeneratorStructureState generatorState;
		private final StructureManager structureManager;
		private final BiomeResolver biomes;
		private final List<Group> spread = new ArrayList<>();
		private final int minSpacing;
		private final int maxRing;

		private final Deque<Candidate> pending = new ArrayDeque<>();
		private final Set<String> seen = new HashSet<>();
		private final NearestFirst.TopK<Candidate> nearest;
		/** What generation would place for a structure set in a chunk: one per set and chunk, shared by its structures. */
		private final Map<StructureSet, Map<Long, Optional<StructureStart>>> generated = new IdentityHashMap<>();
		private int ring = -1;
		private int checks;
		private List<Candidate> results;
		private int footprintsDone;

		Search(ServerLevel level, Origin origin, Request request, WorldIndex.Snapshot snapshot) {
			ServerTasks.requireServerThread(level.getServer());
			this.level = level;
			this.origin = origin;
			this.request = request;
			this.snapshot = snapshot;
			this.visits = snapshot.visits(level.dimension());
			this.generatorState = level.getChunkSource().getGeneratorState();
			this.structureManager = level.structureManager();
			this.biomes = level.getChunkSource().getGenerator().getBiomeSource().createCachingResolver(generatorState.randomState());
			this.nearest = new NearestFirst.TopK<>(request.limit(), request.maxDistance());

			for (Group group : groups()) {
				if (group.set().placement() instanceof ConcentricRingsStructurePlacement rings) {
					List<ChunkPos> positions = generatorState.getRingPositionsFor(rings);
					if (positions != null) {
						positions.forEach(chunk -> addCandidates(group, chunk));
					}
				} else if (group.set().placement() instanceof RandomSpreadStructurePlacement) {
					spread.add(group);
				}
			}
			this.minSpacing = spread.stream().mapToInt(group -> spacing(group)).min().orElse(1);
			this.maxRing = Math.min(MAX_RINGS, request.maxDistance() / (minSpacing * 16) + 2);
		}

		/** The structure sets this level generates that include a requested structure. */
		private List<Group> groups() {
			Set<Structure> requested = Collections.newSetFromMap(new IdentityHashMap<>());
			request.structures().forEach(structure -> requested.add(structure.value()));
			List<Group> groups = new ArrayList<>();
			for (Holder<StructureSet> set : generatorState.possibleStructureSets()) {
				List<Holder<Structure>> wanted = set.value().structures().stream().map(StructureSet.StructureSelectionEntry::structure)
					.filter(structure -> requested.contains(structure.value())).toList();
				if (!wanted.isEmpty()) {
					groups.add(new Group(set.value(), wanted));
				}
			}
			return groups;
		}

		private static int spacing(Group group) {
			return ((RandomSpreadStructurePlacement) group.set().placement()).spacing();
		}

		/** Works until the slice is used up; true once the search is complete. */
		boolean advance() {
			ServerTasks.requireServerThread(level.getServer());
			long sliceEnd = System.nanoTime() + SLICE.toNanos();
			while (System.nanoTime() < sliceEnd) {
				if (results != null) {
					if (footprintsDone == results.size()) {
						return true;
					}
					Candidate result = results.get(footprintsDone++);
					footprint(result);
				} else if (!pending.isEmpty()) {
					check(pending.poll());
				} else if (!nextRing()) {
					results = nearest.sorted();
				}
			}
			return false;
		}

		List<Found> results() {
			return results.stream().map(c -> new Found(c.id, c.locatePos.getX(), c.locatePos.getZ(), c.distance, c.visit, c.start, c.footprint)).toList();
		}

		/** Queues the next ring of placement cells; false when the search is over. */
		private boolean nextRing() {
			// Ring r + 1's cells are at least r cells from the origin's: nothing there can beat a full list closer than that.
			if (ring >= 0 && nearest.settled((double) ring * minSpacing * 16)) {
				return false;
			}
			ring++;
			if (ring > maxRing || checks >= MAX_CHECKS || spread.isEmpty()) {
				return false;
			}

			int originX = SectionPos.blockToSectionCoord(origin.blockPos().getX());
			int originZ = SectionPos.blockToSectionCoord(origin.blockPos().getZ());
			long seed = generatorState.getLevelSeed();
			for (Group group : spread) {
				RandomSpreadStructurePlacement placement = (RandomSpreadStructurePlacement) group.set().placement();
				int spacing = placement.spacing();
				if ((long) (ring - 1) * spacing * 16 > request.maxDistance()) {
					continue;
				}
				NearestFirst.forEachInRing(ring, (dx, dz) ->
					addCandidates(group, placement.getPotentialStructureChunk(seed, originX + spacing * dx, originZ + spacing * dz)));
			}
			return true;
		}

		private void addCandidates(Group group, ChunkPos chunk) {
			for (Holder<Structure> structure : group.structures()) {
				Candidate candidate = new Candidate(structure, group.set().placement(), group.set(), chunk, origin);
				if (seen.add(candidate.id + "@" + chunk.x() + "," + chunk.z())) {
					pending.add(candidate);
				}
			}
		}

		/** Cheapest checks first: pieces and footprints are built only for candidates that could make the list. */
		private void check(Candidate c) {
			if (checks++ >= MAX_CHECKS || c.distance < request.minDistance() || !nearest.couldAccept(c.distance)) {
				return;
			}
			if (request.visited() == Visit.Filter.VISITED && !nearGeneratedLand(c.chunk)) {
				return;
			}
			// Same check /locate uses: decided from the saved structure starts, else from the seed and biomes.
			StructureCheckResult presence = structureManager.checkStructurePresence(c.chunk, c.structure.value(), c.placement, false);
			if (presence == StructureCheckResult.START_NOT_PRESENT) {
				return;
			}
			Optional<StructureStart> start = presence == StructureCheckResult.START_PRESENT
				? storedStart(c).or(() -> generatedStart(c))
				: generatedStart(c);
			if (start.isEmpty()) {
				return;
			}

			c.start = start.get();
			c.visit = timeNearby(c);
			if (!request.visited().accepts(c.visit) || !request.hasPieces(pieces(c.start).keySet())) {
				return;
			}
			if (!request.biomes().isEmpty() && !request.inBiomes(footprint(c))) {
				return;
			}
			nearest.offer(c, c.distance);
		}

		/**
		 * Whether any chunk the structure's pieces could reach is generated. If none is, nobody has been there, so a
		 * search for visited structures can skip it without building its pieces.
		 */
		private boolean nearGeneratedLand(ChunkPos chunk) {
			for (int dx = -PIECE_REACH; dx <= PIECE_REACH; dx++) {
				for (int dz = -PIECE_REACH; dz <= PIECE_REACH; dz++) {
					if (visits.at(chunk.x() + dx, chunk.z() + dz).generated()) {
						return true;
					}
				}
			}
			return false;
		}

		/** The start as the game has it: in the live chunk, else in the save; empty if neither has it. */
		private Optional<StructureStart> storedStart(Candidate c) {
			var live = LiveChunks.at(level, c.chunk.x(), c.chunk.z());
			if (live.isPresent()) {
				// A live chunk's start was loaded from the same save, so it gets the same checks.
				return Optional.ofNullable(live.get().getStartForStructure(c.structure.value())).filter(StructureStart::isValid)
					.filter(start -> plausible(c, start));
			}
			try {
				WorldIndex.ChunkKey key = new WorldIndex.ChunkKey(level.dimension(), c.chunk.x(), c.chunk.z());
				Identifier id = c.structure.unwrapKey().orElseThrow().identifier();
				return snapshot.readSavedChunk(key).flatMap(chunk -> chunk.structureStart(id))
					.filter(tag -> tag.getListOrEmpty("Children").size() <= MAX_SAVED_PIECES)
					.map(tag -> StructureStart.loadStaticStart(StructurePieceSerializationContext.fromLevel(level), tag, generatorState.getLevelSeed()))
					.filter(StructureStart::isValid)
					.filter(start -> plausible(c, start));
			} catch (IOException | RuntimeException e) {
				ClaudeChatClient.LOGGER.debug("Could not read the saved start of {} at {}", c.id, c.chunk, e);
				return Optional.empty();
			}
		}

		/** Whether a stored start's pieces fit {@link #savedGeometryProblem}; logs the ones that don't. */
		private boolean plausible(Candidate c, StructureStart start) {
			List<BoundingBox> boxes = start.getPieces().stream().map(StructurePiece::getBoundingBox).toList();
			String problem = savedGeometryProblem(boxes, c.chunk, level.getMinY(), level.getMaxY());
			if (problem != null) {
				ClaudeChatClient.LOGGER.warn("Skipping the saved start of {} at {}: {}", c.id, c.chunk, problem);
			}
			return problem == null;
		}

		/**
		 * The start generation would make here, built in memory without generating or saving anything; empty if it
		 * would place nothing, or another structure of the same set (a bastion instead of a fortress).
		 */
		private Optional<StructureStart> generatedStart(Candidate c) {
			Optional<StructureStart> start = generated.computeIfAbsent(c.set, set -> new HashMap<>())
				.computeIfAbsent(c.chunk.pack(), chunk -> generateForSet(c.set, c.chunk));
			return start.filter(s -> s.getStructure() == c.structure.value());
		}

		/** What ChunkGenerator.createStructures places for one structure set: the first valid one in its weighted draw. */
		private Optional<StructureStart> generateForSet(StructureSet set, ChunkPos chunk) {
			if (!set.placement().isStructureChunk(generatorState, chunk.x(), chunk.z())) {
				return Optional.empty();
			}
			List<StructureSet.StructureSelectionEntry> options = new ArrayList<>(set.structures());
			if (options.size() == 1) {
				return generate(options.getFirst().structure(), chunk);
			}

			WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
			random.setLargeFeatureSeed(generatorState.getLevelSeed(), chunk.x(), chunk.z());
			int total = options.stream().mapToInt(StructureSet.StructureSelectionEntry::weight).sum();
			while (!options.isEmpty()) {
				int choice = random.nextInt(total);
				int index = 0;
				for (StructureSet.StructureSelectionEntry option : options) {
					choice -= option.weight();
					if (choice < 0) {
						break;
					}
					index++;
				}
				StructureSet.StructureSelectionEntry selected = options.get(index);
				Optional<StructureStart> start = generate(selected.structure(), chunk);
				if (start.isPresent()) {
					return start;
				}
				options.remove(index);
				total -= selected.weight();
			}
			return Optional.empty();
		}

		/** One structure's start and pieces as generation builds them (the same {@link Structure#generate} call), in memory. */
		private Optional<StructureStart> generate(Holder<Structure> holder, ChunkPos chunk) {
			ChunkGenerator generator = level.getChunkSource().getGenerator();
			RandomState randomState = generatorState.randomState();
			Structure structure = holder.value();
			StructureStart start = structure.generate(holder, level.dimension(), level.registryAccess(), generator,
				generator.getBiomeSource(), randomState.createClimateSampler(SamplerContext.builder().enableCaches().build()), randomState,
				level.getServer().getStructureTemplateManager(), generatorState.getLevelSeed(), chunk, 0, level,
				structure.biomes()::contains);
			return start.isValid() ? Optional.of(start) : Optional.empty();
		}

		/**
		 * How long players have spent near the structure: the most of any chunk its pieces cover. The start chunk
		 * alone isn't enough: a chunk only gains time while a player is within 128 blocks of it, and an end city's
		 * ship or a fortress's far bridges can be farther than that from its start.
		 */
		private Visit timeNearby(Candidate c) {
			Visit most = visits.at(c.chunk.x(), c.chunk.z());
			Set<Long> counted = new HashSet<>();
			for (StructurePiece piece : c.start.getPieces()) {
				BoundingBox box = piece.getBoundingBox();
				for (int cx = SectionPos.blockToSectionCoord(box.minX()); cx <= SectionPos.blockToSectionCoord(box.maxX()); cx++) {
					for (int cz = SectionPos.blockToSectionCoord(box.minZ()); cz <= SectionPos.blockToSectionCoord(box.maxZ()); cz++) {
						if (counted.add(ChunkPos.pack(cx, cz))) {
							most = most.max(visits.at(cx, cz));
						}
					}
				}
			}
			return most;
		}

		/**
		 * Where the pieces are and which biomes they sit in. Biomes come from the seed, which matches what generation
		 * stores (worlds generated by older versions can differ).
		 */
		private Footprint footprint(Candidate c) {
			if (c.footprint != null) {
				return c.footprint;
			}
			Map<String, Integer> counts = new HashMap<>();
			Set<Long> sampled = new HashSet<>();
			int visits = 0;
			pieces:
			for (StructurePiece piece : c.start.getPieces()) {
				BoundingBox box = piece.getBoundingBox();
				for (int x : steps(box.minX(), box.maxX())) {
					for (int y : steps(box.minY(), box.maxY())) {
						for (int z : steps(box.minZ(), box.maxZ())) {
							if (sampled.size() >= MAX_SAMPLES || visits++ >= MAX_SAMPLE_VISITS) {
								break pieces;
							}
							int qx = QuartPos.fromBlock(x);
							int qy = QuartPos.fromBlock(y);
							int qz = QuartPos.fromBlock(z);
							if (sampled.add(BlockPos.asLong(qx, qy, qz))) {
								counts.merge(RegistryQuery.id(biomes.getNoiseBiome(qx, qy, qz)), 1, Integer::sum);
							}
						}
					}
				}
			}
			c.footprint = new Footprint(c.start.getBoundingBox(), counts, sampled.size());
			return c.footprint;
		}
	}
}
