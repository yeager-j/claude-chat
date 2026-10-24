package claudechat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The MCP server's HTTP and JSON-RPC handling and the tool contract, over a real socket, with stand-in tools. */
class McpServerTest {
	private static final Param<String> QUERY = Param.requiredText("query", "What to echo.");
	private static final Param<Integer> LIMIT = Param.integer("limit", "Max results.", 5, 1, 20);

	/** Counted down when the block tool starts waiting. */
	private static final CountDownLatch blocking = new CountDownLatch(1);

	private static McpServer server;
	private static int port;
	/** The Authorization header of a Read only grant that stays open. */
	private static String authorization;

	@BeforeAll
	static void start() {
		Tool echo = Tool.of("echo", "Echoes its arguments.", Tool.Requirement.NOTHING, List.of(QUERY, LIMIT), (arguments, context) -> {
			JsonObject out = new JsonObject();
			out.addProperty("query", arguments.get(QUERY));
			out.addProperty("limit", arguments.get(LIMIT));
			return Tool.Result.of(out);
		});
		Tool closed = Tool.of("closed", "Needs a world.", new Tool.Requirement(() -> false, "No world is open."), List.of(),
			(arguments, context) -> Tool.Result.of(new JsonObject()));
		Tool silent = Tool.of("silent", "Fails without a message.", Tool.Requirement.NOTHING, List.of(), (arguments, context) -> {
			throw new IllegalStateException();
		});
		Tool picture = Tool.of("picture", "Returns an image.", Tool.Requirement.NOTHING, List.of(),
			(arguments, context) -> new Tool.Result(new JsonObject(), new byte[] {1, 2, 3}));
		Tool tier = new Tool("tier", context -> "Says the tier: " + context.policy().tier().id, List.of(), Tool.Requirement.NOTHING,
			(arguments, context) -> Tool.Result.of(new JsonPrimitive(context.policy().tier().id)));
		Tool block = Tool.of("block", "Waits until interrupted.", Tool.Requirement.NOTHING, List.of(), (arguments, context) -> {
			blocking.countDown();
			Thread.sleep(60_000);
			return Tool.Result.of(new JsonObject());
		});
		server = new McpServer(List.of(echo, closed, silent, picture, tier, block), () -> false);
		server.start();
		authorization = authorization(server.grant(context(PermissionTier.READ_ONLY)));
	}

	@AfterAll
	static void stop() {
		server.stop();
	}

	@Test
	void eachGrantsCallsGetItsOwnContext() throws IOException {
		try (McpServer.Grant full = server.grant(context(PermissionTier.FULL))) {
			assertEquals("\"full\"", callAs(authorization(full), "tier"));
			assertEquals("\"read_only\"", callAs(authorization, "tier"));
			String listed = body(exchange(post(authorization(full), rpcRequest("tools/list", "{}")))).toString();
			assertTrue(listed.contains("Says the tier: full"), listed);
		}
	}

	@Test
	void aClosedGrantsTokenIsRefused() throws IOException {
		McpServer.Grant grant = server.grant(context(PermissionTier.ASK));
		String token = authorization(grant);
		assertTrue(exchange(post(token, rpcRequest("ping", "{}"))).startsWith("HTTP/1.1 200"));

		grant.close();

		assertTrue(exchange(post(token, rpcRequest("ping", "{}"))).startsWith("HTTP/1.1 401"));
	}

