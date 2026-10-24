package claudechat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Reads the {@code --output-format stream-json} events of one {@code claude -p} run: Claude's progress notes as they
 * arrive, and the final result. One instance per run; {@link #read} runs on its own thread while the result and the
 * start of the raw output can be read from another.
 *
 * <p>Each text block arrives as its own event, and only afterwards is it known whether a tool call follows it. So text
 * is held back: if more activity follows, it was a progress note; the last one is the answer, which also arrives in the
 * result event.
 */
final class ClaudeStream {
	/** How much raw output to keep for the error message when no result arrives. */
	private static final int HEAD_LIMIT = 2000;

	private final Consumer<String> onProgress;
	private final StringBuilder head = new StringBuilder();
	private volatile ClaudeResult result;
	private String pendingText;

	/** {@code onProgress} gets each progress note, on the reading thread. */
	ClaudeStream(Consumer<String> onProgress) {
		this.onProgress = onProgress;
	}

	/** Reads events until the stream ends. Malformed events are skipped, so one bad line never stops the reader. */
	void read(InputStream in) {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				accept(line);
			}
		} catch (IOException e) {
			ClaudeChatClient.LOGGER.debug("Claude output stream closed", e);
		}
	}

	/** Handles one line of output. */
	void accept(String line) {
		recordHead(line);

		try {
			JsonElement parsed = JsonParser.parseString(line);
			if (parsed.isJsonObject()) {
				handle(parsed.getAsJsonObject());
			}
		} catch (RuntimeException e) {
			ClaudeChatClient.LOGGER.debug("Skipped an unreadable Claude output line: {}", line, e);
		}
	}

	/** The result event, once it has arrived. */
	Optional<ClaudeResult> result() {
		return Optional.ofNullable(result);
	}

	/** The first lines of raw output, to explain a run that ended without a result. */
	synchronized String head() {
		return head.toString().strip();
	}

	private synchronized void recordHead(String line) {
		if (head.length() < HEAD_LIMIT) {
			head.append(line).append('\n');
		}
	}

	private void handle(JsonObject event) {
		String type = ClaudeResult.string(event, "type").orElse("");
		switch (type) {
			case "result" -> result = ClaudeResult.parse(event);
			case "assistant" -> activity(textOf(event), hasBlock(event, "tool_use"));
			// Tool results come back to Claude as "user" events.
			case "user" -> activity(null, true);
			default -> {
			}
		}
	}

	/** Text held back from an earlier event was a progress note if anything else happened after it. */
	private void activity(String text, boolean toolActivity) {
		if (pendingText != null && (text != null || toolActivity)) {
			onProgress.accept(pendingText);
			pendingText = null;
		}

		if (text != null) {
			pendingText = text;
		}
	}

	/** The event's text blocks joined, or null if it has none. */
	private static String textOf(JsonObject event) {
		StringBuilder text = new StringBuilder();
		for (JsonObject block : contentBlocks(event)) {
			if (isBlock(block, "text")) {
				ClaudeResult.string(block, "text").ifPresent(text::append);
			}
		}

		String stripped = text.toString().strip();
		return stripped.isEmpty() ? null : stripped;
	}

	private static boolean hasBlock(JsonObject event, String blockType) {
		for (JsonObject block : contentBlocks(event)) {
			if (isBlock(block, blockType)) {
				return true;
			}
		}

		return false;
	}

	private static boolean isBlock(JsonObject block, String blockType) {
		return ClaudeResult.string(block, "type").filter(blockType::equals).isPresent();
	}

	/** The object blocks of {@code message.content}; anything else in the array is ignored. */
	private static Iterable<JsonObject> contentBlocks(JsonObject event) {
		JsonElement message = event.get("message");
		if (message == null || !message.isJsonObject()) {
			return List.of();
		}

		JsonElement content = message.getAsJsonObject().get("content");
		if (content == null || !content.isJsonArray()) {
			return List.of();
		}

		return ((JsonArray) content).asList().stream()
			.filter(JsonElement::isJsonObject)
			.map(JsonElement::getAsJsonObject)
			.toList();
	}
}
