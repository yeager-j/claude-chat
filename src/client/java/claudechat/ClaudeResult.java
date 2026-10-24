package claudechat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The final {@code "result"} event of a {@code claude -p} run, parsed once. Missing or malformed fields read as empty,
 * so a result always exists once the event arrived.
 *
 * @param text      the answer; empty when the run failed before answering
 * @param isError   whether Claude Code reports the run as failed
 * @param subtype   Claude Code's kind of result, e.g. {@code success} or {@code error_max_turns}
 * @param turns     how many turns ran; 0 means nothing was sent to the model and no tool ran
 * @param errors    Claude Code's own error messages, if any
 * @param sessionId the conversation to resume for a follow-up question
 */
record ClaudeResult(String text, boolean isError, String subtype, int turns, List<String> errors, Optional<String> sessionId) {
	/** How Claude Code words a {@code --resume} id it has no conversation for, e.g. after its history was cleared. */
	private static final String MISSING_SESSION = "No conversation found";

	static ClaudeResult parse(JsonObject event) {
		return new ClaudeResult(
			string(event, "result").orElse(""),
			bool(event, "is_error"),
			string(event, "subtype").orElse(""),
			integer(event, "num_turns"),
			strings(event, "errors"),
			string(event, "session_id").filter(id -> !id.isBlank()));
	}

	/** Did {@code --resume} name a conversation Claude Code doesn't have? Nothing ran then, so asking again is safe. */
	boolean missingSession() {
		return isError && turns == 0 && errors.stream().anyMatch(error -> error.startsWith(MISSING_SESSION));
	}

	/** Claude Code's own explanation of a failed run, for the player. */
	String errorMessage() {
		if (!text.isBlank()) {
			return text;
		}

		if (!errors.isEmpty()) {
			return String.join("; ", errors);
		}

		return subtype.isEmpty() ? "Claude Code reported an error." : "Claude Code reported an error (" + subtype + ").";
	}

	/** A string-valued field; empty when it's absent or not a string. */
	static Optional<String> string(JsonObject object, String key) {
		JsonElement value = object.get(key);
		boolean isString = value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
		return isString ? Optional.of(value.getAsString()) : Optional.empty();
	}

	private static boolean bool(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
	}

	private static int integer(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? value.getAsInt() : 0;
	}

	private static List<String> strings(JsonObject object, String key) {
		JsonElement value = object.get(key);
		if (value == null || !value.isJsonArray()) {
			return List.of();
		}

		List<String> out = new ArrayList<>();
		for (JsonElement element : (JsonArray) value) {
			if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
				out.add(element.getAsString());
			}
		}

		return List.copyOf(out);
	}
}
