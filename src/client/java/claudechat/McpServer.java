package claudechat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * A minimal MCP server ("Streamable HTTP" transport, JSON responses only) that gives Claude Code the mod's
 * {@link Tool}s for the whole game session. It lists the tools that are available right now and checks again on each
 * call, so the world tools come and go with the single-player world and {@code look} with any world. Each
 * question starts a new claude process, which lists the tools afresh. Listens on 127.0.0.1 on a random port and
 * requires a random bearer token. The token reaches claude in config/claudechat/mcp.json, which exists only while a
 * question runs (and is owner-only where the file system supports that), so another local process can use the server
 * only if it can read the player's files during a question. Plain sockets rather than the JDK's HTTP server, which some
 * launchers' trimmed Java runtimes don't include.
 */
public class McpServer {
	/** MCP versions whose Streamable HTTP transport and tool calls this server implements. */
	private static final Set<String> SUPPORTED_PROTOCOLS = Set.of("2025-03-26", "2025-06-18", "2025-11-25");
	private static final String PREFERRED_PROTOCOL = "2025-06-18";
	/** One claude process at a time, but it may call several tools at once, and an approval blocks for minutes. */
	private static final int MAX_WORKERS = 16;
	private static final int READ_TIMEOUT_MILLIS = 30_000;
	private static final int MAX_LINE_BYTES = 8 * 1024;
	private static final int MAX_HEADERS = 64;
	/** Generous: an approval request for a Write carries the whole file. */
	private static final int MAX_BODY_BYTES = 32 * 1024 * 1024;
	private static final int MAX_DRAIN_BYTES = 64 * 1024;

	/** JSON-RPC's code for a request whose params are wrong; MCP uses it for a call to a tool it doesn't have. */
	private static final int INVALID_PARAMS = -32602;

	/** By name, in the order tools/list gives them. */
	private final Map<String, Tool> tools = new LinkedHashMap<>();
	private final BooleanSupplier worldOpen;
	private final String token = newToken();
	private volatile ServerSocket socket;
	private ExecutorService workers;

	/**
	 * @param tools     every tool the server can offer, each with a unique name
	 * @param worldOpen whether the single-player world tools are offered right now, for the system prompt
	 */
	public McpServer(List<Tool> tools, BooleanSupplier worldOpen) {
		for (Tool tool : tools) {
			if (this.tools.putIfAbsent(tool.name(), tool) != null) {
				throw new IllegalArgumentException("Two tools are named " + tool.name());
			}
		}

		this.worldOpen = worldOpen;
	}

	/** The mod's tools: decompiling mods, approvals, {@code look}, the world tools and {@code run_command}. */
	public McpServer(ModTools modTools, WorldTools worldTools, GameCommands gameCommands, ViewSnapshot view) {
		this(Stream.of(modTools.tools(), List.of(Approvals.tool(), view.tool()), worldTools.tools(), List.of(gameCommands.tool()))
			.flatMap(List::stream).toList(), worldTools::available);
	}

	/** Are the world tools offered right now? */
	public boolean worldOpen() {
		return worldOpen.getAsBoolean();
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
		// No queue: a request beyond MAX_WORKERS is refused at once rather than waiting behind blocked tool calls.
		workers = new ThreadPoolExecutor(0, MAX_WORKERS, 60, TimeUnit.SECONDS, new SynchronousQueue<>(),
			runnable -> daemon(runnable, "Claude Chat MCP"));
		ServerSocket listening = socket;
		ExecutorService pool = workers;
		daemon(() -> accept(listening, pool), "Claude Chat MCP accept").start();
		ClaudeChatClient.LOGGER.info("Tools for Claude served on 127.0.0.1:{}", socket.getLocalPort());
	}

	private void accept(ServerSocket listening, ExecutorService pool) {
		while (!listening.isClosed()) {
			try {
				Socket client = listening.accept();
				try {
					pool.execute(() -> handle(client));
				} catch (RejectedExecutionException e) {
					refuse(client);
				}
			} catch (IOException e) {
				if (!listening.isClosed()) {
					ClaudeChatClient.LOGGER.debug("MCP accept failed", e);
				}
			}
		}
	}

	private static void refuse(Socket client) {
		try (client) {
			respond(client.getOutputStream(), 503, null);
		} catch (IOException ignored) {
		}
	}

