package claudechat;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * The game's recipes, by id and by what they make: the one place recipe grids and material lists look recipes up.
 * <p>
 * In a single-player world they are the integrated server's {@link RecipeManager}'s, so the world's enabled packs,
 * their order and their load conditions all count. Without a server (on a multiplayer server, whose recipes 26.x
 * clients don't receive, only recipe-book displays) they are read from the knowledge base's data files, highest
 * priority first, with the game's own recipe codec; Fabric load conditions aren't checked there. Those files come from
 * every installed mod, anyone's download, so they're read within {@link Limits}; what a limit leaves out is missing
 * from the snapshot, which says it's incomplete.
 */
public final class GameRecipes {
	/**
	 * Every recipe by id, and each item's main recipe. Immutable.
	 *
	 * @param complete false when a {@link Limits limit} stopped the recipe files from being read in full
	 */
	public record Snapshot(Map<Identifier, GameRecipe> byId, Map<Identifier, GameRecipe> mainByItem, boolean complete) {
		static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), true);

		static Snapshot of(Collection<GameRecipe> recipes) {
			return of(recipes, true);
		}

		static Snapshot of(Collection<GameRecipe> recipes, boolean complete) {
			Map<Identifier, GameRecipe> byId = new HashMap<>();
			Map<Identifier, List<GameRecipe>> byItem = new HashMap<>();

			for (GameRecipe recipe : recipes) {
				byId.put(recipe.id(), recipe);
				recipe.resultItem()
					.flatMap(item -> item.unwrapKey())
					.ifPresent(item -> byItem.computeIfAbsent(item.identifier(), key -> new ArrayList<>()).add(recipe));
			}

			Map<Identifier, GameRecipe> mainByItem = byItem.entrySet().stream()
				.collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> GameRecipes.mainRecipe(entry.getKey(), entry.getValue())));
			return new Snapshot(Map.copyOf(byId), mainByItem, complete);
		}

		public Optional<GameRecipe> byId(Identifier id) {
			return Optional.ofNullable(byId.get(id));
		}

		/** The recipe the item is usually made with; see {@link GameRecipes#mainRecipe(Identifier, Collection)}. */
		public Optional<GameRecipe> mainRecipe(Identifier item) {
			return Optional.ofNullable(mainByItem.get(item));
		}

		/**
		 * What [[recipe:id]] means: the recipe with that id, or else the main recipe for the item with that id (recipes
		 * are named freely: iron_ingot_from_smelting_raw_iron).
		 */
		public Optional<GameRecipe> find(Identifier id) {
			return byId(id).or(() -> mainRecipe(id));
		}
	}

	/**
	 * How much of the recipe files one snapshot may read: entries walked (folders and files), folder depth under a
	 * recipe folder, bytes per file and in all, JSON nesting, and time. Vanilla has over a thousand recipe files of a
	 * few hundred bytes each, and big modpacks some tens of thousands.
	 */
	record Limits(int entries, int depth, int fileBytes, long totalBytes, int jsonDepth, Duration time) {
		static final Limits DEFAULT = new Limits(100_000, 16, 256 * 1024, 64L * 1024 * 1024, 64, Duration.ofSeconds(10));
	}

	/** What a snapshot was read from: the server's recipe manager, or the client's registries and the files' generation. */
	private record Basis(Object recipes, int generation) {
	}

	private final KnowledgeBase knowledge;
	private volatile RecipeManager server;
	private Snapshot snapshot = Snapshot.EMPTY;
	private Basis basis;

	public GameRecipes(KnowledgeBase knowledge) {
		this.knowledge = knowledge;
	}

	/** Serves the integrated server's recipes. Call on the server thread when a world opens and after each reload. */
	public void useServer(RecipeManager recipes) {
		server = recipes;
	}

	/** The world closed: back to the knowledge base's files. */
	public void clearServer() {
		server = null;
	}

	/**
	 * The current recipes, from any thread. The first call after the recipes change reads them all (thousands with
	 * mods; from files, within {@link Limits}); later calls return the same snapshot.
	 */
	public synchronized Snapshot snapshot() {
		RecipeManager manager = server;

		if (manager != null) {
			return snapshotOf(new Basis(manager, 0), () -> fromServer(manager));
		}

		ClientPacketListener connection = Minecraft.getInstance().getConnection();

		if (connection == null) {
			return Snapshot.EMPTY;
		}

		HolderLookup.Provider registries = connection.registryAccess();
		return snapshotOf(new Basis(registries, knowledge.generation()), () -> fromFiles(knowledge.dataRoots(),
			registries.createSerializationContext(JsonOps.INSTANCE), Limits.DEFAULT));
	}

	/**
	 * Which of an item's recipes is its main one: the recipe named after the item (minecraft:piston), then crafting
	 * recipes before smelting and the like, then the lowest id. The same answer whatever order recipes were read in.
	 */
	static GameRecipe mainRecipe(Identifier item, Collection<GameRecipe> candidates) {
		Comparator<GameRecipe> mainFirst = Comparator.comparing((GameRecipe recipe) -> !recipe.id().equals(item))
			.thenComparing(recipe -> !recipe.isCrafting())
			.thenComparing(GameRecipe::id);
		return candidates.stream().min(mainFirst).orElseThrow();
	}

	/**
	 * The recipe files under data roots in priority order, by recipe id: a higher-priority pack's
	 * data/minecraft/recipe/piston.json replaces Minecraft's, the way the game loads them.
	 */
	static Map<Identifier, Path> recipeFiles(List<Path> dataRoots) {
		return new FileReader(Limits.DEFAULT).recipeFiles(dataRoots);
	}

	/** A recipe's JSON read with the game's own codec, so ingredients mean what they mean to the game. Empty if it doesn't decode. */
	static Optional<GameRecipe> parse(Identifier id, JsonElement json, DynamicOps<JsonElement> registryOps) {
		Optional<Recipe<?>> recipe = Recipe.DIRECT_CODEC.parse(registryOps, json)
			.resultOrPartial(error -> ClaudeChatClient.LOGGER.debug("Skipping recipe {}: {}", id, error));
		return recipe.map(decoded -> GameRecipe.of(id, decoded));
	}

	private Snapshot snapshotOf(Basis current, Supplier<Snapshot> read) {
		if (!current.equals(basis)) {
			long start = System.nanoTime();
			Snapshot fresh = read.get();
			ClaudeChatClient.LOGGER.info("Read {} recipes in {} ms", fresh.byId().size(), (System.nanoTime() - start) / 1_000_000);

			// An interrupted read stopped early for this caller only; the next one reads them again.
			if (Thread.currentThread().isInterrupted()) {
				return fresh;
			}

			snapshot = fresh;
			basis = current;
		}

		return snapshot;
	}

	/** The server's recipes. Its recipe map and recipes don't change once loaded; a reload makes a new manager. */
	private static Snapshot fromServer(RecipeManager manager) {
		List<GameRecipe> recipes = new ArrayList<>();

		for (var holder : manager.getRecipes()) {
			Identifier id = holder.id().identifier();

			try {
				recipes.add(GameRecipe.of(id, holder.value()));
			} catch (RuntimeException e) {
				ClaudeChatClient.LOGGER.debug("Skipping recipe {}", id, e);
			}
		}

		return Snapshot.of(recipes);
	}

	/** The recipes in the data roots' files, as far as {@code limits} allow. Stops early, incomplete, if interrupted. */
	static Snapshot fromFiles(List<Path> dataRoots, DynamicOps<JsonElement> ops, Limits limits) {
		FileReader reader = new FileReader(limits);
		List<GameRecipe> recipes = new ArrayList<>();

		for (Map.Entry<Identifier, Path> file : reader.recipeFiles(dataRoots).entrySet()) {
			if (reader.stopped()) {
				break;
			}

			reader.read(file.getValue()).flatMap(json -> parse(file.getKey(), json, ops)).ifPresent(recipes::add);
		}

		if (!reader.limitsHit.isEmpty()) {
			ClaudeChatClient.LOGGER.warn("Read only part of the recipe files: stopped by {}", reader.limitsHit);
		}

		return Snapshot.of(recipes, reader.limitsHit.isEmpty());
	}

	/** Which {@link Limits} a read ran into. */
	enum Limit {
		ENTRIES, DEPTH, FILE_SIZE, TOTAL_SIZE, JSON, TIME, INTERRUPTED
	}

	/** Lists and reads one snapshot's recipe files, counting everything against one set of {@link Limits}. */
	static final class FileReader {
		private final Limits limits;
		private final long deadline;
		/** The limits run into so far; any of them makes the snapshot incomplete. */
		final EnumSet<Limit> limitsHit = EnumSet.noneOf(Limit.class);
		private int entries;
		private long bytes;

		FileReader(Limits limits) {
			this.limits = limits;
			this.deadline = System.nanoTime() + limits.time().toNanos();
		}

		/**
		 * Whether reading must stop: out of bytes or time, or interrupted. A skipped file doesn't stop it, and running
		 * out of entries only ends the walk: the files it found are still read.
		 */
		boolean stopped() {
			if (Thread.currentThread().isInterrupted()) {
				limitsHit.add(Limit.INTERRUPTED);
			} else if (System.nanoTime() - deadline > 0) {
				limitsHit.add(Limit.TIME);
			}

			return limitsHit.contains(Limit.INTERRUPTED) || limitsHit.contains(Limit.TIME) || limitsHit.contains(Limit.TOTAL_SIZE);
		}

		/** Counts one folder entry; false once the walk must end. */
		private boolean count() {
			if (++entries > limits.entries()) {
				limitsHit.add(Limit.ENTRIES);
				return false;
			}

			return !stopped();
		}

		/** Like {@link GameRecipes#recipeFiles}, but within the limits; links (and junctions) aren't followed. */
		Map<Identifier, Path> recipeFiles(List<Path> dataRoots) {
			Map<Identifier, Path> files = new LinkedHashMap<>();

			for (Path data : dataRoots) {
				if (stopped() || limitsHit.contains(Limit.ENTRIES)) {
					break;
				}

				try (DirectoryStream<Path> namespaces = Files.newDirectoryStream(data)) {
					for (Path namespace : namespaces) {
						if (!count()) {
							break;
						}

						Path recipeDir = namespace.resolve("recipe");

						if (KnowledgeFiles.isPlainDirectory(recipeDir)) {
							walk(namespace, recipeDir, files);
						}
					}
				} catch (IOException e) {
					ClaudeChatClient.LOGGER.debug("Could not list recipes in {}", data, e);
				}
			}

			return files;
		}

		private void walk(Path namespace, Path recipeDir, Map<Identifier, Path> files) throws IOException {
			Files.walkFileTree(recipeDir, EnumSet.noneOf(FileVisitOption.class), limits.depth(), new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
					if (!count()) {
						return FileVisitResult.TERMINATE;
					}

					return KnowledgeFiles.isLink(dir, attributes) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
					if (!count()) {
						return FileVisitResult.TERMINATE;
					}

					if (attributes.isDirectory()) {
						// A folder at the depth limit, reported as a file: what's in it isn't read.
						limitsHit.add(Limit.DEPTH);
					} else if (attributes.isRegularFile() && file.toString().endsWith(".json")) {
						recipeId(namespace, recipeDir, file).ifPresent(id -> files.putIfAbsent(id, file));
					}

					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException e) {
					return FileVisitResult.CONTINUE;
				}
			});
		}

		/**
		 * A recipe file's JSON, with its bytes counted as they're read. Empty if it can't be read, is larger than a
		 * recipe file has any reason to be, nests too deeply or isn't JSON; the reading goes on without it.
		 */
		Optional<JsonElement> read(Path file) {
			long left = limits.totalBytes() - bytes;
			int allowed = (int) Math.max(0, Math.min(limits.fileBytes(), left));

			try (InputStream in = Files.newInputStream(file)) {
				byte[] data = in.readNBytes(allowed + 1);
				bytes += data.length;

				if (data.length > allowed) {
					limitsHit.add(allowed < limits.fileBytes() ? Limit.TOTAL_SIZE : Limit.FILE_SIZE);
					return Optional.empty();
				}

				JsonReader reader = new JsonReader(new StringReader(new String(data, StandardCharsets.UTF_8)));
				reader.setNestingLimit(limits.jsonDepth());
				return Optional.of(JsonParser.parseReader(reader));
			} catch (IOException | RuntimeException e) {
				// Gson's MalformedJsonException, wrapped by the parser: "Nesting limit 64 reached at line …".
				if (String.valueOf(e.getMessage()).contains("Nesting limit")) {
					limitsHit.add(Limit.JSON);
				}

				ClaudeChatClient.LOGGER.debug("Skipping recipe file {}", file, e);
				return Optional.empty();
			}
		}
	}

	/** data/&lt;namespace&gt;/recipe/&lt;path&gt;.json as namespace:path. */
	private static Optional<Identifier> recipeId(Path namespace, Path recipeDir, Path file) {
		String relative = StreamSupport.stream(recipeDir.relativize(file).spliterator(), false)
			.map(Path::toString)
			.collect(Collectors.joining("/"));
		String path = relative.substring(0, relative.length() - ".json".length());
		return Optional.ofNullable(Identifier.tryBuild(namespace.getFileName().toString(), path));
	}
}
