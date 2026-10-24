package claudechat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

/**
 * One argument of a tool: its JSON schema, the default and bounds Claude is told about, and how a call's value is
 * parsed. The same object writes the schema and reads the value, so the two can't disagree. A value of the wrong type
 * is an error, never the default; a missing or JSON-null value is the default, or an error if the argument is required.
 *
 * @param <T> what the argument parses to
 */
public final class Param<T> {
	/** Parses a present value; throws {@link IllegalArgumentException} with what's wrong, phrased after the name. */
	@FunctionalInterface
	public interface Reader<T> {
		T read(JsonElement value);
	}

	private final String name;
	private final JsonObject schema;
	private final boolean required;
	private final T absent;
	private final Reader<T> reader;

	private Param(String name, JsonObject schema, boolean required, T absent, Reader<T> reader) {
		this.name = name;
		this.schema = schema;
		this.required = required;
		this.absent = absent;
		this.reader = reader;
	}

	public String name() {
		return name;
	}

	/** The argument's JSON schema, for the tool's inputSchema. */
	public JsonObject schema() {
		return schema.deepCopy();
	}

	public boolean required() {
		return required;
	}

	/** This argument's value in a call's arguments. */
	T read(JsonObject arguments) {
		JsonElement value = arguments.get(name);
		if (value == null || value.isJsonNull()) {
			if (required) {
				throw new IllegalArgumentException(name + " is required.");
			}

			return absent;
		}

		try {
			return reader.read(value);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(name + " " + e.getMessage(), e);
		}
	}

	// --- kinds of argument ----------------------------------------------------------------------------------------

	/** Optional text, trimmed; "" when not given. */
	public static Param<String> text(String name, String description) {
		return new Param<>(name, schema("string", description), false, "", value -> string(value).trim());
	}

	/** Text that must be given and not blank, trimmed. */
	public static Param<String> requiredText(String name, String description) {
		return new Param<>(name, schema("string", description), true, null, value -> {
			String text = string(value).trim();
			if (text.isEmpty()) {
				throw new IllegalArgumentException("must not be empty.");
			}

			return text;
		});
	}

	/** Text that must be given, exactly as sent: it may be empty or start with spaces, like a file's new content. */
	public static Param<String> requiredExactText(String name, String description) {
		return new Param<>(name, schema("string", description), true, null, Param::string);
	}

	/**
	 * A whole number, {@code fallback} when not given, clamped to {@code min..max}. The schema and description state
	 * the default and the bounds.
	 */
	public static Param<Integer> integer(String name, String description, int fallback, int min, int max) {
		JsonObject schema = schema("integer", description + " Default " + fallback + ", " + min + " to " + max + ".");
		schema.addProperty("default", fallback);
		schema.addProperty("minimum", min);
		schema.addProperty("maximum", max);
		return new Param<>(name, schema, false, fallback, value -> Math.clamp(wholeNumber(value), min, max));
	}

	/** A whole number clamped to {@code min..max}; empty when not given. */
	public static Param<OptionalInt> optionalInteger(String name, String description, int min, int max) {
		JsonObject schema = schema("integer", description);
		schema.addProperty("minimum", min);
		schema.addProperty("maximum", max);
		return new Param<>(name, schema, false, OptionalInt.empty(), value -> OptionalInt.of(Math.clamp(wholeNumber(value), min, max)));
	}

	/** A whole number; empty when not given. */
	public static Param<OptionalInt> optionalInteger(String name, String description) {
		return new Param<>(name, schema("integer", description), false, OptionalInt.empty(), value -> OptionalInt.of(wholeNumber(value)));
	}

	/** A whole number that must be given. */
	public static Param<Integer> requiredInteger(String name, String description) {
		return new Param<>(name, schema("integer", description), true, null, Param::wholeNumber);
	}

	/** true or false, {@code fallback} when not given. */
	public static Param<Boolean> flag(String name, String description, boolean fallback) {
		JsonObject schema = schema("boolean", description + " Default " + fallback + ".");
		schema.addProperty("default", fallback);
		return new Param<>(name, schema, false, fallback, value -> {
			if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
				return value.getAsBoolean();
			}

			throw new IllegalArgumentException("must be true or false, not " + value + ".");
		});
	}

	/** Texts, from an array of strings or one comma-separated string; trimmed, blanks dropped. Empty when not given. */
	public static Param<List<String>> texts(String name, String description) {
		JsonObject schema = schema("array", description);
		JsonObject items = new JsonObject();
		items.addProperty("type", "string");
		schema.add("items", items);
		return new Param<>(name, schema, false, List.of(), value -> {
			List<String> texts = new ArrayList<>();
			if (value.isJsonArray()) {
				value.getAsJsonArray().forEach(element -> texts.add(string(element)));
			} else {
				texts.addAll(Arrays.asList(string(value).split(",")));
			}

			return texts.stream().map(String::trim).filter(text -> !text.isEmpty()).toList();
		});
	}

	/** One of an enum's constants, named in lower case ("unvisited" for UNVISITED); {@code fallback} when not given. */
	public static <E extends Enum<E>> Param<E> choice(String name, String description, E fallback) {
		E[] constants = fallback.getDeclaringClass().getEnumConstants();
		List<String> names = Arrays.stream(constants).map(Param::choiceName).toList();
		JsonObject schema = schema("string", description + " Default " + choiceName(fallback) + ".");
		JsonArray values = new JsonArray();
		names.forEach(values::add);
		schema.add("enum", values);
		return new Param<>(name, schema, false, fallback, value -> {
			String given = string(value).trim().toLowerCase(Locale.ROOT);
			for (E constant : constants) {
				if (choiceName(constant).equals(given)) {
					return constant;
				}
			}

			throw new IllegalArgumentException("must be one of " + String.join(", ", names) + ", not " + value + ".");
		});
	}

	/** A JSON object that must be given. */
	public static Param<JsonObject> requiredObject(String name, String description) {
		return new Param<>(name, schema("object", description), true, null, value -> {
			if (!value.isJsonObject()) {
				throw new IllegalArgumentException("must be an object, not " + value + ".");
			}

			return value.getAsJsonObject();
		});
	}

	/** An optional argument with its own schema and reader; {@code absent} when not given. */
	public static <T> Param<T> custom(String name, JsonObject schema, T absent, Reader<T> reader) {
		return new Param<>(name, schema, false, absent, reader);
	}

	// --- reading values -------------------------------------------------------------------------------------------

	/** A string, number or boolean as text; objects and arrays aren't text. */
	private static String string(JsonElement value) {
		if (value.isJsonPrimitive()) {
			return value.getAsString();
		}

		throw new IllegalArgumentException("must be text, not " + value + ".");
	}

	/** A JSON number, or a string holding one ("5"), with no fraction; "five" and "500 blocks" are errors. */
	private static int wholeNumber(JsonElement value) {
		if (value.isJsonPrimitive()) {
			JsonPrimitive primitive = value.getAsJsonPrimitive();
			try {
				if (primitive.isNumber()) {
					return primitive.getAsBigDecimal().intValueExact();
				}

				if (primitive.isString()) {
					return new BigDecimal(primitive.getAsString().trim()).intValueExact();
				}
			} catch (NumberFormatException | ArithmeticException e) {
				// not a whole number in range; reported below
			}
		}

		throw new IllegalArgumentException("must be a whole number, not " + value + ".");
	}

	private static String choiceName(Enum<?> constant) {
		return constant.name().toLowerCase(Locale.ROOT);
	}

	private static JsonObject schema(String type, String description) {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", type);
		if (!description.isEmpty()) {
			schema.addProperty("description", description);
		}

		return schema;
	}
}
