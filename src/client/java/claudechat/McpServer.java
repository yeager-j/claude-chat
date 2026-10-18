package claudechat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A minimal MCP server ("Streamable HTTP" transport, JSON responses only) that gives Claude Code the mod's tools for
 * the whole game session: {@link ModTools} always, {@link WorldTools} only while a single-player world is open. Each
 * question starts a new claude process, which lists the tools afresh. Listens on 127.0.0.1 on a random port and
 * requires a random bearer token, so only the claude process this mod starts can use it. Plain sockets rather than the
 * JDK's HTTP server, which some launchers' trimmed Java runtimes don't include.
 */
public class McpServer {
	private static final String FALLBACK_PROTOCOL = "2025-06-18";

	private final ModTools modTools;
	private final WorldTools worldTools;
	private final String token = newToken();
	private volatile ServerSocket socket;
	private ExecutorService workers;

	public McpServer(ModTools modTools, WorldTools worldTools) {
		this.modTools = modTools;
		this.worldTools = worldTools;
	}

	/** Are the world tools offered right now? */
	public boolean worldOpen() {
		return worldTools.available();
	}

	private static String newToken() {
		byte[] bytes = new byte[24];
		new SecureRandom().nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}

	public synchronized void start() {
		if (socket != null) {
			return;
		}
		try {
			socket = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
		} catch (IOException e) {
			ClaudeChatClient.LOGGER.warn("Could not start the tools server for Claude", e);
			return;
		}
		workers = Executors.newCachedThreadPool(runnable -> {
			Thread thread = new Thread(runnable, "Claude Chat MCP");
			thread.setDaemon(true);
			return thread;
		});
		ServerSocket listening = socket;
		workers.execute(() -> {
			while (!listening.isClosed()) {
				try {
					Socket client = listening.accept();
					workers.execute(() -> handle(client));
				} catch (IOException e) {
					if (!listening.isClosed()) {
						ClaudeChatClient.LOGGER.debug("MCP accept failed", e);
					}
				}
			}
		});
		ClaudeChatClient.LOGGER.info("Tools for Claude served on 127.0.0.1:{}", socket.getLocalPort());
	}

	public synchronized void stop() {
		if (socket != null) {
			try {
				socket.close();
			} catch (IOException ignored) {
			}
			socket = null;
			workers.shutdownNow();
		}
	}

	public boolean isRunning() {
		return socket != null;
	}

	/** The --mcp-config JSON for claude: this server as "minecraft", or no servers at all if it couldn't start. */
	public String claudeConfig() {
		JsonObject servers = new JsonObject();
		ServerSocket s = socket;
		if (s != null) {
			JsonObject minecraft = new JsonObject();
			minecraft.addProperty("type", "http");
			minecraft.addProperty("url", "http://127.0.0.1:" + s.getLocalPort() + "/mcp");
			JsonObject headers = new JsonObject();
			headers.addProperty("Authorization", "Bearer " + token);
			minecraft.add("headers", headers);
			servers.add("minecraft", minecraft);
		}
		JsonObject config = new JsonObject();
		config.add("mcpServers", servers);
		return config.toString();
	}

	// --- HTTP -----------------------------------------------------------------------------------------------------

	private void handle(Socket client) {
		try (client) {
			client.setSoTimeout(120_000);
			InputStream in = client.getInputStream();
			OutputStream out = client.getOutputStream();
			String requestLine = readLine(in);
			if (requestLine == null || requestLine.isEmpty()) {
				return;
			}
			String[] parts = requestLine.split(" ");
			String method = parts[0];
			String path = parts.length > 1 ? parts[1] : "/";
			Map<String, String> headers = new HashMap<>();
			String line;
			while ((line = readLine(in)) != null && !line.isEmpty()) {
				int colon = line.indexOf(':');
				if (colon > 0) {
					headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
				}
			}

			if (!path.startsWith("/mcp")) {
				respond(out, 404, null);
				return;
			}
			if (!("Bearer " + token).equals(headers.get("authorization"))) {
				respond(out, 401, null);
				return;
			}
			if (method.equals("DELETE")) {
				respond(out, 200, null);
				return;
			}
			if (!method.equals("POST")) {
				// No server-to-client event stream; clients fall back to plain request/response.
				respond(out, 405, null);
				return;
			}

			byte[] body = readBody(in, headers);
			JsonElement request = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
			JsonElement response;
			if (request.isJsonArray()) {
				JsonArray responses = new JsonArray();
				for (JsonElement message : request.getAsJsonArray()) {
					JsonObject r = handleMessage(message.getAsJsonObject());
					if (r != null) {
						responses.add(r);
					}
				}
				response = responses.isEmpty() ? null : responses;
			} else {
				response = handleMessage(request.getAsJsonObject());
			}
			respond(out, response == null ? 202 : 200, response == null ? null : response.toString());
		} catch (Exception e) {
			ClaudeChatClient.LOGGER.debug("MCP request failed", e);
		}
	}

