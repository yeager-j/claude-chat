package claudechat;

import claudechat.BuiltFolder.Content;
import claudechat.BuiltFolder.Progress;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A local, searchable copy of the game in .minecraft/config/claudechat/knowledge, rebuilt only when its input changes.
 * Claude Code runs with that folder as its working directory.
 * <ul>
 * <li>Every source (Minecraft and each mod) gets its data pack JSON (recipes, loot tables, tags, worldgen) and English
 *     names. That's cheap, and lets recipe grids and searches cover modded content.</li>
 * <li>Minecraft is always decompiled, whatever the config says. Mods start data-only: which ones are worth asking
 *     about can't be inferred, so a mod is decompiled when a question names it, when Claude asks for it, or when the
 *     player runs /claude knowledge include. /claude knowledge exclude removes a mod's code and stops it being
 *     decompiled.</li>
 * <li>The single-player world's enabled data packs are copied too, since they override mods and Minecraft.</li>
 * </ul>
 * Folders for other versions, uninstalled mods and other worlds' packs are deleted after each successful build, so
 * Claude's searches only find what the current game uses.
 */
public class KnowledgeBase {
	/** Whether a source's code is decompiled up front (CODE) or only when needed (DATA). */
	public enum Tier { CODE, DATA }

	/** A source's progress as one word, for display. */
	public enum State { MISSING, QUEUED, BUILDING, DATA_READY, READY, FAILED }

	/** Whether, and why, a source's code may be decompiled. */
	public enum CodePolicy {
		/** Minecraft itself: always decompiled, whatever the config says. */
		ALWAYS("the game"),
		INCLUDED("included by you"),
		EXCLUDED("excluded by you"),
		/** A mod's default: decompiled when a question names it or Claude asks. */
		ON_DEMAND("decompiled when needed"),
		/** A mod without classes: data and resources only. */
		NO_CODE("no code (data and resources only)");

		private final String reason;

		CodePolicy(String reason) {
			this.reason = reason;
		}

		/** Why, in a few words, for display. */
		public String reason() {
			return reason;
		}

		/** Decompiled up front, without waiting for a question to need it. */
		public boolean wantsCode() {
			return this == ALWAYS || this == INCLUDED;
		}

		/** May be decompiled. */
		public boolean allowsCode() {
			return this != EXCLUDED && this != NO_CODE;
		}
	}

	/** Everything known about one source at one moment. Get it with {@link #status(Source)}. */
	public record Status(CodePolicy policy, Content content, Progress progress, String failure) {
		/** Decompiled code is there for Claude. False once the player excludes the mod, even before its folder is deleted. */
		public boolean hasCode() {
			return content == Content.CODE && policy.allowsCode();
		}

		/** Data and lang files are there for Claude. */
		public boolean hasData() {
			return content != Content.NONE;
		}

		/** Code could be added now (by a question, decompile_mod, or include). */
		public boolean canDecompile() {
			return !hasCode() && policy.allowsCode();
		}

		/** /claude knowledge include would change something. */
		public boolean includable() {
			return policy == CodePolicy.ON_DEMAND || policy == CodePolicy.EXCLUDED;
		}

		/** /claude knowledge exclude would change something. */
		public boolean excludable() {
			return policy == CodePolicy.ON_DEMAND || policy == CodePolicy.INCLUDED;
		}

		public Tier tier() {
			return policy.wantsCode() ? Tier.CODE : Tier.DATA;
		}

		public String reason() {
			return policy.reason();
		}

		public State state() {
			return switch (progress) {
				case QUEUED -> State.QUEUED;
				case BUILDING -> State.BUILDING;
				case FAILED -> State.FAILED;
				case IDLE -> hasCode() ? State.READY : hasData() ? State.DATA_READY : State.MISSING;
			};
		}
	}

