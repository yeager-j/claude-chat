package claudechat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * A minimal MCP server ("Streamable HTTP" transport, JSON responses only) that gives Claude Code the mod's
 * {@link Tool}s. It runs for the whole game session and lists the tools that are available right now, checking again
 * on each call, so the world tools come and go with the single-player world and {@code look} with any world.
 * <p>
 * Listens on 127.0.0.1 on a random port. Each question gets a {@link Grant}: a random bearer token of its own, mapped
 * to the question's {@link Tool.Context} and accepted only until the question ends. Whoever holds a live token can
 * call the tools with that question's permissions; nothing checks which process sends it. The token reaches claude in
 * a file that only the player's account can read and that is deleted when the question ends, so another local
 * process can use the server only while a question runs, and only if it runs as the player or can read their files.
 * <p>
 * Plain sockets rather than the JDK's HTTP server, which some launchers' trimmed Java runtimes don't include.
 */
public class McpServer {
	/** The server's name in claude's MCP config. Claude Code names each of its tools after it: see {@link #claudeName}. */
	public static final String NAME = "minecraft";
	/** Claude Code's name for all of this server's tools at once, as {@code --allowedTools} takes it. */
	public static final String CLAUDE_PREFIX = "mcp__" + NAME;

	/** MCP versions whose Streamable HTTP transport and tool calls this server implements. */
	private static final Set<String> SUPPORTED_PROTOCOLS = Set.of("2025-03-26", "2025-06-18", "2025-11-25");
	private static final String PREFERRED_PROTOCOL = "2025-06-18";
	/** One question at a time, but claude may call several tools at once, and an approval blocks for minutes. */
	private static final int MAX_WORKERS = 16;
	private static final int READ_TIMEOUT_MILLIS = 30_000;
	private static final int MAX_LINE_BYTES = 8 * 1024;
	private static final int MAX_HEADERS = 64;
	/** Generous: an approval request for a Write carries the whole file. */
	private static final int MAX_BODY_BYTES = 32 * 1024 * 1024;
	private static final int MAX_DRAIN_BYTES = 64 * 1024;
	/** The whole request, headers and body, must arrive within this, however steadily it trickles in. */
	private static final int REQUEST_DEADLINE_MILLIS = 60_000;
	/**
	 * Messages in one JSON-RPC batch. Claude Code sends one message per request (MCP dropped batches in 2025-06-18),
	 * so this only stops one small request from asking for a great many answers at once.
	 */
	static final int MAX_BATCH = 8;
	/** All the answers to one request together. A look screenshot, the largest single answer, is far below this. */
	static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;

	/** JSON-RPC's code for a request whose params are wrong; MCP uses it for a call to a tool it doesn't have. */
	private static final int INVALID_PARAMS = -32602;

	/** By name, in the order tools/list gives them. */
	private final Map<String, Tool> tools = new LinkedHashMap<>();
	private final BooleanSupplier worldOpen;
	/** The live tokens: one per question that's running. */
	private final Set<Grant> grants = ConcurrentHashMap.newKeySet();
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

	/** Are the world tools offered right now? */
	public boolean worldOpen() {
		return worldOpen.getAsBoolean();
	}

	/** Claude Code's name for one of this server's tools, e.g. {@code mcp__minecraft__look} for {@code look}. */
	public static String claudeName(String tool) {
		return CLAUDE_PREFIX + "__" + tool;
	}

	/** Every tool the server can offer, whether or not it's available right now. */
	public Set<String> toolNames() {
		return Set.copyOf(tools.keySet());
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

	/** A new token for one question, whose tool calls get {@code context}. Close the grant when the question ends. */
	public Grant grant(Tool.Context context) {
		Grant grant = new Grant(context);
		grants.add(grant);
		return grant;
	}

	/**
	 * One question's access to the tools: a token of its own and the question's {@link Tool.Context}. Closing it
	 * revokes the token, so later requests with it are refused; closes the connections it is still serving, so a
	 * request still arriving never completes; and interrupts their calls, so slow reads (schematics, templates,
	 * searches) stop rather than finish for nobody. A request already let in checks the grant again before each
	 * message and each call, and its context says the question ended, so nothing acts for it afterwards.
	 */
	public final class Grant implements AutoCloseable {
		private final String token = newToken();
		private final Tool.Context context;
		/** The worker threads serving this grant's requests right now, and their connections. */
		private final Map<Thread, Socket> serving = new HashMap<>();
		private volatile boolean closed;

		private Grant(Tool.Context context) {
			this.context = context.whileOpen(this::isOpen);
		}

		/** False once the question has ended. */
		public boolean isOpen() {
			return !closed;
		}

		/** The --mcp-config JSON for claude: this server as {@link #NAME} with this token, or no servers if it isn't running. */
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
				servers.add(NAME, minecraft);
			}

			JsonObject config = new JsonObject();
			config.add("mcpServers", servers);
			return config.toString();
		}

