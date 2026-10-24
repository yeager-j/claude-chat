package claudechat;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Settings in .minecraft/config/claudechat.properties. Created with defaults on first launch; /claude model and
 * /claude effort save here. A value that doesn't parse falls back to its default, and keys this version doesn't know
 * are kept when the file is saved.
 */
public class ClaudeConfig {
	public static final List<String> MODEL_SUGGESTIONS = List.of("haiku", "sonnet", "opus", "fable");

	/** Claude Code's {@code --effort} level. */
	public enum Effort {
		/** No {@code --effort} flag: Claude Code's own default. */
		DEFAULT("default"),
		LOW("low"),
		MEDIUM("medium"),
		HIGH("high"),
		XHIGH("xhigh"),
		MAX("max");

		/** The name the player types in {@code /claude effort}. */
		public final String id;

		Effort(String id) {
			this.id = id;
		}

		/** The level with this id, ignoring case; empty also means {@link #DEFAULT}, as older versions wrote it. */
		public static Optional<Effort> parse(String id) {
			String trimmed = id.trim();
			if (trimmed.isEmpty()) {
				return Optional.of(DEFAULT);
			}

			return Stream.of(values()).filter(e -> e.id.equalsIgnoreCase(trimmed)).findFirst();
		}

		/** The value for {@code --effort}; empty for {@link #DEFAULT}, which passes no flag. */
		public Optional<String> flag() {
			return this == DEFAULT ? Optional.empty() : Optional.of(id);
		}
	}

	// Defaults are these initial values.

	/** Path to claude.exe. Empty = look on PATH, then in the Claude desktop app's bundled copy. */
	public Optional<Path> claudePath = Optional.empty();
	/** A model alias (haiku, sonnet, opus, fable) or a full model ID. */
	public String model = "sonnet";
	public Effort effort = Effort.DEFAULT;
	/** Chat messages starting with this are sent to Claude instead of the server. */
	public String chatPrefix = "@claude";
	/** How long Claude may work on a question, not counting time spent waiting for the player to Allow or Deny. */
	public int timeoutSeconds = 300;
	/**
	 * Build the knowledge base in the background at startup: Minecraft's code, included mods' code and every mod's
	 * data. Only the startup build: a question naming a mod, or Claude's decompile_mod, decompiles it either way.
	 */
	public boolean buildKnowledge = true;
	/**
	 * Mod ids to always decompile, or never decompile, overriding the automatic choice. Set with /claude knowledge on
	 * the client thread and read by background builds, so they're concurrent sets (sorted, for the file).
	 */
	public final Set<String> knowledgeInclude = new ConcurrentSkipListSet<>();
	public final Set<String> knowledgeExclude = new ConcurrentSkipListSet<>();
	/** Extra instructions appended to the system prompt, e.g. "Answer like a pirate." */
	public String extraInstructions = "";
	/**
	 * Set with /claude permissions; a tier that needs it is set only after a click on a confirm button. A question
	 * captures it when asked ({@link Policy}).
	 */
	public PermissionTier permissions = PermissionTier.READ_ONLY;
	/**
	 * Off: Claude's game commands run with the player's own permission level, so in a world without cheats it can't
	 * run cheat commands. On: they run at command block level at least (still as the player), whatever the world
	 * allows. A question captures it when asked.
	 */
	public boolean cheats = false;

	private static final String VERSION_KEY = "configVersion";

	/**
	 * Migration {@code i} upgrades a file from version {@code i} to {@code i + 1}, so the current version is the list's
	 * size. A file without a version is version 0.
	 */
	private static final List<Consumer<Properties>> MIGRATIONS = List.of(
		// Version 1 made Sonnet the default. Older files stored the old default (haiku) explicitly, so move them over.
		props -> {
			if ("haiku".equals(props.getProperty("model", "").trim())) {
				props.setProperty("model", "sonnet");
			}
		},
		// Version 2 added permissions and cheats. Missing means their defaults (read only, off), so nothing to change.
		props -> { });
	static final int CURRENT_VERSION = MIGRATIONS.size();

