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
		if (config.chatPrefix.isEmpty()) {
			config.chatPrefix = "@claude";
		}

		// Write back so every option is visible in the file.
		config.save();
		return config;
	}

	public void save() {
		Properties props = new Properties();
		props.setProperty("configVersion", "1");
		props.setProperty("claudePath", claudePath);
		props.setProperty("model", model);
		props.setProperty("effort", effort);
		props.setProperty("chatPrefix", chatPrefix);
		props.setProperty("timeoutSeconds", "" + timeoutSeconds);
		props.setProperty("buildKnowledge", "" + buildKnowledge);
		props.setProperty("extraInstructions", extraInstructions);
		props.setProperty("knowledgeInclude", String.join(",", knowledgeInclude));
		props.setProperty("knowledgeExclude", String.join(",", knowledgeExclude));
		try (Writer writer = Files.newBufferedWriter(file())) {
			props.store(writer, "Claude Chat settings. claudePath empty = auto-detect. model: haiku, sonnet, opus, fable, or a full model ID."
				+ " effort: low, medium, high, xhigh, max, or empty for the default.");
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
