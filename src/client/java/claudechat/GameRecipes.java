package claudechat;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * The game's recipes, by id and by what they make: the one place recipe grids and material lists look recipes up.
 * <p>
 * In a single-player world they are the integrated server's {@link RecipeManager}'s, so the world's enabled packs,
 * their order and their load conditions all count. Without a server (on a multiplayer server, whose recipes 26.x
 * clients don't receive, only recipe-book displays) they are read from the knowledge base's data files, highest
 * priority first, with the game's own recipe codec; Fabric load conditions aren't checked there.
 */
public final class GameRecipes {
	/** Every recipe by id, and each item's main recipe. Immutable. */
	public record Snapshot(Map<Identifier, GameRecipe> byId, Map<Identifier, GameRecipe> mainByItem) {
		static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of());

		static Snapshot of(Collection<GameRecipe> recipes) {
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
			return new Snapshot(Map.copyOf(byId), mainByItem);
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
	 * mods); later calls return the same snapshot.
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
		return snapshotOf(new Basis(registries, knowledge.generation()), () -> fromFiles(knowledge.dataRoots(), registries));
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
		Map<Identifier, Path> files = new LinkedHashMap<>();

		for (Path data : dataRoots) {
			try (Stream<Path> namespaces = Files.list(data)) {
				for (Path namespace : namespaces.toList()) {
					Path recipeDir = namespace.resolve("recipe");

					if (!Files.isDirectory(recipeDir)) {
						continue;
					}

					try (Stream<Path> walk = Files.walk(recipeDir)) {
						for (Path file : walk.filter(path -> path.toString().endsWith(".json")).toList()) {
							recipeId(namespace, recipeDir, file).ifPresent(id -> files.putIfAbsent(id, file));
						}
					}
				}
			} catch (IOException e) {
				ClaudeChatClient.LOGGER.debug("Could not list recipes in {}", data, e);
			}
		}

		return files;
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
			snapshot = read.get();
			basis = current;
			ClaudeChatClient.LOGGER.info("Read {} recipes in {} ms", snapshot.byId().size(), (System.nanoTime() - start) / 1_000_000);
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

	private static Snapshot fromFiles(List<Path> dataRoots, HolderLookup.Provider registries) {
		DynamicOps<JsonElement> ops = registries.createSerializationContext(JsonOps.INSTANCE);
		List<GameRecipe> recipes = new ArrayList<>();

		recipeFiles(dataRoots).forEach((id, file) -> {
			try {
				parse(id, JsonParser.parseString(Files.readString(file)), ops).ifPresent(recipes::add);
			} catch (IOException | RuntimeException e) {
				ClaudeChatClient.LOGGER.debug("Skipping recipe {}", id, e);
			}
		});

		return Snapshot.of(recipes);
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