	/**
	 * Minecraft itself, or one top-level mod. {@code codeContainers} are what gets decompiled: the mod plus any nested
	 * jars that aren't libraries (some mods keep their real code in a nested "common" jar); empty when there are no
	 * classes. {@code dataContainers} also include nested libraries. {@code dir} is relative to the knowledge root.
	 * {@code defaultPolicy} is ALWAYS, ON_DEMAND or NO_CODE; the player's include and exclude only change ON_DEMAND.
	 */
	public record Source(String id, String label, String dir, CodePolicy defaultPolicy,
		List<ModContainer> codeContainers, List<ModContainer> dataContainers, List<String> mentionNames) {
		/** Minecraft itself rather than a mod. */
		public boolean isGame() {
			return defaultPolicy == CodePolicy.ALWAYS;
		}
	}

	/** How the reference file list marks a mod whose code decompile_mod can add. */
	public static final String NOT_DECOMPILED = "code not decompiled yet";

	private static final String MINECRAFT_ID = "minecraft";
	/** The data folder inside a jar or a data pack. */
	private static final String PACK_DATA = "data";
	/** Words in mod names that don't identify a mod; Minecraft's own words are added to these. */
	private static final Set<String> GENERIC_WORDS = Set.of("fabric", "mod", "mods", "api", "lib", "library");
	/** Files older versions of this mod kept in the knowledge root. */
	private static final List<String> LEGACY_FILES = List.of("build.log", "vineflower-libraries.cfg");

	/** A data pack from the current world's datapacks folder, as a folder or a zip. */
	private record WorldPack(String name, Path location, BuiltFolder folder) {
	}

	/** What the prompt says about a world pack. */
	private enum PackStatus { READING, READY, EMPTY, FAILED }

	private final ClaudeConfig config;
	private final Path root = ClaudeConfig.dir().resolve("knowledge");
	private final List<Source> sources;
	private final Map<String, BuiltFolder> sourceFolders;
	private final Decompiler decompiler = new Decompiler(root);
	private final Object sweepLock = new Object();
	private final AtomicInteger generation = new AtomicInteger();
	private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Claude Chat knowledge builder");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});
	private volatile List<WorldPack> worldPacks = List.of();
	private volatile String worldName = "";
	private ModMentions mentions;

	public KnowledgeBase(ClaudeConfig config) {
		this.config = config;
		this.sources = findSources();
		Map<String, BuiltFolder> folders = new HashMap<>();

		for (Source source : sources) {
			folders.put(source.id(), new BuiltFolder(root, source.dir(), inputKey(source)));
		}

		this.sourceFolders = Map.copyOf(folders);
	}

	public Path root() {
		return root;
	}

	public List<Source> sources() {
		return sources;
	}

	public Optional<Source> source(String id) {
		return sources.stream().filter(source -> source.id().equals(id)).findFirst();
	}

	public Status status(Source source) {
		BuiltFolder folder = folder(source);
		return new Status(policy(source), folder.content(), folder.progress(), folder.failure());
	}

	/** Same as {@code status(source).state()}; kept until callers use {@link #status}. */
	public State state(Source source) {
		return status(source).state();
	}

	/** Same as {@code status(source).tier()}; kept until callers use {@link #status}. */
	public Tier tier(Source source) {
		return status(source).tier();
	}

	/** Same as {@code status(source).reason()}; kept until callers use {@link #status}. */
	public String reason(Source source) {
		return status(source).reason();
	}

	/** Same as {@code status(source).canDecompile()}; kept until callers use {@link #status}. */
	public boolean canDecompile(Source source) {
		return status(source).canDecompile();
	}

	/** Same as {@code status(source).hasCode()}; kept until callers use {@link #status}. */
	public boolean hasCode(Source source) {
		return status(source).hasCode();
	}

	/** The source's decompiled code, relative to {@link #root()}, as Claude sees it. */
	public String codeFolder(Source source) {
		return folder(source).relativeDir() + "/" + BuiltFolder.SRC;
	}