	@Test
	void closingAGrantInterruptsItsCalls() throws Exception {
		McpServer.Grant grant = server.grant(context(PermissionTier.READ_ONLY));
		String token = authorization(grant);
		CompletableFuture<String> call = CompletableFuture.supplyAsync(() -> {
			try {
				return exchange(post(token, rpcRequest("tools/call", "{\"name\": \"block\"}")));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
		assertTrue(blocking.await(10, TimeUnit.SECONDS));

		grant.close();

		JsonObject result = body(call.get(10, TimeUnit.SECONDS)).getAsJsonObject("result");
		assertTrue(result.get("isError").getAsBoolean(), result.toString());
		// The worker that was interrupted serves the next request normally.
		for (int i = 0; i < 20; i++) {
			assertEquals("\"read_only\"", callAs(authorization, "tier"));
		}
	}

	private static Tool.Context context(PermissionTier tier) {
		return new Tool.Context(new Policy(tier, false), new Approvals(note -> { }));
	}

	/** The Authorization header claude gets from the grant's --mcp-config; also sets {@link #port}. */
	private static String authorization(McpServer.Grant grant) {
		JsonObject minecraft = JsonParser.parseString(grant.claudeConfig()).getAsJsonObject()
			.getAsJsonObject("mcpServers").getAsJsonObject(McpServer.NAME);
		port = URI.create(minecraft.get("url").getAsString()).getPort();
		return minecraft.getAsJsonObject("headers").get("Authorization").getAsString();
	}

	@Test
	void wrongTokenIsRefused() throws IOException {
		assertTrue(exchange(post("Bearer nope", "{}")).startsWith("HTTP/1.1 401"));
		assertTrue(exchange(post(authorization + "x", "{}")).startsWith("HTTP/1.1 401"));
	}

	@Test
	void malformedJsonGetsParseError() throws IOException {
		String response = exchange(post(authorization, "{\"jsonrpc\": \"2.0\", \"id\": 1,"));

		assertTrue(response.startsWith("HTTP/1.1 400"), response);
		assertEquals(-32700, body(response).getAsJsonObject("error").get("code").getAsInt());
	}

	@Test
	void nonObjectMessageIsInvalid() throws IOException {
		String response = exchange(post(authorization, "[1]"));

		assertTrue(response.startsWith("HTTP/1.1 200"), response);
		int code = JsonParser.parseString(response.substring(response.indexOf("\r\n\r\n") + 4)).getAsJsonArray()
			.get(0).getAsJsonObject().getAsJsonObject("error").get("code").getAsInt();
		assertEquals(-32600, code);
	}

	@Test
	void oversizedHeadersAndBodiesAreRefused() throws IOException {
		String longHeader = "POST /mcp HTTP/1.1\r\nX-Long: " + "a".repeat(10_000) + "\r\n\r\n";
		assertTrue(exchange(longHeader).startsWith("HTTP/1.1 431"));

		String hugeBody = "POST /mcp HTTP/1.1\r\nAuthorization: " + authorization + "\r\nContent-Length: 999999999999\r\n\r\n";
		assertTrue(exchange(hugeBody).startsWith("HTTP/1.1 413"));
	}

	@Test
	void protocolVersionIsNegotiated() throws IOException {
		assertEquals("2025-03-26", initialize("2025-03-26"));
		assertEquals("2025-06-18", initialize("1999-01-01"));
	}

	@Test
	void onlyAvailableToolsAreListed() throws IOException {
		List<String> names = new ArrayList<>();
		rpc("tools/list", "{}").getAsJsonObject("result").getAsJsonArray("tools")
			.forEach(tool -> names.add(tool.getAsJsonObject().get("name").getAsString()));

		assertEquals(List.of("echo", "silent", "picture", "tier", "block"), names);
	}

	@Test
	void schemaStatesDefaultsAndRequiredArguments() throws IOException {
		JsonObject echo = rpc("tools/list", "{}").getAsJsonObject("result").getAsJsonArray("tools").get(0).getAsJsonObject();
		JsonObject schema = echo.getAsJsonObject("inputSchema");
		JsonObject limit = schema.getAsJsonObject("properties").getAsJsonObject("limit");

		assertEquals("[\"query\"]", schema.getAsJsonArray("required").toString());
		assertEquals(5, limit.get("default").getAsInt());
		assertEquals(20, limit.get("maximum").getAsInt());
		assertTrue(limit.get("description").getAsString().contains("Default 5, 1 to 20."));
	}

	@Test
	void argumentsAreParsedDefaultedAndClamped() throws IOException {
		assertEquals("{\"query\":\"x\",\"limit\":5}", callText("echo", "{\"query\": \" x \"}"));
		assertEquals("{\"query\":\"x\",\"limit\":7}", callText("echo", "{\"query\": \"x\", \"limit\": \"7\"}"));
		assertEquals("{\"query\":\"x\",\"limit\":20}", callText("echo", "{\"query\": \"x\", \"limit\": 100}"));
	}

	@Test
	void badArgumentsAreToolErrors() throws IOException {
		assertToolError("echo", "{}", "query is required.");
		assertToolError("echo", "{\"query\": \"  \"}", "query must not be empty.");
		assertToolError("echo", "{\"query\": \"x\", \"limit\": \"five\"}", "limit must be a whole number, not \"five\".");
		assertToolError("echo", "{\"query\": \"x\", \"limit\": 2.5}", "limit must be a whole number, not 2.5.");
		assertToolError("echo", "{\"query\": [\"x\"]}", "query must be text");
		assertToolError("echo", "[1]", "arguments must be an object");
	}

	@Test
	void unavailableToolFailsWhenCalled() throws IOException {
		assertToolError("closed", "{}", "No world is open.");
	}

	@Test
	void unknownToolIsAProtocolError() throws IOException {
		JsonObject response = rpc("tools/call", "{\"name\": \"no_such_tool\", \"arguments\": {}}");

		assertEquals(-32602, response.getAsJsonObject("error").get("code").getAsInt());
		assertTrue(response.getAsJsonObject("error").get("message").getAsString().contains("no_such_tool"));
	}

	@Test
	void anErrorWithoutAMessageStillSaysWhatFailed() throws IOException {
		JsonObject result = rpc("tools/call", "{\"name\": \"silent\"}").getAsJsonObject("result");
		String text = result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();

		assertTrue(result.get("isError").getAsBoolean());
		assertFalse(text.contains("null"), text);
		assertTrue(text.contains("IllegalStateException"), text);
	}

	@Test
	void imagesFollowTheDataAsContent() throws IOException {
		JsonArray content = rpc("tools/call", "{\"name\": \"picture\"}").getAsJsonObject("result").getAsJsonArray("content");

		assertEquals("text", content.get(0).getAsJsonObject().get("type").getAsString());
		JsonObject image = content.get(1).getAsJsonObject();
		assertEquals("image", image.get("type").getAsString());
		assertEquals("image/png", image.get("mimeType").getAsString());
		assertEquals("AQID", image.get("data").getAsString());
	}

	private static void assertToolError(String tool, String arguments, String message) throws IOException {
		JsonObject result = rpc("tools/call", "{\"name\": \"" + tool + "\", \"arguments\": " + arguments + "}").getAsJsonObject("result");
		String text = result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();

		assertTrue(result.has("isError") && result.get("isError").getAsBoolean(), text);
		assertTrue(text.contains(message), text);
	}

	private static String callText(String tool, String arguments) throws IOException {
		JsonObject result = rpc("tools/call", "{\"name\": \"" + tool + "\", \"arguments\": " + arguments + "}").getAsJsonObject("result");
		assertFalse(result.has("isError"), result.toString());
		return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
	}

	private static String initialize(String version) throws IOException {
		return rpc("initialize", "{\"protocolVersion\": \"" + version + "\"}").getAsJsonObject("result").get("protocolVersion").getAsString();
	}

	private static JsonObject rpc(String method, String params) throws IOException {
		return body(exchange(post(authorization, rpcRequest(method, params))));
	}

	private static String rpcRequest(String method, String params) {
		return "{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"" + method + "\", \"params\": " + params + "}";
	}

	/** A tool's text result, called with {@code authorization}. */
	private static String callAs(String authorization, String tool) throws IOException {
		JsonObject result = body(exchange(post(authorization, rpcRequest("tools/call", "{\"name\": \"" + tool + "\"}"))))
			.getAsJsonObject("result");
		assertFalse(result.has("isError"), result.toString());
		return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
	}

	private static String post(String authorization, String json) {
		byte[] body = json.getBytes(StandardCharsets.UTF_8);
		return "POST /mcp HTTP/1.1\r\nAuthorization: " + authorization + "\r\nContent-Type: application/json\r\n"
			+ "Content-Length: " + body.length + "\r\n\r\n" + json;
	}

	private static JsonObject body(String response) {
		return JsonParser.parseString(response.substring(response.indexOf("\r\n\r\n") + 4)).getAsJsonObject();
	}

	/** Sends a raw request and reads the whole response; the server closes every connection after one exchange. */
	private static String exchange(String request) throws IOException {
		try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
			OutputStream out = socket.getOutputStream();
			out.write(request.getBytes(StandardCharsets.UTF_8));
			out.flush();
			InputStream in = socket.getInputStream();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
