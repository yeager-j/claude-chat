package claudechat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The MCP server's HTTP and JSON-RPC handling, over a real socket. No tool is called, so no tools are needed. */
class McpServerTest {
	private static McpServer server;
	private static int port;
	private static String authorization;

	@BeforeAll
	static void start() {
		server = new McpServer(null, null, null, null);
		server.start();
		JsonObject minecraft = JsonParser.parseString(server.claudeConfig()).getAsJsonObject()
			.getAsJsonObject("mcpServers").getAsJsonObject("minecraft");
		port = URI.create(minecraft.get("url").getAsString()).getPort();
		authorization = minecraft.getAsJsonObject("headers").get("Authorization").getAsString();
	}

	@AfterAll
	static void stop() {
		server.stop();
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

	private static String initialize(String version) throws IOException {
		String request = "{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"initialize\", \"params\": {\"protocolVersion\": \"" + version + "\"}}";
		return body(exchange(post(authorization, request))).getAsJsonObject("result").get("protocolVersion").getAsString();
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