	/** Bumped whenever the data roots or their contents change, so caches of the data (e.g. the recipe index) refresh. */
	public int generation() {
		return generation.get();
	}

	/** True while anything is queued or building. */
	public boolean isBusy() {
		return allFolders().anyMatch(folder -> folder.progress().busy());
	}

	/**
	 * Fabric API's own modules ("fabric-networking-api-v1", ...): part of the loader setup, never one of the player's
	 * mods, and not worth decompiling.
	 */
	public static boolean isFabricApiModule(String modId) {
		return modId.startsWith("fabric-");
	}

	/** Queues what each source still needs: code and data when its policy wants code up front, otherwise data. */
	public void buildMissing(Consumer<String> notify) {
		// Minecraft first, since most questions need its code; then the data-only builds, a second each, before the
		// slower mod decompiles.
		Comparator<Source> order = Comparator.comparing((Source source) -> !source.isGame())
			.thenComparing(source -> policy(source).wantsCode());

		for (Source source : sources.stream().sorted(order).toList()) {
			BuiltFolder folder = folder(source);
			Content target = target(policy(source), CodePolicy::wantsCode);

			if (!folder.content().covers(target) && folder.queue()) {
				executor.execute(() -> ensure(source, CodePolicy::wantsCode, notify));
			}
		}
	}

	/** Throws away what's built for the game and mods, and builds it again. A build that's running is left to finish. */
	public void rebuildAll(Consumer<String> notify) {
		sourceFolders.values().forEach(BuiltFolder::discardUnlessBuilding);
		generation.incrementAndGet();
		buildMissing(notify);
	}

	/** Decompiles a mod from now on, starting right away. Throws for Minecraft and mods without code. */
	public void include(Source source, Consumer<String> notify) {
		requireConfigurable(source);
		config.knowledgeExclude.remove(source.id());
		config.knowledgeInclude.add(source.id());
		config.save();
		buildMissing(notify);
	}

	/**
	 * Stops decompiling a mod and deletes its decompiled code; its data stays. {@link #status} reports no code at once;
	 * the folder is deleted after any build of the mod already queued or running. Throws for Minecraft and mods without
	 * code.
	 */
	public void exclude(Source source) {
		requireConfigurable(source);
		// Recorded even though mods default to data-only, so neither a mention nor Claude's request decompiles it again.
		config.knowledgeInclude.remove(source.id());
		config.knowledgeExclude.add(source.id());
		config.save();
		// Behind any queued build, and under the folder's lock, so code a running build publishes is removed too.
		executor.execute(() -> dropCodeIfExcluded(source));
	}

	/**
	 * Mods a question refers to whose code could be decompiled but isn't yet. {@code namespaces} are exact mod ids from
	 * the world (the held item's, the targeted block's). The question's text matches only through a distinctive name
	 * (see {@link ModMentions}), since each match decompiles before Claude answers.
	 */
	public List<Source> mentionedWithoutCode(String question, Collection<String> namespaces) {
		List<String> questionWords = ModMentions.words(question);
		ModMentions matcher = mentions();

		return sources.stream()
			.filter(source -> !source.isGame() && status(source).canDecompile())
			.filter(source -> isNamespaceOf(source, namespaces)
				|| source.mentionNames().stream().anyMatch(name -> matcher.mentions(questionWords, name)))
			.toList();
	}

	/**
	 * Free text only: namespaces appended to it match only as distinctive names. Kept until callers pass namespaces to
	 * {@link #mentionedWithoutCode(String, Collection)}.
	 */
	public List<Source> mentionedWithoutCode(String text) {
		return mentionedWithoutCode(text, Set.of());
	}

	/** Decompiles one source right now, on the calling thread (waiting if a build of it is already running). */
	public void buildCodeNow(Source source, Consumer<String> notify) {
		ensure(source, CodePolicy::allowsCode, notify);
	}