		/**
		 * Registers the current thread as serving one of this grant's requests over {@code client}; false once the
		 * grant is closed.
		 */
		private synchronized boolean enter(Socket client) {
			if (closed) {
				return false;
			}

			serving.put(Thread.currentThread(), client);
			return true;
		}

		/** The request is answered. Clears an interrupt meant for it, so it can't reach the worker's next request. */
		private void exit() {
			synchronized (this) {
				serving.remove(Thread.currentThread());
			}

			Thread.interrupted();
		}

		/**
		 * Revokes the token, closes the connections still being served and interrupts their calls. Safe to call more
		 * than once.
		 */
		@Override
		public void close() {
			grants.remove(this);
			synchronized (this) {
				closed = true;
				serving.forEach((thread, client) -> {
					thread.interrupt();
					try {
						client.close();
					} catch (IOException ignored) {
					}
				});
			}
		}
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
			InputStream in = new Deadline(client, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REQUEST_DEADLINE_MILLIS));
			OutputStream out = client.getOutputStream();
			try {
				serve(client, in, out);
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
	 * The request's input, which must all arrive by {@code deadline} ({@link System#nanoTime}): each read waits at most
	 * until then, so a client can't keep a request open by sending a byte every so often.
	 */
	private static final class Deadline extends FilterInputStream {
		private final Socket client;
		private final long deadline;

		Deadline(Socket client, long deadline) throws IOException {
			super(client.getInputStream());
			this.client = client;
			this.deadline = deadline;
		}

		private void beforeRead() throws IOException {
			long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
			if (left <= 0) {
				throw new SocketTimeoutException("request not received within " + REQUEST_DEADLINE_MILLIS + " ms");
			}
			client.setSoTimeout((int) Math.min(left, READ_TIMEOUT_MILLIS));
		}

		/** The socket's input itself, without the deadline. */
		InputStream raw() {
			return in;
		}

		@Override
		public int read() throws IOException {
			beforeRead();
			return super.read();
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			beforeRead();
			return super.read(buffer, offset, length);
		}
	}

	/**
	 * Reads and drops what the client is still sending, within limits. Closing with unread input makes TCP reset the
	 * connection, which can discard the error response before the client reads it.
	 */
	private static void drain(Socket client, InputStream in) throws IOException {
		client.shutdownOutput();
		client.setSoTimeout(1000);
		InputStream raw = in instanceof Deadline deadline ? deadline.raw() : in;
		byte[] buffer = new byte[8192];
		int drained = 0;
		int read;
		while (drained < MAX_DRAIN_BYTES && (read = raw.read(buffer)) != -1) {
			drained += read;
		}
	}

	/** One HTTP exchange. The headers are read within fixed limits and the token checked before any body is read. */
	private void serve(Socket client, InputStream in, OutputStream out) throws IOException, BadRequest {
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
		Grant grant = grantFor(headers.get("authorization"));
		if (grant == null) {
			throw new BadRequest(401, "wrong, revoked or missing token");
		}
		if (method.equals("DELETE")) {
			respond(out, 200, null);
			return;
		}
		if (!method.equals("POST")) {
			// No server-to-client event stream; clients fall back to plain request/response.
			throw new BadRequest(405, "method " + method);
		}
		if (!grant.enter(client)) {
			throw new BadRequest(401, "token revoked");
		}

		try {
			answer(in, out, headers, grant);
		} finally {
			grant.exit();
		}
	}

	/**
	 * Reads a POST body of JSON-RPC messages and answers them for the question {@code grant} belongs to. The grant is
	 * checked again once the body is in and before each message: once the question ends, nothing more is done for the
	 * request and it gets no answer (its connection is closed anyway).
	 */
	private void answer(InputStream in, OutputStream out, Map<String, String> headers, Grant grant)
		throws IOException, BadRequest {
		JsonElement request;
		try {
			request = JsonParser.parseString(new String(readBody(in, headers), StandardCharsets.UTF_8));
		} catch (JsonParseException e) {
			respond(out, 400, error(JsonNull.INSTANCE, -32700, "Parse error").toString());
			return;
		}
		boolean batch = request.isJsonArray();
		List<JsonElement> messages = batch ? request.getAsJsonArray().asList() : List.of(request);
		if (batch && messages.size() > MAX_BATCH) {
			respond(out, 400, error(JsonNull.INSTANCE, -32600, "Invalid Request: a batch may hold at most " + MAX_BATCH
				+ " messages").toString());
			return;
		}

		Answers answers = new Answers();
		for (JsonElement message : messages) {
			if (!grant.isOpen() || Thread.currentThread().isInterrupted()) {
				return;
			}
			JsonObject response = handleMessage(message, grant.context);
			if (response != null) {
				answers.add(response);
			}
		}
		if (!grant.isOpen()) {
			return;
		}
		if (answers.isEmpty()) {
			respond(out, 202, null);
		} else {
			answers.send(out, batch);
		}
	}

	/**
	 * The answers to one request, each kept only as its JSON bytes, together within {@link #MAX_RESPONSE_BYTES}. An
	 * answer that doesn't fit is replaced by an error saying so.
	 */
	private static final class Answers {
		private final List<byte[]> parts = new ArrayList<>();
		/** As sent in a batch: brackets and commas included. */
		private long size = 2;

		void add(JsonObject response) {
			byte[] part = response.toString().getBytes(StandardCharsets.UTF_8);
			if (size + part.length + 1 > MAX_RESPONSE_BYTES) {
				part = error(response.get("id"), -32603, "The answer is over " + MAX_RESPONSE_BYTES / (1024 * 1024)
					+ " MiB, together with the request's other answers. Ask for less at a time.").toString()
					.getBytes(StandardCharsets.UTF_8);
			}
			parts.add(part);
			size += part.length + 1;
		}

		boolean isEmpty() {
			return parts.isEmpty();
		}

		/** One answer, or a batch's as a JSON array. */
		void send(OutputStream out, boolean batch) throws IOException {
			List<byte[]> body = new ArrayList<>();
			if (batch) {
				body.add(new byte[] {'['});
			}
			for (int i = 0; i < parts.size(); i++) {
				if (i > 0) {
					body.add(new byte[] {','});
				}
				body.add(parts.get(i));
			}
			if (batch) {
				body.add(new byte[] {']'});
			}
			respond(out, 200, true, body);
		}
	}

	/**
	 * The live grant whose token the Authorization header carries, or null. Compares in constant time, so response
	 * timing says nothing about how much of a guess was right.
	 */
	private Grant grantFor(String header) {
		byte[] given = header == null ? new byte[0] : header.getBytes(StandardCharsets.UTF_8);
		Grant match = null;
		for (Grant grant : grants) {
			byte[] expected = ("Bearer " + grant.token).getBytes(StandardCharsets.UTF_8);
			if (MessageDigest.isEqual(expected, given)) {
				match = grant;
			}
		}

		return match;
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
		respond(out, status, json != null, json == null ? List.of() : List.of(json.getBytes(StandardCharsets.UTF_8)));
	}

	/** A response whose body is {@code body}'s parts one after another, written without joining them first. */
	private static void respond(OutputStream out, int status, boolean isJson, List<byte[]> body) throws IOException {
		long length = 0;
		for (byte[] part : body) {
			length += part.length;
		}
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
			+ (isJson ? "Content-Type: application/json\r\n" : "")
			+ "Content-Length: " + length + "\r\n"
			+ "Connection: close\r\n\r\n";
		out.write(head.getBytes(StandardCharsets.ISO_8859_1));
		for (byte[] part : body) {
			out.write(part);
		}
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
	private JsonObject handleMessage(JsonElement element, Tool.Context context) {
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
				info.addProperty("name", NAME);
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
				tools.values().stream().filter(Tool::available).forEach(tool -> definitions.add(tool.definition(context)));
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

				response.add("result", callTool(tool, params.get("arguments"), context));
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
	private static JsonObject callTool(Tool tool, JsonElement arguments, Tool.Context context) {
		JsonObject result = new JsonObject();
		try {
			if (arguments != null && !arguments.isJsonNull() && !arguments.isJsonObject()) {
				throw new IllegalArgumentException("arguments must be an object, not " + arguments + ".");
			}

			JsonObject given = arguments != null && arguments.isJsonObject() ? arguments.getAsJsonObject() : new JsonObject();
			result.add("content", tool.call(given, context).content());
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
