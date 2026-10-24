package claudechat;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeStreamTest {
	private final List<String> notes = new ArrayList<>();

	private ClaudeStream read(String... lines) {
		ClaudeStream stream = new ClaudeStream(notes::add);
		byte[] bytes = String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
		stream.read(new ByteArrayInputStream(bytes));
		return stream;
	}

	private static String text(String text) {
		return "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}}";
	}

	private static final String TOOL_USE = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Grep\"}]}}";
	private static final String TOOL_RESULT = "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\"}]}}";
	private static final String SUCCESS = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"num_turns\":3,"
		+ "\"result\":\"Use a crafting table.\",\"session_id\":\"abc\"}";

	@Test
	void textFollowedByToolActivityIsANoteAndTheLastTextIsTheAnswer() {
		ClaudeStream stream = read(
			"{\"type\":\"system\",\"subtype\":\"init\"}",
			text("Searching the crafter code"),
			TOOL_USE,
			TOOL_RESULT,
			text("Found it, checking hopper logic"),
			text("Use a crafting table."),
			SUCCESS);

		assertEquals(List.of("Searching the crafter code", "Found it, checking hopper logic"), notes);
		ClaudeResult result = stream.result().orElseThrow();
		assertEquals("Use a crafting table.", result.text());
		assertEquals(Optional.of("abc"), result.sessionId());
		assertFalse(result.isError());
	}

	@Test
	void malformedEventsAreSkippedWithoutStoppingTheReader() {
		ClaudeStream stream = read(
			"not json",
			"[1, 2]",
			"{\"type\":\"assistant\",\"message\":{\"content\":[{\"text\":\"block without a type\"}, 7, null]}}",
			"{\"type\":\"assistant\",\"message\":\"just a string\"}",
			"{\"type\":{\"nested\":true}}",
			text("Checking the data"),
			"{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":5}]}}",
			TOOL_RESULT,
			SUCCESS);

		assertEquals(List.of("Checking the data"), notes);
		assertTrue(stream.result().isPresent());
	}

	@Test
	void noResultKeepsTheStartOfTheOutput() {
		ClaudeStream stream = read("Error: something went wrong");

		assertTrue(stream.result().isEmpty());
		assertEquals("Error: something went wrong", stream.head());
	}

	@Test
	void missingSessionIsRecognized() {
		// What claude -p --resume printed for an id it has no conversation for, trimmed.
		ClaudeStream stream = read("{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"duration_ms\":0,"
			+ "\"is_error\":true,\"num_turns\":0,\"stop_reason\":null,\"session_id\":\"00000000-0000-0000-0000-000000000000\","
			+ "\"errors\":[\"No conversation found with session ID: 00000000-0000-0000-0000-000000000000\"]}");

		ClaudeResult result = stream.result().orElseThrow();
		assertTrue(result.missingSession());
		assertEquals("No conversation found with session ID: 00000000-0000-0000-0000-000000000000", result.errorMessage());
	}

	@Test
	void otherErrorsAreNotRetriedAndKeepTheirMessage() {
		ClaudeStream stream = read(
			"{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":true,\"num_turns\":12,\"session_id\":\"abc\"}");

		ClaudeResult result = stream.result().orElseThrow();
		assertFalse(result.missingSession());
		assertEquals("Claude Code reported an error (error_max_turns).", result.errorMessage());
	}

	@Test
	void apiErrorTextIsTheMessage() {
		ClaudeStream stream = read("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"num_turns\":1,"
			+ "\"result\":\"API Error: 529 Overloaded\"}");

		ClaudeResult result = stream.result().orElseThrow();
		assertFalse(result.missingSession());
		assertEquals("API Error: 529 Overloaded", result.errorMessage());
		assertEquals(Optional.empty(), result.sessionId());
	}
}