	/**
	 * The data/ folders that exist, in override order: the world's data packs (last enabled first), then mods, then
	 * Minecraft. A file found earlier in this list is the one the game actually uses.
	 */
	public List<Path> dataRoots() {
		List<Path> roots = new ArrayList<>();

		for (WorldPack pack : worldPacks) {
			if (packStatus(pack.folder()) == PackStatus.READY) {
				roots.add(pack.folder().data());
			}
		}

		List<Source> minecraftLast = sources.stream()
			.sorted(Comparator.comparing(Source::isGame))
			.toList();

		for (Source source : minecraftLast) {
			if (folder(source).content() != Content.NONE) {
				roots.add(folder(source).data());
			}
		}

		return roots.stream().filter(Files::isDirectory).toList();
	}

	/** A file under data/, e.g. "minecraft/recipe/crafter.json", from whichever source has it. */
	public Optional<Path> findDataFile(String relative) {
		for (Path data : dataRoots()) {
			Optional<Path> file = KnowledgeFiles.resolveInside(data, relative).filter(Files::isRegularFile);

			if (file.isPresent()) {
				return file;
			}
		}

		return Optional.empty();
	}

	/**
	 * The single-player world's enabled data packs changed (world opened, or /reload). {@code selectedIds} is the
	 * server's enabled pack list in load order; only "file/..." packs come from the world's datapacks folder. A pack is
	 * copied again whenever its files change.
	 */
	public void setWorldPacks(String world, Path datapackDir, List<String> selectedIds) {
		String worldFolder = KnowledgeFiles.folderName(datapackDir.toAbsolutePath().getParent().getFileName().toString());
		List<WorldPack> packs = new ArrayList<>();

		for (String id : selectedIds) {
			if (!id.startsWith("file/")) {
				continue;
			}

			String name = id.substring("file/".length());
			Optional<Path> location = KnowledgeFiles.resolveInside(datapackDir, name).filter(Files::exists);

			if (location.isPresent()) {
				String dir = "datapacks/" + worldFolder + "/" + KnowledgeFiles.folderName(name);
				packs.add(new WorldPack(name, location.get(), packFolder(dir, packKey(location.get()))));
			}
		}

		Collections.reverse(packs); // highest priority (loaded last) first
		worldName = world;
		worldPacks = List.copyOf(packs);
		generation.incrementAndGet();

		for (WorldPack pack : packs) {
			if (pack.folder().content() == Content.NONE && pack.folder().queue()) {
				executor.execute(() -> extractPack(pack));
			}
		}
	}

	public void clearWorldPacks() {
		worldPacks = List.of();
		worldName = "";
		generation.incrementAndGet();
	}

	/** The part of the system prompt that tells Claude what reference files exist and how to use them. */
	public String describeForPrompt() {
		StringBuilder sb = new StringBuilder();
		sb.append("Reference files for the player's exact game, in the current directory (read-only; search with Grep/Glob, then Read).\n");
		sb.append(BuiltFolder.SRC).append(" = decompiled Java, ")
			.append(BuiltFolder.DATA).append(" = data pack JSON (recipe/, loot_table/, tags/, worldgen/, ...), ")
			.append(BuiltFolder.LANG).append(" = English names.\n");

		for (Source source : sources) {
			sb.append("- ").append(source.label()).append(" [").append(source.id()).append("]: ")
				.append(describeSource(source)).append('\n');
		}

		List<WorldPack> packs = worldPacks;

		if (!packs.isEmpty()) {
			sb.append("Data packs enabled in this world (\"").append(worldName).append("\"), highest priority first. A file here")
				.append(" replaces the file at the same path in mods and Minecraft (e.g. data/minecraft/loot_table/...), so check these first:\n");

			for (WorldPack pack : packs) {
				sb.append("- ").append(pack.name()).append(": ").append(describePack(pack.folder())).append('\n');
			}
		}

		sb.append("""
			How to use them:
			- Recipes, drops, tags, structures, worldgen: read the JSON under data/ first.
			- Mechanics (spawning, AI, redstone timing, formulas, what a mod changes): grep the relevant src/ and read the code. Don't guess.
			- Mods often change vanilla behavior through mixins: grep a mod's src/ for "@Mixin" to see which game classes it modifies.
			- Map display names to ids with lang/*.json (e.g. "block.minecraft.crafter": "Crafter").
			""");
		return sb.toString();
	}

