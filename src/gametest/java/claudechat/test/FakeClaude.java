package claudechat.test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stand-in for the claude CLI: the executable every question of the game test runs, set as {@code claudePath} by
 * {@link TestPreLaunch}. The mod starts it exactly as it starts claude: {@code claudePath} names a wrapper script
 * ({@link #writeWrapper}) that runs this class with the scenario folder followed by claude's own arguments, so the
 * mod's argument building, process start, stream parsing and killing are all real. It plays the scenario named in
 * {@code <folder>/scenario.txt}, prints stream-json as claude does, and leaves files the test reads: {@code calls.log}
 * (one line of arguments per start), {@code started.txt} (its pid and its child's), {@code mcp.json} and
 * {@code prompt.txt} (copies of the MCP config and system prompt it was given), {@code workdir.txt} (the working
 * directory it was started in). It waits for {@code <folder>/go} where
 * a scenario needs the test to act first.
 * <p>
 * Scenarios, as {@code <name> <argument>}:
 * <ul>
 * <li>{@code answer <text>}: answers at once.</li>
 * <li>{@code progress <text>}: a progress note and a tool call, then the answer once the test says go.</li>
 * <li>{@code hold <text>}: waits for go, then answers; meanwhile the test plays Claude with the question's token.</li>
 * <li>{@code error <text>}: an error result with that message.</li>
 * <li>{@code missing-session <text>}: a resumed session is gone; a fresh one answers.</li>
 * <li>{@code run_command <command>}: once the test says go, calls run_command and answers with what it said.</li>
 * <li>{@code hang}: starts a child process and never ends.</li>
 * <li>{@code late-answer <text>}: answers only once the test says go.</li>
 * <li>{@code live <path to claude>}: hands the question to the real claude, with the same arguments.</li>
 * </ul>
 * Only the JDK: it runs in a plain JVM with this class alone on the class path, so the test's side of the file
 * protocol (the static methods at the end) uses nothing else either.
 */
public final class FakeClaude {
	private static final String SLEEP = "--sleep";
	/** Only a guard against a test that died: the mod's question timeout normally ends a waiting fake first. */
	private static final long GO_TIMEOUT_MILLIS = 600_000;
	private static final String FOLDER = "claudechat-fake";

	private final Path folder;
	private final List<String> args;

	private FakeClaude(Path folder, List<String> args) {
		this.folder = folder;
		this.args = args;
	}

	public static void main(String[] argv) throws Exception {
		if (argv.length > 0 && argv[0].equals(SLEEP)) {
			Thread.sleep(Long.MAX_VALUE);
			return;
		}

		FakeClaude fake = new FakeClaude(Path.of(argv[0]), List.of(argv).subList(1, argv.length));
		fake.play();
	}

	/** The folder the fake and the test share, in the game folder. */
	public static Path folder(Path gameDir) {
		return gameDir.resolve(FOLDER);
	}

	/**
	 * Writes the script {@code claudePath} points to: a {@code .cmd} file on Windows, a shell script elsewhere. It
	 * passes claude's arguments through unchanged, which holds for the mod's arguments (no quotes, no {@code & | ^ %}).
	 */
	public static Path writeWrapper(Path folder) throws IOException {
		Files.createDirectories(folder);
		String java = ProcessHandle.current().info().command().orElse("java");
		String classPath = classPath();
		boolean windows = System.getProperty("os.name").toLowerCase().startsWith("windows");
		Path script = folder.resolve(windows ? "fake-claude.cmd" : "fake-claude.sh");
		String run = quote(java) + " -cp " + quote(classPath) + " " + FakeClaude.class.getName() + " " + quote(folder.toString());
		Files.writeString(script, windows ? "@echo off\r\n" + run + " %*\r\n" : "#!/bin/sh\nexec " + run + " \"$@\"\n");
		script.toFile().setExecutable(true);
		return script;
	}

	/** Where this class was loaded from: the game test's classes, which is all the fake needs. */
	private static String classPath() {
		try {
			return Path.of(FakeClaude.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
		} catch (Exception e) {
			throw new IllegalStateException("Can't find the game test's classes for the fake claude", e);
		}
	}

	private static String quote(String text) {
		return "\"" + text + "\"";
	}

	private void play() throws Exception {
		String question = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
		String[] scenario = Files.readString(folder.resolve("scenario.txt")).strip().split(" ", 2);
		String argument = scenario.length > 1 ? scenario[1] : "";
		append("calls.log", scenario[0] + " | " + String.join(" ", args) + " | " + question);
		// Copied now: the mod deletes both files when the question ends.
		copy(Path.of(valueAfter("--mcp-config")), "mcp.json");
		copy(Path.of(valueAfter("--append-system-prompt-file")), "prompt.txt");
		Files.writeString(folder.resolve("workdir.txt"), Path.of("").toAbsolutePath().toString());

		switch (scenario[0]) {
			case "answer" -> {
				started(Optional.empty());
				answer(argument, "fake-session-answer");
			}
			case "progress" -> {
				started(Optional.empty());
				event("{\"type\":\"assistant\",\"message\":{\"content\":[" + text("Checking the fake data")
					+ ",{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Read\",\"input\":{}}]}}");
				event("{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"ok\"}]}}");
				waitForGo();
				answer(argument, "fake-session-progress");
			}
			case "hold" -> {
				started(Optional.empty());
				waitForGo();
				answer(argument, "fake-session-hold");
			}
			case "error" -> {
				started(Optional.empty());
				event("{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"is_error\":true,\"num_turns\":1,"
					+ "\"result\":" + json(argument) + ",\"session_id\":\"fake-session-error\"}");
			}
			case "missing-session" -> {
				started(Optional.empty());
				if (args.contains("--resume")) {
					event("{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"is_error\":true,\"num_turns\":0,"
						+ "\"errors\":[\"No conversation found with session ID: " + valueAfter("--resume") + "\"]}");
				} else {
					answer(argument, "fake-session-fresh");
				}
			}
			case "run_command" -> runCommand(argument);
			case "hang" -> {
				Process child = new ProcessBuilder(ProcessHandle.current().info().command().orElse("java"), "-cp",
					System.getProperty("java.class.path"), FakeClaude.class.getName(), SLEEP).inheritIO().start();
				started(Optional.of(child.pid()));
				Thread.sleep(Long.MAX_VALUE);
			}
			case "late-answer" -> {
				started(Optional.empty());
				waitForGo();
				answer(argument, "fake-session-late");
			}
			case "live" -> System.exit(live(Path.of(argument), question));
			default -> throw new IllegalArgumentException("Unknown scenario " + scenario[0]);
		}
	}

	/**
	 * Calls run_command over MCP with the URL and token from the --mcp-config file it was given, once the test says go,
	 * and answers with what the tool returned.
	 */
	private void runCommand(String command) throws Exception {
		String config = Files.readString(Path.of(valueAfter("--mcp-config")));
		String url = match(config, "\"url\":\"([^\"]+)\"");
		String authorization = match(config, "\"Authorization\":\"([^\"]+)\"");
		started(Optional.empty());
		waitForGo();

		String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"run_command\","
			+ "\"arguments\":{\"command\":" + json(command) + "}}}";
		HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url))
			.header("Authorization", authorization)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build(), HttpResponse.BodyHandlers.ofString());
		String said = unescape(match(response.body(), "\"text\":\"((?:[^\"\\\\]|\\\\.)*)\""));
		answer("run_command said: " + said, "fake-session-command");
	}

	/**
	 * The real claude with the arguments the mod gave, its output passed straight through; returns its exit code. The
	 * mod kills it along with the fake, as one of the fake's descendants. A step may ask the real Claude after fake
	 * answers in the same conversation, so a resume of one of the fake's own sessions is dropped: the real claude has
	 * never heard of it, and rejects an id that isn't a UUID outright rather than as a missing session.
	 */
	private int live(Path claude, String question) throws Exception {
		List<String> command = new ArrayList<>(List.of(claude.toString()));
		for (int i = 0; i < args.size(); i++) {
			if (args.get(i).equals("--resume") && i + 1 < args.size() && args.get(i + 1).startsWith("fake-session-")) {
				i++;
				continue;
			}
			command.add(args.get(i));
		}
		Process real = new ProcessBuilder(command)
			.redirectOutput(ProcessBuilder.Redirect.INHERIT)
			.redirectError(ProcessBuilder.Redirect.INHERIT)
			.start();
		started(Optional.of(real.pid()));
		try (OutputStream stdin = real.getOutputStream()) {
			stdin.write(question.getBytes(StandardCharsets.UTF_8));
		}

		return real.waitFor();
	}

	private void answer(String answer, String session) {
		event("{\"type\":\"assistant\",\"message\":{\"content\":[" + text(answer) + "]}}");
		event("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"num_turns\":2,\"result\":" + json(answer)
			+ ",\"session_id\":\"" + session + "\"}");
	}

	private static void event(String json) {
		System.out.println(json);
		System.out.flush();
	}

	private static String text(String text) {
		return "{\"type\":\"text\",\"text\":" + json(text) + "}";
	}

	/** Its pid, and its child's if it has one, written whole so the test never reads half a file. */
	private void started(Optional<Long> child) throws IOException {
		write("started.txt", ProcessHandle.current().pid() + child.map(pid -> " " + pid).orElse(""));
	}

	private void waitForGo() throws InterruptedException {
		long deadline = System.currentTimeMillis() + GO_TIMEOUT_MILLIS;
		while (!Files.exists(folder.resolve("go"))) {
			if (System.currentTimeMillis() > deadline) {
				throw new IllegalStateException("The test never said go");
			}
			Thread.sleep(50);
		}
	}

	private String valueAfter(String flag) {
		int at = args.indexOf(flag);
		if (at < 0 || at + 1 >= args.size()) {
			throw new IllegalArgumentException(flag + " missing from " + args);
		}
		return args.get(at + 1);
	}

	private void write(String name, String content) throws IOException {
		Path temp = folder.resolve(name + ".tmp");
		Files.writeString(temp, content);
		Files.move(temp, folder.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/** A copy of {@code file} named {@code name}, replaced in one step like {@link #write}. */
	private void copy(Path file, String name) throws IOException {
		Path temp = folder.resolve(name + ".tmp");
		Files.copy(file, temp, StandardCopyOption.REPLACE_EXISTING);
		Files.move(temp, folder.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	private void append(String name, String line) throws IOException {
		Files.writeString(folder.resolve(name), line.replace("\r", "").replace("\n", " ") + "\n", StandardOpenOption.CREATE,
			StandardOpenOption.APPEND);
	}

	private static String match(String text, String regex) {
		Matcher matcher = Pattern.compile(regex).matcher(text);
		if (!matcher.find()) {
			throw new IllegalArgumentException("No " + regex + " in " + text);
		}
		return matcher.group(1);
	}

	/** A JSON string literal. */
	private static String json(String text) {
		StringBuilder out = new StringBuilder("\"");
		for (char c : text.toCharArray()) {
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20) {
						out.append(String.format("\\u%04x", (int) c));
					} else {
						out.append(c);
					}
				}
			}
		}
		return out.append('"').toString();
	}

	/** The text of a JSON string literal's body. */
	private static String unescape(String body) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < body.length(); i++) {
			char c = body.charAt(i);
			if (c != '\\') {
				out.append(c);
				continue;
			}

			char escaped = body.charAt(++i);
			switch (escaped) {
				case 'n' -> out.append('\n');
				case 't' -> out.append('\t');
				case 'r' -> out.append('\r');
				case 'u' -> {
					out.append((char) Integer.parseInt(body.substring(i + 1, i + 5), 16));
					i += 4;
				}
				default -> out.append(escaped);
			}
		}
		return out.toString();
	}

	// --- the test's side ----------------------------------------------------------------------------------------------

	/** For the test: the next question plays {@code scenario}; clears what the last one left. */
	public static void scenario(Path folder, String scenario) throws IOException {
		Files.deleteIfExists(folder.resolve("go"));
		Files.deleteIfExists(folder.resolve("started.txt"));
		Files.writeString(folder.resolve("scenario.txt"), scenario);
	}

	/** For the test: a waiting scenario may go on. */
	public static void go(Path folder) throws IOException {
		Files.writeString(folder.resolve("go"), "");
	}

	/** For the test: the MCP config the last question was given, as claude reads it. */
	public static String mcpConfig(Path folder) throws IOException {
		return Files.readString(folder.resolve("mcp.json"));
	}

	/** For the test: the system prompt the last question was given. */
	public static String systemPrompt(Path folder) throws IOException {
		return Files.readString(folder.resolve("prompt.txt"));
	}

	/** For the test: the working directory the last question started in. */
	public static Path workingDirectory(Path folder) throws IOException {
		return Path.of(Files.readString(folder.resolve("workdir.txt")));
	}

	/** For the test: the lines of calls.log, oldest first. */
	public static List<String> calls(Path folder) throws IOException {
		Path log = folder.resolve("calls.log");
		return Files.exists(log) ? Files.readAllLines(log) : new ArrayList<>();
	}

	/** For the test: the pids in started.txt, or empty until it exists. */
	public static List<Long> startedPids(Path folder) {
		try {
			Path file = folder.resolve("started.txt");
			return Files.exists(file)
				? Arrays.stream(Files.readString(file).strip().split(" ")).map(Long::parseLong).toList()
				: List.of();
		} catch (IOException e) {
			return List.of();
		}
	}
}
