package claudechat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tool arguments: parsed once, with wrong types as errors rather than defaults. */
class ParamTest {
	private static final Param<OptionalInt> NEAR = Param.optionalInteger("max_distance", "", 1, 1000);
	private static final Param<List<String>> PIECES = Param.texts("with_pieces", "");
	private static final Param<Visit.Filter> VISITED = Param.choice("visited", "Which.", Visit.Filter.ANY);
	private static final Param<Boolean> LAYERS = Param.flag("layers", "", false);
	private static final Param<String> CONTENT = Param.requiredExactText("content", "");

	@Test
	void optionalNumbersAreEmptyOnlyWhenNotGiven() {
		assertEquals(OptionalInt.empty(), parse("{}").get(NEAR));
		assertEquals(OptionalInt.empty(), parse("{\"max_distance\": null}").get(NEAR));
		assertEquals(OptionalInt.of(1000), parse("{\"max_distance\": 5000}").get(NEAR));
		assertError("{\"max_distance\": \"500 blocks\"}", "max_distance must be a whole number");
	}

	@Test
	void textListsComeAsArraysOrCommaSeparated() {
		assertEquals(List.of("ship", "tower"), parse("{\"with_pieces\": [\" ship\", \"\", \"tower\"]}").get(PIECES));
		assertEquals(List.of("ship", "tower"), parse("{\"with_pieces\": \"ship, tower\"}").get(PIECES));
		assertError("{\"with_pieces\": [{}]}", "with_pieces must be text");
	}

	@Test
	void choicesAreTheEnumsNamesAndTheSchemaListsThem() {
		assertEquals(Visit.Filter.UNVISITED, parse("{\"visited\": \"Unvisited\"}").get(VISITED));
		assertEquals(Visit.Filter.ANY, parse("{}").get(VISITED));
		assertError("{\"visited\": \"never\"}", "visited must be one of any, visited, unvisited");
		assertEquals("[\"any\",\"visited\",\"unvisited\"]", VISITED.schema().getAsJsonArray("enum").toString());
		assertTrue(VISITED.schema().get("description").getAsString().endsWith("Default any."));
	}

	@Test
	void flagsMustBeBooleans() {
		assertEquals(true, parse("{\"layers\": true}").get(LAYERS));
		assertError("{\"layers\": \"yes\"}", "layers must be true or false");
	}

	@Test
	void exactTextKeepsEmptyAndSpaces() {
		List<Param<?>> params = List.of(CONTENT);
		assertEquals("", Arguments.parse(params, JsonParser.parseString("{\"content\": \"\"}").getAsJsonObject()).get(CONTENT));
		assertEquals("  x\n", Arguments.parse(params, JsonParser.parseString("{\"content\": \"  x\\n\"}").getAsJsonObject()).get(CONTENT));
		assertError("{}", "content is required.");
	}

	@Test
	void undeclaredArgumentsCantBeRead() {
		Arguments arguments = Arguments.parse(List.of(LAYERS), new JsonObject());

		assertThrows(IllegalStateException.class, () -> arguments.get(NEAR));
	}

	private static Arguments parse(String json) {
		return Arguments.parse(List.of(NEAR, PIECES, VISITED, LAYERS), JsonParser.parseString(json).getAsJsonObject());
	}

	private static void assertError(String json, String message) {
		JsonObject arguments = JsonParser.parseString(json).getAsJsonObject();
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
			() -> Arguments.parse(List.of(NEAR, PIECES, VISITED, LAYERS, CONTENT), arguments));

		assertTrue(e.getMessage().startsWith(message), e.getMessage());
	}
}