	private static Thread daemon(Runnable runnable, String name) {
		Thread thread = new Thread(runnable, name);
		thread.setDaemon(true);
		return thread;
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

	/** A request that is refused before its body is read; answered with {@code status} and no body. */
	private static final class BadRequest extends Exception {
		final int status;

		BadRequest(int status, String message) {
			super(message);
			this.status = status;
		}
	}

	private void handle(Socket client) {
		try (client) {
			// Reads only: a slow tool call doesn't read the socket, so this bounds idle clients, not tool time.
			client.setSoTimeout(READ_TIMEOUT_MILLIS);
			InputStream in = client.getInputStream();
			OutputStream out = client.getOutputStream();
			try {
				serve(in, out);
			} catch (BadRequest e) {
				ClaudeChatClient.LOGGER.debug("MCP request refused: {}", e.getMessage());
				respond(out, e.status, null);
				drain(client, in);
			}
		} catch (Exception e) {
			ClaudeChatClient.LOGGER.debug("MCP request failed", e);
		}
	}

	/**
	 * Reads and drops what the client is still sending, within limits. Closing with unread input makes TCP reset the
	 * connection, which can discard the error response before the client reads it.
	 */
	private static void drain(Socket client, InputStream in) throws IOException {
		client.shutdownOutput();
		client.setSoTimeout(1000);
		byte[] buffer = new byte[8192];
		int drained = 0;
		int read;
		while (drained < MAX_DRAIN_BYTES && (read = in.read(buffer)) != -1) {
			drained += read;
		}
	}

	/** One HTTP exchange. The headers are read within fixed limits and the token checked before any body is read. */
	private void serve(InputStream in, OutputStream out) throws IOException, BadRequest {
		String requestLine = readLine(in);
		if (requestLine == null || requestLine.isEmpty()) {
			return;
		}
		String[] parts = requestLine.split(" ");
		String method = parts[0];
		String path = parts.length > 1 ? parts[1] : "/";
		Map<String, String> headers = readHeaders(in);

		if (!path.startsWith("/mcp")) {
			throw new BadRequest(404, "path " + path);
		}
		if (!authorized(headers.get("authorization"))) {
			throw new BadRequest(401, "wrong or missing token");
		}
		if (method.equals("DELETE")) {
			respond(out, 200, null);
			return;
		}
		if (!method.equals("POST")) {
			// No server-to-client event stream; clients fall back to plain request/response.
			throw new BadRequest(405, "method " + method);
		}

		JsonElement request;
		try {
			request = JsonParser.parseString(new String(readBody(in, headers), StandardCharsets.UTF_8));
		} catch (JsonParseException e) {
			respond(out, 400, error(JsonNull.INSTANCE, -32700, "Parse error").toString());
			return;
		}
		JsonElement response;
		if (request.isJsonArray()) {
			JsonArray responses = new JsonArray();
			for (JsonElement message : request.getAsJsonArray()) {
				JsonObject r = handleMessage(message);
				if (r != null) {
					responses.add(r);
				}
			}
			response = responses.isEmpty() ? null : responses;
		} else {
			response = handleMessage(request);
		}
		respond(out, response == null ? 202 : 200, response == null ? null : response.toString());
	}

	/** Compares in constant time, so response timing says nothing about how much of a guess was right. */
	private boolean authorized(String header) {
		byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
		byte[] given = header == null ? new byte[0] : header.getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(expected, given);
	}

	private static Map<String, String> readHeaders(InputStream in) throws IOException, BadRequest {
		Map<String, String> headers = new HashMap<>();
		String line;
		while ((line = readLine(in)) != null && !line.isEmpty()) {
			if (headers.size() >= MAX_HEADERS) {
				throw new BadRequest(431, "more than " + MAX_HEADERS + " headers");
			}
			int colon = line.indexOf(':');
			if (colon > 0) {
				headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
			}
		}
		return headers;
	}

	/** A CRLF- or LF-terminated line of at most {@link #MAX_LINE_BYTES}; null at the end of the stream. */
	private static String readLine(InputStream in) throws IOException, BadRequest {
		ByteArrayOutputStream line = new ByteArrayOutputStream();
		int b;
		while ((b = in.read()) != -1) {
			if (b == '\n') {
				break;
			}
			if (b != '\r') {
				line.write(b);
			}
			if (line.size() > MAX_LINE_BYTES) {
				throw new BadRequest(431, "line longer than " + MAX_LINE_BYTES + " bytes");
			}
		}
		return b == -1 && line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1);
	}