	private String describeSource(Source source) {
		Status status = status(source);
		String dir = folder(source).relativeDir();
		String dataOnly = dir + "/{" + BuiltFolder.DATA + "," + BuiltFolder.LANG + "}";

		if (status.hasCode()) {
			return dir + "/{" + BuiltFolder.SRC + "," + BuiltFolder.DATA + "," + BuiltFolder.LANG + "}";
		}

		if (status.hasData() && status.canDecompile()) {
			return dataOnly + "; " + NOT_DECOMPILED + " (decompile_mod)";
		}

		if (status.hasData()) {
			return dataOnly + "; no code available (" + status.reason() + ")";
		}

		if (status.progress() == Progress.FAILED) {
			return "couldn't be prepared (" + status.failure() + ")";
		}

		return "not available yet (still being prepared)";
	}

	private static String describePack(BuiltFolder folder) {
		return switch (packStatus(folder)) {
			case READY -> folder.relativeDir() + "/" + BuiltFolder.DATA;
			case EMPTY -> "no data files (resources only)";
			case READING -> "still being read";
			case FAILED -> "couldn't be read (" + folder.failure() + ")";
		};
	}

	private static PackStatus packStatus(BuiltFolder folder) {
		if (folder.progress() == Progress.FAILED) {
			return PackStatus.FAILED;
		}

		if (folder.content() == Content.NONE) {
			return PackStatus.READING;
		}

		return Files.isDirectory(folder.data()) ? PackStatus.READY : PackStatus.EMPTY;
	}

	private BuiltFolder folder(Source source) {
		return sourceFolders.get(source.id());
	}

	private Stream<BuiltFolder> allFolders() {
		return Stream.concat(sourceFolders.values().stream(), worldPacks.stream().map(WorldPack::folder));
	}

	/** The player's include or exclude applies only to mods with code; Minecraft and code-less mods keep their default. */
	private static boolean isConfigurable(Source source) {
		return source.defaultPolicy() == CodePolicy.ON_DEMAND;
	}

	private static void requireConfigurable(Source source) {
		if (!isConfigurable(source)) {
			throw new IllegalArgumentException(source.label() + " can't be included or excluded: " + source.defaultPolicy().reason());
		}
	}

	private CodePolicy policy(Source source) {
		if (!isConfigurable(source)) {
			return source.defaultPolicy();
		}

		if (config.knowledgeInclude.contains(source.id())) {
			return CodePolicy.INCLUDED;
		}

		return config.knowledgeExclude.contains(source.id()) ? CodePolicy.EXCLUDED : CodePolicy.ON_DEMAND;
	}

	/** What a build must produce: code when {@code withCode} accepts the policy, otherwise data. */
	private static Content target(CodePolicy policy, Predicate<CodePolicy> withCode) {
		return withCode.test(policy) ? Content.CODE : Content.DATA;
	}

	/** Builds what {@code withCode} asks for unless it's there. A second caller waits for the first, then finds it done. */
	private void ensure(Source source, Predicate<CodePolicy> withCode, Consumer<String> notify) {
		BuiltFolder folder = folder(source);

		folder.locked(() -> {
			// Decided under the lock, so an exclude since the build was queued is respected.
			Content target = target(policy(source), withCode);

			if (folder.content().covers(target)) {
				folder.settle();
				return;
			}

			build(source, folder, target, notify);
		});
	}