	/** Every key in the file: how it's parsed and formatted, and which field holds it. */
	private static final List<Setting<?>> SETTINGS = List.of(
		new Setting<>("claudePath", ClaudeConfig::parsePath, path -> path.map(Path::toString).orElse(""),
			c -> c.claudePath, (c, v) -> c.claudePath = v),
		new Setting<>("model", ClaudeConfig::nonBlank, UnaryOperator.identity(), c -> c.model, (c, v) -> c.model = v),
		new Setting<>("effort", Effort::parse, e -> e.flag().orElse(""), c -> c.effort, (c, v) -> c.effort = v),
		new Setting<>("chatPrefix", ClaudeConfig::nonBlank, UnaryOperator.identity(), c -> c.chatPrefix, (c, v) -> c.chatPrefix = v),
		new Setting<>("timeoutSeconds", ClaudeConfig::positiveInt, String::valueOf, c -> c.timeoutSeconds, (c, v) -> c.timeoutSeconds = v),
		new Setting<>("buildKnowledge", ClaudeConfig::bool, String::valueOf, c -> c.buildKnowledge, (c, v) -> c.buildKnowledge = v),
		new Setting<>("extraInstructions", Optional::of, UnaryOperator.identity(), c -> c.extraInstructions, (c, v) -> c.extraInstructions = v),
		new Setting<>("knowledgeInclude", ClaudeConfig::list, ClaudeConfig::formatList, c -> c.knowledgeInclude,
			(c, v) -> replaceAll(c.knowledgeInclude, v)),
		new Setting<>("knowledgeExclude", ClaudeConfig::list, ClaudeConfig::formatList, c -> c.knowledgeExclude,
			(c, v) -> replaceAll(c.knowledgeExclude, v)),
		// A missing or unknown tier is read only, so nobody gets more access by accident.
		new Setting<>("permissions", PermissionTier::parse, p -> p.id, c -> c.permissions, (c, v) -> c.permissions = v),
		new Setting<>("cheats", ClaudeConfig::bool, String::valueOf, c -> c.cheats, (c, v) -> c.cheats = v));

	/** The file as loaded and migrated, including keys this version doesn't know. */
	private Properties stored = new Properties();
	/** The file's version before migrating; higher than {@link #CURRENT_VERSION} if a newer version wrote it. */
	private int storedVersion = CURRENT_VERSION;
	/** Values that didn't parse, by key. Each stays in the file as written until the player changes that setting. */
	private final Map<String, String> unparsed = new HashMap<>();

	/**
	 * One key of the file. The field's initial value is the default, used when the key is missing or doesn't parse.
	 *
	 * @param parser    the value, or empty if the text isn't valid; gets trimmed text
	 * @param formatter the text that {@code parser} reads back as the same value
	 */
	private record Setting<T>(String key, Function<String, Optional<T>> parser, Function<T, String> formatter,
		Function<ClaudeConfig, T> getter, BiConsumer<ClaudeConfig, T> setter) {

		/** Sets the field from the file's text; returns false if the text didn't parse, leaving the default. */
		boolean read(ClaudeConfig config, String text) {
			Optional<T> value = parser.apply(text.trim());
			value.ifPresent(v -> setter.accept(config, v));
			return value.isPresent();
		}

		String format(ClaudeConfig config) {
			return formatter.apply(getter.apply(config));
		}
	}

	public static Path dir() {
		return FabricLoader.getInstance().getConfigDir().resolve(ClaudeChatClient.MOD_ID);
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(ClaudeChatClient.MOD_ID + ".properties");
	}

	/** Reads the file, migrating it to the current version. Writes it back if it lacks a setting or was migrated. */
	public static ClaudeConfig load() {
		Properties props = new Properties();
		if (Files.exists(file())) {
			try (Reader reader = Files.newBufferedReader(file())) {
				props.load(reader);
			} catch (IOException e) {
				ClaudeChatClient.LOGGER.warn("Could not read {}", file(), e);
			}
		}

		ClaudeConfig config = fromProperties(props);
		config.unparsed.forEach((key, text) ->
			ClaudeChatClient.LOGGER.warn("{} in {}: \"{}\" isn't a valid value; using the default", key, file(), text));
		if (config.fileOutdated()) {
			config.save();
		}

		return config;
	}

