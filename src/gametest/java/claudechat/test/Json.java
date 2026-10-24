package claudechat.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Optional;

/** Reading tool results by field, so checks don't depend on field order or the display text around a value. */
final class Json {
	private Json() {
	}

	/** The array's objects; fails if an element is anything else. */
	static List<JsonObject> objects(JsonArray array) {
		return array.asList().stream().map(JsonElement::getAsJsonObject).toList();
	}

	/** The array's strings; fails if an element is anything else. */
	static List<String> strings(JsonArray array) {
		return array.asList().stream().map(JsonElement::getAsString).toList();
	}

	/** A string field, or empty when it's missing. */
	static Optional<String> string(JsonObject object, String key) {
		return object != null && object.has(key) ? Optional.of(object.get(key).getAsString()) : Optional.empty();
	}

	/** Whether the field is there and holds exactly {@code value}. */
	static boolean is(JsonObject object, String key, String value) {
		return string(object, key).filter(value::equals).isPresent();
	}

	/** The result objects whose {@code key} holds {@code value}. */
	static List<JsonObject> where(JsonArray results, String key, String value) {
		return objects(results).stream().filter(result -> is(result, key, value)).toList();
	}

	/** A share such as "80%", as a number; fails on anything else. */
	static int percent(JsonElement share) {
		String text = share.getAsString();
		if (!text.endsWith("%")) {
			throw new AssertionError("not a percentage: " + share);
		}

		return Integer.parseInt(text.substring(0, text.length() - 1));
	}
}
