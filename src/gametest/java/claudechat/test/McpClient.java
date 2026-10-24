package claudechat.test;

import claudechat.McpServer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Claude's side of the mod's MCP server, as {@code claude -p} talks to it: JSON-RPC over HTTP to the URL, with the
 * bearer token, from a question's {@code --mcp-config}. Every failure is loud: an HTTP status other than 200, a
 * JSON-RPC error or a body that isn't the expected shape throws, so a check never reads "missing" as "empty".
 * <p>
 * Calls block until the server answers; world tools wait on the server thread, so call them through
 * {@link Game#call} rather than from the test thread.
 */
final class McpClient {
	private final URI url;
	private final String authorization;

	private McpClient(URI url, String authorization) {
		this.url = url;
		this.authorization = authorization;
	}

	/** The client a question's {@code --mcp-config} file describes. */
	static McpClient fromClaudeConfig(String json) {
		JsonObject server = JsonParser.parseString(json).getAsJsonObject()
			.getAsJsonObject("mcpServers").getAsJsonObject(McpServer.NAME);
		return new McpClient(URI.create(server.get("url").getAsString()),
			server.getAsJsonObject("headers").get("Authorization").getAsString());
	}

	/** A JSON-RPC error response: the request reached the server and was refused. */
	static final class JsonRpcError extends RuntimeException {
		final int code;

		JsonRpcError(int code, String message) {
			super("JSON-RPC error " + code + ": " + message);
			this.code = code;
		}
	}

	/** The result of one request; throws {@link JsonRpcError} for an error response. */
	JsonObject result(String method, JsonObject params) {
		JsonObject response = response(method, params);
		if (response.has("error")) {
			JsonObject error = response.getAsJsonObject("error");
			throw new JsonRpcError(error.get("code").getAsInt(), error.get("message").getAsString());
		}

		JsonElement result = response.get("result");
		if (result == null || !result.isJsonObject()) {
			throw new IllegalStateException(method + " answered without a result object: " + response);
		}

		return result.getAsJsonObject();
	}

	/** The names in tools/list. */
	List<String> toolNames() {
		List<String> names = new ArrayList<>();
		result("tools/list", new JsonObject()).getAsJsonArray("tools")
			.forEach(tool -> names.add(tool.getAsJsonObject().get("name").getAsString()));
		return names;
	}

	/** tools/call as Claude makes it; the tool's own failure is an {@code isError} result, not an exception. */
	ToolResult callTool(String name, JsonObject arguments) {
		JsonObject params = new JsonObject();
		params.addProperty("name", name);
		params.add("arguments", arguments);
		return ToolResult.of(result("tools/call", params));
	}

	/** The HTTP status a ping gets: 401 once this client's token is revoked. */
	int pingStatus() {
		return send(request("ping", new JsonObject())).statusCode();
	}

	private JsonObject response(String method, JsonObject params) {
		HttpResponse<String> response = send(request(method, params));
		if (response.statusCode() != 200) {
			throw new IllegalStateException(method + " got HTTP " + response.statusCode() + ": " + response.body());
		}

		JsonElement body = JsonParser.parseString(response.body());
		if (!body.isJsonObject()) {
			throw new IllegalStateException(method + " answered with something other than an object: " + response.body());
		}

		return body.getAsJsonObject();
	}

	private static String request(String method, JsonObject params) {
		JsonObject request = new JsonObject();
		request.addProperty("jsonrpc", "2.0");
		request.addProperty("id", 1);
		request.addProperty("method", method);
		request.add("params", params);
		return request.toString();
	}

	private HttpResponse<String> send(String body) {
		try (HttpClient http = HttpClient.newHttpClient()) {
			return http.send(HttpRequest.newBuilder(url)
				.header("Authorization", authorization)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build(), HttpResponse.BodyHandlers.ofString());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}
