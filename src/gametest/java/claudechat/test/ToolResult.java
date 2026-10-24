package claudechat.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * One tools/call result as MCP shapes it: a text item first (the tool's data as JSON, or its error), then any images,
 * with {@code isError} on failures. Reading the side that isn't there throws, so a failed call can't pass for data.
 *
 * @param images the PNG bytes of each image item, in order
 */
record ToolResult(boolean isError, String text, List<byte[]> images) {
	private static final String IMAGE_TYPE = "image/png";

	static ToolResult of(JsonObject result) {
		JsonArray content = result.getAsJsonArray("content");
		if (content == null || content.isEmpty()) {
			throw new IllegalStateException("tool result without content: " + result);
		}

		JsonObject first = content.get(0).getAsJsonObject();
		if (!"text".equals(first.get("type").getAsString())) {
			throw new IllegalStateException("tool result doesn't start with text: " + result);
		}

		List<byte[]> images = new ArrayList<>();
		for (JsonElement item : content.asList().subList(1, content.size())) {
			JsonObject image = item.getAsJsonObject();
			if (!"image".equals(image.get("type").getAsString()) || !IMAGE_TYPE.equals(image.get("mimeType").getAsString())) {
				throw new IllegalStateException("content after the text isn't a PNG image: " + image.get("type"));
			}

			images.add(Base64.getDecoder().decode(image.get("data").getAsString()));
		}

		boolean isError = result.has("isError") && result.get("isError").getAsBoolean();
		return new ToolResult(isError, first.get("text").getAsString(), List.copyOf(images));
	}

	/** The tool's data; throws with the error text if the call failed. */
	JsonElement data() {
		if (isError) {
			throw new IllegalStateException(text);
		}

		return JsonParser.parseString(text);
	}

	/** The data as an object; throws if it's anything else. */
	JsonObject object() {
		JsonElement data = data();
		if (!data.isJsonObject()) {
			throw new IllegalStateException("expected an object, got " + data);
		}

		return data.getAsJsonObject();
	}

	/** The error text Claude gets; throws if the call succeeded. */
	String error() {
		if (!isError) {
			throw new AssertionError("expected an error, got " + text);
		}

		return text;
	}
}