	private static byte[] readBody(InputStream in, Map<String, String> headers) throws IOException, BadRequest {
		if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
			return readChunked(in);
		}
		long length = number(headers.getOrDefault("content-length", "0"), 10);
		if (length > MAX_BODY_BYTES) {
			throw new BadRequest(413, "body of " + length + " bytes");
		}
		return in.readNBytes((int) length);
	}

	private static byte[] readChunked(InputStream in) throws IOException, BadRequest {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		while (true) {
			String sizeLine = readLine(in);
			if (sizeLine == null) {
				throw new BadRequest(400, "chunked body ended early");
			}
			long size = number(sizeLine.split(";")[0].trim(), 16);
			if (body.size() + size > MAX_BODY_BYTES) {
				throw new BadRequest(413, "chunked body over " + MAX_BODY_BYTES + " bytes");
			}
			if (size == 0) {
				readLine(in);
				return body.toByteArray();
			}
			body.write(in.readNBytes((int) size));
			readLine(in);
		}
	}

	/** A non-negative size from a header or chunk line. */
	private static long number(String text, int radix) throws BadRequest {
		try {
			long value = Long.parseLong(text, radix);
			if (value < 0) {
				throw new BadRequest(400, "negative size " + text);
			}
			return value;
		} catch (NumberFormatException e) {
			throw new BadRequest(400, "bad size " + text);
		}
	}

	private static void respond(OutputStream out, int status, String json) throws IOException {
		byte[] body = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
		String reason = switch (status) {
			case 200 -> "OK";
			case 202 -> "Accepted";
			case 400 -> "Bad Request";
			case 401 -> "Unauthorized";
			case 404 -> "Not Found";
			case 405 -> "Method Not Allowed";
			case 413 -> "Content Too Large";
			case 431 -> "Request Header Fields Too Large";
			case 503 -> "Service Unavailable";
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

	/** A JSON-RPC error response. */
	private static JsonObject error(JsonElement id, int code, String message) {
		JsonObject error = new JsonObject();
		error.addProperty("code", code);
		error.addProperty("message", message);
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.add("id", id);
		response.add("error", error);
		return response;
	}

	/** The client's protocol version if this server speaks it, otherwise the one it prefers (MCP version negotiation). */
	private static String protocolVersion(JsonObject params) {
		JsonElement requested = params.get("protocolVersion");
		boolean supported = requested != null && requested.isJsonPrimitive()
			&& SUPPORTED_PROTOCOLS.contains(requested.getAsString());
		return supported ? requested.getAsString() : PREFERRED_PROTOCOL;
	}

	/** Returns the response, or null for notifications. */
	private JsonObject handleMessage(JsonElement element) {
		if (!element.isJsonObject()) {
			return error(JsonNull.INSTANCE, -32600, "Invalid Request");
		}
		JsonObject message = element.getAsJsonObject();
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
				result.addProperty("protocolVersion", protocolVersion(params));
				JsonObject capabilities = new JsonObject();
				capabilities.add("tools", new JsonObject());
				result.add("capabilities", capabilities);
				JsonObject info = new JsonObject();
				info.addProperty("name", "minecraft");
				info.addProperty("version", "1.0.0");
				result.add("serverInfo", info);
				result.addProperty("instructions", "Tools for the player's Minecraft game: decompile a mod's code on demand, and "
					+ "while a single-player world is open, the player's world: what's stored where, entities, the player's "
					+ "inventory, and structure locations. They compute distances and directions. In any world, look describes what "
					+ "the player sees right now.");
				response.add("result", result);
			}
			case "ping" -> response.add("result", new JsonObject());
			case "tools/list" -> {
				JsonArray definitions = new JsonArray();
				tools.values().stream().filter(Tool::available).forEach(tool -> definitions.add(tool.definition()));
				JsonObject result = new JsonObject();
				result.add("tools", definitions);
				response.add("result", result);
			}
			case "tools/call" -> {
				JsonElement name = params.get("name");
				Tool tool = name != null && name.isJsonPrimitive() ? tools.get(name.getAsString()) : null;
				if (tool == null) {
					return error(message.get("id"), INVALID_PARAMS, "Unknown tool: " + name);
				}

				response.add("result", callTool(tool, params.get("arguments")));
			}
			default -> {
				return error(message.get("id"), -32601, "Method not found: " + method);
			}
		}
		return response;
	}

	/**
	 * The MCP result of one call. A failure, bad arguments included, is a result with {@code isError} rather than a
	 * JSON-RPC error, so Claude reads what went wrong and can try again.
	 */
	private static JsonObject callTool(Tool tool, JsonElement arguments) {
		JsonObject result = new JsonObject();
		try {
			if (arguments != null && !arguments.isJsonNull() && !arguments.isJsonObject()) {
				throw new IllegalArgumentException("arguments must be an object, not " + arguments + ".");
			}

			JsonObject given = arguments != null && arguments.isJsonObject() ? arguments.getAsJsonObject() : new JsonObject();
			result.add("content", tool.call(given, new Tool.Context()).content());
		} catch (Exception e) {
			Throwable cause = rootCause(e);
			ClaudeChatClient.LOGGER.warn("Tool {} failed", tool.name(), cause);
			JsonArray content = new JsonArray();
			content.add(Tool.Result.text("Error: " + errorText(cause)));
			result.add("content", content);
			result.addProperty("isError", true);
		}

		return result;
	}

	/** The exception a wrapper (a failed future, a reflective call) stands for. */
	private static Throwable rootCause(Throwable e) {
		Throwable cause = e;
		while ((cause instanceof ExecutionException || cause instanceof CompletionException || cause instanceof InvocationTargetException)
			&& cause.getCause() != null) {
			cause = cause.getCause();
		}

		return cause;
	}

	/** What Claude is told went wrong: the message, or for an exception without one, what kind of failure it was. */
	static String errorText(Throwable error) {
		if (error.getMessage() != null && !error.getMessage().isBlank()) {
			return error.getMessage();
		}

		return switch (error) {
			case TimeoutException _ -> "The game didn't answer in time.";
			case InterruptedException _, CancellationException _ -> "The call was cancelled.";
			default -> "The tool failed (" + error.getClass().getSimpleName() + ", no details). The game log has more.";
		};
	}
}