	private void build(Source source, BuiltFolder folder, Content target, Consumer<String> notify) {
		boolean withCode = target == Content.CODE;
		long start = System.nanoTime();
		ClaudeChatClient.LOGGER.info("Building Claude knowledge base for {} ({})", source.label(), withCode ? "code + data" : "data");

		try {
			folder.build(target, (out, work) -> {
				for (ModContainer container : source.dataContainers()) {
					extractData(container, out);
				}

				if (withCode) {
					decompileInto(source, out.resolve(BuiltFolder.SRC), work);
				}
			});
		} catch (IOException | RuntimeException e) {
			ClaudeChatClient.LOGGER.warn("Could not build Claude knowledge base for {}", source.label(), e);
			notify.accept("Couldn't build the knowledge base for " + source.label() + ": " + folder.failure());
			return;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			ClaudeChatClient.LOGGER.warn("Building Claude knowledge base for {} was interrupted", source.label());
			return;
		}

		generation.incrementAndGet();
		removeSuperseded();

		long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
		ClaudeChatClient.LOGGER.info("Claude knowledge base for {} ready in {}s", source.label(), seconds);

		if (withCode) {
			notify.accept("Knowledge base ready: " + source.label() + " (" + seconds + "s).");
		}
	}

	private void decompileInto(Source source, Path src, Path work) throws IOException, InterruptedException {
		Files.createDirectories(src);

		for (ModContainer container : source.codeContainers()) {
			decompiler.decompile(container, src, work);
		}

		KnowledgeFiles.pruneEmptyDirectories(src);
	}

	private void dropCodeIfExcluded(Source source) {
		BuiltFolder folder = folder(source);

		folder.locked(() -> {
			if (policy(source) != CodePolicy.EXCLUDED) {
				return;
			}

			try {
				folder.dropCode();
			} catch (IOException e) {
				ClaudeChatClient.LOGGER.warn("Could not remove decompiled code for {}", source.label(), e);
			}
		});
	}

	/** The current pack's folder when the same pack is already known (keeping its progress), otherwise a new one. */
	private BuiltFolder packFolder(String dir, String key) {
		return worldPacks.stream()
			.map(WorldPack::folder)
			.filter(folder -> folder.relativeDir().equals(dir) && folder.key().equals(key))
			.findFirst()
			.orElseGet(() -> new BuiltFolder(root, dir, key));
	}

	private void extractPack(WorldPack pack) {
		BuiltFolder folder = pack.folder();

		// The world closed or its packs changed since this was queued.
		if (!worldPacks.contains(pack)) {
			folder.settle();
			return;
		}

		folder.locked(() -> {
			try {
				folder.build(Content.DATA, (out, work) -> copyPackData(pack.location(), out.resolve(BuiltFolder.DATA)));
			} catch (IOException | RuntimeException e) {
				ClaudeChatClient.LOGGER.warn("Could not read world data pack {}", pack.name(), e);
				return;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}

			generation.incrementAndGet();
			removeSuperseded();
			ClaudeChatClient.LOGGER.info("Claude knowledge base: extracted world data pack {}", pack.name());
		});
	}

	/** A pack can be anyone's download, so its copy is bounded and doesn't follow links (see {@link DataPackCopy}). */
	private static void copyPackData(Path location, Path target) throws IOException {
		if (Files.isDirectory(location)) {
			DataPackCopy.fromFolder(location.resolve(PACK_DATA), target, DataPackCopy.Limits.DEFAULT);
			return;
		}

		DataPackCopy.fromZip(location, PACK_DATA, target, DataPackCopy.Limits.DEFAULT);
	}

