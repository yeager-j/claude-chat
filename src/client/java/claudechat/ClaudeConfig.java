package claudechat;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/** Settings in .minecraft/config/claudechat.properties. Created with defaults on first launch; /claude model and /claude effort save here. */
public class ClaudeConfig {
	public static final List<String> MODEL_SUGGESTIONS = List.of("haiku", "sonnet", "opus", "fable");
	public static final List<String> EFFORT_LEVELS = List.of("low", "medium", "high", "xhigh", "max");

	/** What Claude may do beyond reading. Each tier's claude flags are in {@link ClaudeCli}. */
	public enum Permissions {
		/** Read and search only; game commands only if they change nothing. */
		READ_ONLY("read_only", "Read only"),
		/** Also edits files, runs shell and game commands, each after the player clicks Allow in chat. */
		ASK("ask", "Ask for approval"),
		/** Everything, without asking. */
		FULL("full", "Full access");

		public final String id;
		public final String label;

		Permissions(String id, String label) {
			this.id = id;
			this.label = label;
		}

		public static Permissions parse(String id) {
			for (Permissions p : values()) {
				if (p.id.equalsIgnoreCase(id.trim())) {
					return p;
				}
			}
			return null;
		}
	}

	/** Full path to claude.exe. Empty = look on PATH, then in the Claude desktop app's bundled copy. */
	public String claudePath = "";
	/** A model alias (haiku, sonnet, opus, fable) or a full model ID. */
	public String model = "sonnet";
	/** low, medium, high, xhigh or max. Empty = Claude Code's default. */
	public String effort = "";
	/** Chat messages starting with this are sent to Claude instead of the server. */
	public String chatPrefix = "@claude";
	public int timeoutSeconds = 300;
	/** Decompile Minecraft and installed mods in the background so Claude can search the real code. */
	public boolean buildKnowledge = true;
	/** Mod ids to always decompile, or never decompile, overriding the automatic choice. Set with /claude knowledge. */
	public final Set<String> knowledgeInclude = new TreeSet<>();
	public final Set<String> knowledgeExclude = new TreeSet<>();
	/** Extra instructions appended to the system prompt, e.g. "Answer like a pirate." */
	public String extraInstructions = "";
	/** Set with /claude permissions; turning on Full access needs a click on a confirm button. */
	public Permissions permissions = Permissions.READ_ONLY;
	/**
	 * Off: Claude's game commands run with the player's own permission level, so in a world without cheats it can't
	 * run cheat commands. On: they run at command-block level (still as the player), whatever the world allows.
	 */
	public boolean cheats = false;

	public static Path dir() {
		return FabricLoader.getInstance().getConfigDir().resolve(ClaudeChatClient.MOD_ID);
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(ClaudeChatClient.MOD_ID + ".properties");
	}

	public static ClaudeConfig load() {
		ClaudeConfig config = new ClaudeConfig();
		Properties props = new Properties();
		if (Files.exists(file())) {
			try (Reader reader = Files.newBufferedReader(file())) {
				props.load(reader);
			} catch (IOException e) {
				ClaudeChatClient.LOGGER.warn("Could not read {}", file(), e);
			}
		}

		// Version 1 made Sonnet the default. Older files stored the old default (haiku) explicitly, so move them over.
		boolean preVersion1 = props.containsKey("model") && !props.containsKey("configVersion");
		if (preVersion1 && "haiku".equals(props.getProperty("model").trim())) {
			props.setProperty("model", "sonnet");
		}

		config.claudePath = props.getProperty("claudePath", config.claudePath).trim();
		config.model = props.getProperty("model", config.model).trim();
		config.effort = props.getProperty("effort", config.effort).trim();
		config.chatPrefix = props.getProperty("chatPrefix", config.chatPrefix).trim();
		config.timeoutSeconds = parseInt(props, "timeoutSeconds", config.timeoutSeconds);
		config.buildKnowledge = Boolean.parseBoolean(props.getProperty("buildKnowledge", "" + config.buildKnowledge).trim());
		config.extraInstructions = props.getProperty("extraInstructions", config.extraInstructions).trim();
		config.knowledgeInclude.addAll(parseList(props.getProperty("knowledgeInclude", "")));
		config.knowledgeExclude.addAll(parseList(props.getProperty("knowledgeExclude", "")));
		// Version 2 added these. A missing or unknown value is read only, so nobody gets more access by accident.
		Permissions permissions = Permissions.parse(props.getProperty("permissions", ""));
		config.permissions = permissions != null ? permissions : Permissions.READ_ONLY;
		config.cheats = Boolean.parseBoolean(props.getProperty("cheats", "false").trim());
		if (config.chatPrefix.isEmpty()) {
			config.chatPrefix = "@claude";
		}

		// Write back so every option is visible in the file.
		config.save();
		return config;
	}

	public void save() {
		Properties props = new Properties();
		props.setProperty("configVersion", "2");
		props.setProperty("claudePath", claudePath);
		props.setProperty("model", model);
		props.setProperty("effort", effort);
		props.setProperty("chatPrefix", chatPrefix);
		props.setProperty("timeoutSeconds", "" + timeoutSeconds);
		props.setProperty("buildKnowledge", "" + buildKnowledge);
		props.setProperty("extraInstructions", extraInstructions);
		props.setProperty("knowledgeInclude", String.join(",", knowledgeInclude));
		props.setProperty("knowledgeExclude", String.join(",", knowledgeExclude));
		props.setProperty("permissions", permissions.id);
		props.setProperty("cheats", "" + cheats);
		try (Writer writer = Files.newBufferedWriter(file())) {
			props.store(writer, "Claude Chat settings. claudePath empty = auto-detect. model: haiku, sonnet, opus, fable, or a full model ID."
				+ " effort: low, medium, high, xhigh, max, or empty for the default. permissions: read_only, ask or full."
				+ " cheats: true lets Claude run cheat commands even where you can't.");
		} catch (IOException e) {
			ClaudeChatClient.LOGGER.warn("Could not write {}", file(), e);
		}
	}

	private static List<String> parseList(String value) {
		return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
	}

	private static int parseInt(Properties props, String key, int fallback) {
		try {
			return Integer.parseInt(props.getProperty(key, "" + fallback).trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