	private static String readLine(InputStream in) throws IOException {
		ByteArrayOutputStream line = new ByteArrayOutputStream();
		int b;
		while ((b = in.read()) != -1) {
			if (b == '\n') {
				break;
			}
			if (b != '\r') {
				line.write(b);
			}
		}
		return b == -1 && line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1);
	}

	private static byte[] readBody(InputStream in, Map<String, String> headers) throws IOException {
		if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
			ByteArrayOutputStream body = new ByteArrayOutputStream();
			while (true) {
				String sizeLine = readLine(in);
				int size = Integer.parseInt(sizeLine.split(";")[0].trim(), 16);
				if (size == 0) {
					readLine(in);
					return body.toByteArray();
				}
				body.write(in.readNBytes(size));
				readLine(in);
			}
		}
		int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
		return in.readNBytes(length);
	}

	private static void respond(OutputStream out, int status, String json) throws IOException {
		byte[] body = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
		String reason = switch (status) {
			case 200 -> "OK";
			case 202 -> "Accepted";
			case 401 -> "Unauthorized";
			case 404 -> "Not Found";
			case 405 -> "Method Not Allowed";
			default -> "Error";
		};
		String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
			+ (json != null ? "Content-Type: application/json\r\n" : "")
			+ "Content-Length: " + body.length + "\r\n"
			+ "Connection: close\r\n\r\n";
		out.write(head.getBytes(StandardCharsets.ISO_8859_1));
		out.write(body);
		out.flush();
	}

	// --- JSON-RPC / MCP -------------------------------------------------------------------------------------------

	/** Returns the response, or null for notifications. */
	private JsonObject handleMessage(JsonObject message) {
		if (!message.has("id") || message.get("id").isJsonNull()) {
			return null; // notifications/initialized and friends
		}
		String method = message.has("method") ? message.get("method").getAsString() : "";
		JsonObject params = message.has("params") && message.get("params").isJsonObject() ? message.getAsJsonObject("params") : new JsonObject();
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.add("id", message.get("id"));
		switch (method) {
			case "initialize" -> {
				JsonObject result = new JsonObject();
				result.addProperty("protocolVersion", params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : FALLBACK_PROTOCOL);
				JsonObject capabilities = new JsonObject();
				capabilities.add("tools", new JsonObject());
				result.add("capabilities", capabilities);
				JsonObject info = new JsonObject();
				info.addProperty("name", "minecraft");
				info.addProperty("version", "1.0.0");
				result.add("serverInfo", info);
				result.addProperty("instructions", "Tools for the player's Minecraft game: decompile a mod's code on demand, and "
					+ "while a single-player world is open, the player's world: what's stored where, entities, the player's "
					+ "inventory, and structure locations. They compute distances and directions.");
				response.add("result", result);
			}
			case "ping" -> response.add("result", new JsonObject());
			case "tools/list" -> {
				JsonArray tools = modTools.definitions();
				if (worldTools.available()) {
					tools.addAll(worldTools.definitions());
				}
				JsonObject result = new JsonObject();
				result.add("tools", tools);
				response.add("result", result);
			}
			case "tools/call" -> response.add("result", callTool(params));
			default -> {
				JsonObject error = new JsonObject();
				error.addProperty("code", -32601);
				error.addProperty("message", "Method not found: " + method);
				response.add("error", error);
			}
		}
		return response;
	}

	private JsonObject callTool(JsonObject params) {
		String name = params.has("name") ? params.get("name").getAsString() : "";
		JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject() ? params.getAsJsonObject("arguments") : new JsonObject();
		JsonObject result = new JsonObject();
		JsonArray content = new JsonArray();
		JsonObject text = new JsonObject();
		text.addProperty("type", "text");
		try {
			JsonElement output;
			if (modTools.has(name)) {
				output = modTools.call(name, args);
			} else if (worldTools.available()) {
				output = worldTools.call(name, args);
			} else {
				throw new IllegalStateException("No single-player world is open, so the world tools aren't available.");
			}
			text.addProperty("text", output.toString());
		} catch (Exception e) {
			Throwable cause = e.getCause() != null && e instanceof java.util.concurrent.ExecutionException ? e.getCause() : e;
			ClaudeChatClient.LOGGER.warn("Tool {} failed", name, cause);
			text.addProperty("text", "Error: " + cause.getMessage());
			result.addProperty("isError", true);
		}
		content.add(text);
		result.add("content", content);
		return result;
	}
}