	/**
	 * Describes a pack's files, so an edited pack is copied again: size and time for a zip; each data file's path,
	 * size and time for a folder.
	 */
	private static String packKey(Path location) {
		try {
			if (!Files.isDirectory(location)) {
				return "zip:" + Files.size(location) + ":" + Files.getLastModifiedTime(location).toMillis();
			}

			Path data = location.resolve(PACK_DATA);

			if (!Files.isDirectory(data)) {
				return "folder:no data";
			}

			try (Stream<Path> files = Files.walk(data)) {
				List<String> listing = new ArrayList<>();

				for (Path file : files.filter(Files::isRegularFile).toList()) {
					listing.add(data.relativize(file) + "\t" + Files.size(file) + "\t" + Files.getLastModifiedTime(file).toMillis());
				}

				Collections.sort(listing);
				return "folder:" + KnowledgeFiles.sha256(String.join("\n", listing));
			}
		} catch (IOException | UncheckedIOException e) {
			// Copying will fail too, and report why.
			return "unreadable";
		}
	}

	/**
	 * Deletes built folders the current game, mods and world don't use (other versions, uninstalled mods, other
	 * worlds' packs), so Claude's searches don't find stale copies. Runs only after a build succeeds.
	 */
	private void removeSuperseded() {
		Set<Path> wanted = allFolders().map(BuiltFolder::dir).collect(Collectors.toSet());

		synchronized (sweepLock) {
			BuiltFolder.removeUnwanted(root, wanted);

			for (String legacy : LEGACY_FILES) {
				try {
					Files.deleteIfExists(root.resolve(legacy));
				} catch (IOException e) {
					ClaudeChatClient.LOGGER.warn("Could not delete {}", legacy, e);
				}
			}
		}
	}

	private static boolean isNamespaceOf(Source source, Collection<String> namespaces) {
		return namespaces.contains(source.id())
			|| source.codeContainers().stream().anyMatch(container -> namespaces.contains(container.getMetadata().getId()));
	}

	private synchronized ModMentions mentions() {
		if (mentions == null) {
			mentions = new ModMentions(commonWords());
		}

		return mentions;
	}

	/** Words a question uses without naming a mod: those of Minecraft's English text, and generic mod-name words. */
	private static Set<String> commonWords() {
		Set<String> words = new HashSet<>(GENERIC_WORDS);
		Optional<Path> english = FabricLoader.getInstance().getModContainer(MINECRAFT_ID)
			.flatMap(minecraft -> minecraft.findPath("assets/minecraft/lang/en_us.json"));

		if (english.isEmpty()) {
			ClaudeChatClient.LOGGER.warn("Minecraft's English text is missing; mod names in questions match more loosely");
			return words;
		}

		try (Reader reader = Files.newBufferedReader(english.get(), StandardCharsets.UTF_8)) {
			for (Map.Entry<String, JsonElement> entry : JsonParser.parseReader(reader).getAsJsonObject().entrySet()) {
				if (entry.getValue().isJsonPrimitive()) {
					words.addAll(ModMentions.words(entry.getValue().getAsString()));
				}
			}
		} catch (IOException | RuntimeException e) {
			ClaudeChatClient.LOGGER.warn("Could not read Minecraft's English text", e);
		}

		return words;
	}

	/** Every mod and nested jar with its version, so a changed nested jar is rebuilt even when the mod's version isn't. */
	private static String inputKey(Source source) {
		return source.dataContainers().stream()
			.map(ModContainer::getMetadata)
			.map(meta -> meta.getId() + "@" + meta.getVersion().getFriendlyString())
			.collect(Collectors.joining(","));
	}

	/** Copies data/ and English translations out of a mod, through the loader's view of its files (works for nested jars). */
	private static void extractData(ModContainer container, Path out) throws IOException {
		for (Path rootPath : container.getRootPaths()) {
			KnowledgeFiles.copyTree(rootPath.resolve(PACK_DATA), out.resolve(BuiltFolder.DATA), file -> true);
			copyEnglishNames(rootPath.resolve("assets"), out.resolve(BuiltFolder.LANG));
		}
	}

