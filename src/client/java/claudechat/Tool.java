package claudechat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.Base64;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * One MCP tool: what Claude is told about it, what it needs before it can run, and what a call does. {@link McpServer}
 * serves a list of these and knows nothing about any one of them. Availability is checked when the tools are listed
 * and again on every call, so a tool listed while a world was open can't run after it closes.
 *
 * @param description told to Claude in tools/list; read at each listing, since it may describe the question's policy
 * @param params      the arguments, in the order the schema lists them
 */
public record Tool(String name, Function<Context, String> description, List<Param<?>> params, Requirement requirement, Handler handler) {
	/** A tool with a fixed description. */
	public static Tool of(String name, String description, Requirement requirement, List<Param<?>> params, Handler handler) {
		return new Tool(name, context -> description, params, requirement, handler);
	}

	/** Runs a call whose arguments have been parsed. Throws with a message for Claude when the call fails. */
	@FunctionalInterface
	public interface Handler {
		Result call(Arguments arguments, Context context) throws Exception;
	}

	/**
	 * What a tool needs before it can run.
	 *
	 * @param unmet the error Claude gets when it isn't met, e.g. "No single-player world is open."
	 */
	public record Requirement(BooleanSupplier met, String unmet) {
		public static final Requirement NOTHING = new Requirement(() -> true, "");
	}

	/**
	 * The question a call belongs to, which the server knows from the call's token. Tools take the question's
	 * permissions and its approvals from here, never from the live settings.
	 *
	 * @param policy    the permissions the question started with
	 * @param approvals where to ask the player, for this question only
	 */
	public record Context(Policy policy, Approvals approvals) {
	}

	/**
	 * What a call returns: JSON data for Claude, and optionally a PNG image of the same moment.
	 *
	 * @param png null when there's no image
	 */
	public record Result(JsonElement data, byte @Nullable [] png) {
		public static Result of(JsonElement data) {
			return new Result(data, null);
		}

		/** The MCP content blocks: the data as text, then the image. */
		public JsonArray content() {
			JsonArray content = new JsonArray();
			content.add(text(data.toString()));
			if (png != null) {
				JsonObject image = new JsonObject();
				image.addProperty("type", "image");
				image.addProperty("data", Base64.getEncoder().encodeToString(png));
				image.addProperty("mimeType", "image/png");
				content.add(image);
			}

			return content;
		}

		/** One MCP text content block. */
		static JsonObject text(String text) {
			JsonObject block = new JsonObject();
			block.addProperty("type", "text");
			block.addProperty("text", text);
			return block;
		}
	}

	public boolean available() {
		return requirement.met().getAsBoolean();
	}

	/** The tools/list entry for a question with {@code context}: name, description and input schema. */
	public JsonObject definition(Context context) {
		JsonObject properties = new JsonObject();
		JsonArray required = new JsonArray();
		for (Param<?> param : params) {
			properties.add(param.name(), param.schema());
			if (param.required()) {
				required.add(param.name());
			}
		}

		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", properties);
		if (!required.isEmpty()) {
			schema.add("required", required);
		}

		JsonObject definition = new JsonObject();
		definition.addProperty("name", name);
		definition.addProperty("description", description.apply(context));
		definition.add("inputSchema", schema);
		return definition;
	}

	/**
	 * Checks the requirement, parses the arguments and runs the handler.
	 *
	 * @throws IllegalStateException    when the requirement isn't met
	 * @throws IllegalArgumentException when an argument is missing or malformed
	 */
	public Result call(JsonObject arguments, Context context) throws Exception {
		if (!available()) {
			throw new IllegalStateException(requirement.unmet());
		}

		return handler.call(Arguments.parse(params, arguments), context);
	}
}