	/** Parses a loaded file: migrates it from its version, then reads each setting. */
	static ClaudeConfig fromProperties(Properties file) {
		Properties props = new Properties();
		props.putAll(file);

		ClaudeConfig config = new ClaudeConfig();
		config.storedVersion = parseVersion(props.getProperty(VERSION_KEY));
		for (int version = config.storedVersion; version < CURRENT_VERSION; version++) {
			MIGRATIONS.get(version).accept(props);
		}

		for (Setting<?> setting : SETTINGS) {
			String text = props.getProperty(setting.key());
			if (text != null && !setting.read(config, text)) {
				config.unparsed.put(setting.key(), text);
			}
		}

		config.stored = props;
		return config;
	}

	/** A missing or unreadable version is 0, so every migration runs. */
	private static int parseVersion(String text) {
		if (text == null) {
			return 0;
		}

		try {
			return Math.max(0, Integer.parseInt(text.trim()));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** Does the file need writing so every setting and the current version are visible in it? */
	boolean fileOutdated() {
		return storedVersion < CURRENT_VERSION || SETTINGS.stream().anyMatch(s -> !stored.containsKey(s.key()));
	}

	/**
	 * The file to write: unknown keys as loaded, every setting, and a version that never goes below the one loaded,
	 * so a newer version's file isn't marked as older.
	 */
	Properties toProperties() {
		Properties props = new Properties();
		props.putAll(stored);
		props.setProperty(VERSION_KEY, String.valueOf(Math.max(storedVersion, CURRENT_VERSION)));

		ClaudeConfig defaults = new ClaudeConfig();
		for (Setting<?> setting : SETTINGS) {
			String value = setting.format(this);
			String original = unparsed.get(setting.key());
			// Still the fallback: keep what was written, which may be a newer version's value this one can't read.
			boolean keepOriginal = original != null && value.equals(setting.format(defaults));
			props.setProperty(setting.key(), keepOriginal ? original : value);
		}

		return props;
	}

	public synchronized void save() {
		Properties props = toProperties();
		try (Writer writer = Files.newBufferedWriter(file())) {
			props.store(writer, fileComment());
		} catch (IOException e) {
			ClaudeChatClient.LOGGER.warn("Could not write {}", file(), e);
			return;
		}

		stored = props;
		unparsed.entrySet().removeIf(entry -> !entry.getValue().equals(props.getProperty(entry.getKey())));
	}

	private static String fileComment() {
		String efforts = Stream.of(Effort.values()).filter(e -> e != Effort.DEFAULT).map(e -> e.id).collect(Collectors.joining(", "));
		String tiers = String.join(", ", PermissionTier.ids());
		return "Claude Chat settings. claudePath empty = auto-detect. model: " + String.join(", ", MODEL_SUGGESTIONS)
			+ ", or a full model ID. effort: " + efforts + ", or empty for the default. permissions: " + tiers + "."
			+ " cheats: true lets Claude run cheat commands even where you can't.";
	}

	// --- Parsers: the value, or empty if the text isn't valid ----------------------------------------------------

	private static Optional<Optional<Path>> parsePath(String text) {
		if (text.isEmpty()) {
			return Optional.of(Optional.empty());
		}

		try {
			return Optional.of(Optional.of(Path.of(text)));
		} catch (InvalidPathException e) {
			return Optional.empty();
		}
	}

	private static Optional<String> nonBlank(String text) {
		return text.isEmpty() ? Optional.empty() : Optional.of(text);
	}

	private static Optional<Integer> positiveInt(String text) {
		try {
			int value = Integer.parseInt(text);
			return value > 0 ? Optional.of(value) : Optional.empty();
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

	private static Optional<Boolean> bool(String text) {
		String lower = text.toLowerCase(Locale.ROOT);
		return switch (lower) {
			case "true" -> Optional.of(true);
			case "false" -> Optional.of(false);
			default -> Optional.empty();
		};
	}

	private static Optional<Set<String>> list(String text) {
		Set<String> values = Arrays.stream(text.split(",")).map(String::trim).filter(s -> !s.isEmpty())
			.collect(Collectors.toCollection(TreeSet::new));
		return Optional.of(values);
	}

	private static String formatList(Collection<String> values) {
		return String.join(",", values);
	}

	/** The include and exclude sets are final, so a loaded value replaces their contents. */
	private static void replaceAll(Set<String> target, Set<String> values) {
		target.clear();
		target.addAll(values);
	}
}