	/** assets/&lt;namespace&gt;/lang/en_us.json to lang/&lt;namespace&gt;.json. */
	private static void copyEnglishNames(Path assets, Path langDir) throws IOException {
		if (!Files.isDirectory(assets)) {
			return;
		}

		try (Stream<Path> namespaces = Files.list(assets)) {
			for (Path namespace : namespaces.toList()) {
				Path english = namespace.resolve("lang").resolve("en_us.json");
				// Folder names from a jar's file system can end with a slash.
				String name = namespace.getFileName().toString().replace("/", "");
				Optional<Path> target = KnowledgeFiles.resolveInside(langDir, name + ".json");

				if (Files.isRegularFile(english) && target.isPresent()) {
					KnowledgeFiles.copyFile(english, target.get());
				}
			}
		}
	}

	private static List<Source> findSources() {
		List<Source> found = new ArrayList<>();

		FabricLoader.getInstance().getModContainer(MINECRAFT_ID).ifPresent(minecraft -> {
			String version = minecraft.getMetadata().getVersion().getFriendlyString();
			found.add(new Source(MINECRAFT_ID, "Minecraft " + version, "minecraft-" + KnowledgeFiles.folderName(version),
				CodePolicy.ALWAYS, List.of(minecraft), List.of(minecraft), List.of()));
		});

		// Mods are never decompiled up front: whether one is worth asking about can't be inferred from its metadata.
		// Each starts data-only and is decompiled when a question needs it or the player includes it.
		for (ModContainer mod : GameContext.userMods()) {
			ModMetadata meta = mod.getMetadata();
			List<ModContainer> code = new ArrayList<>();
			List<ModContainer> data = new ArrayList<>();
			code.add(mod);
			data.add(mod);
			collectNested(mod.getContainedMods(), code, data);

			boolean hasClasses = hasClasses(code);
			String version = meta.getVersion().getFriendlyString();
			String dir = "mods/" + KnowledgeFiles.folderName(meta.getId()) + "/" + KnowledgeFiles.folderName(version);
			found.add(new Source(meta.getId(), meta.getName() + " " + version, dir,
				hasClasses ? CodePolicy.ON_DEMAND : CodePolicy.NO_CODE, hasClasses ? List.copyOf(code) : List.of(),
				List.copyOf(data), List.copyOf(new LinkedHashSet<>(List.of(meta.getId(), meta.getName())))));
		}

		return found;
	}

	/** Nested jars: libraries are skipped for code (polymer, placeholder-api...), but everything counts for data. */
	private static void collectNested(Collection<ModContainer> nested, List<ModContainer> code, List<ModContainer> data) {
		for (ModContainer child : nested) {
			data.add(child);

			if (!isLibrary(child.getMetadata()) && !isFabricApiModule(child.getMetadata().getId())) {
				code.add(child);
			}

			collectNested(child.getContainedMods(), code, data);
		}
	}

	/** Mod Menu's "library" badge, which most library mods set. */
	private static boolean isLibrary(ModMetadata meta) {
		CustomValue modmenu = meta.getCustomValue("modmenu");

		if (modmenu == null || modmenu.getType() != CustomValue.CvType.OBJECT) {
			return false;
		}

		CustomValue badges = modmenu.getAsObject().get("badges");

		if (badges == null || badges.getType() != CustomValue.CvType.ARRAY) {
			return false;
		}

		for (CustomValue badge : badges.getAsArray()) {
			if (badge.getType() == CustomValue.CvType.STRING && badge.getAsString().equals("library")) {
				return true;
			}
		}

		return false;
	}

	/** Stops at the first class file: startup, which runs this for every mod, only needs to know there is code. */
	private static boolean hasClasses(List<ModContainer> containers) {
		for (ModContainer container : containers) {
			for (Path rootPath : container.getRootPaths()) {
				try (Stream<Path> files = Files.walk(rootPath)) {
					if (files.anyMatch(Decompiler::isClassFile)) {
						return true;
					}
				} catch (IOException | UncheckedIOException e) {
					ClaudeChatClient.LOGGER.debug("Could not scan {}", rootPath, e);
				}
			}
		}

		return false;
	}
}
