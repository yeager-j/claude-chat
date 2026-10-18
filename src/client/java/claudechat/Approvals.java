package claudechat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Asks the player in chat before Claude changes anything (Ask for approval). Two kinds of request come here: Claude
 * Code's own permission prompts (edits, shell commands), which claude -p hands to the {@code approve} MCP tool
 * (--permission-prompt-tool), and game commands that run_command won't run unasked. The asking thread blocks until
 * the player clicks [Allow] or [Deny]; no answer within {@link #TIMEOUT_SECONDS} counts as Deny.
 */
public final class Approvals {
	public static final long TIMEOUT_SECONDS = 120;
	public static final String TOOL = "approve";
	private static final int SHOWN_CHARS = 300;
	private static final int HOVER_CHARS = 1500;

	private static final Set<CompletableFuture<Boolean>> pending = ConcurrentHashMap.newKeySet();
	private static final AtomicLong waitedMillis = new AtomicLong();
	/** Shows "waiting for you" and then the answer in the status line; set by ClaudeChatClient. */
	public static volatile Consumer<Component> onStatus = note -> { };
	/** For the client game test: the latest request's buttons, as the player would click them. */
	public static volatile Component lastAllow, lastDeny;

	private Approvals() {
	}

	/**
	 * Posts "[Claude] wants to {action}" with {@code detail} and Allow/Deny buttons, then waits for the player. Not on
	 * the client thread. {@code warning}, if not null, is shown in red (e.g. "This can't be undone").
	 */
	public static boolean ask(String action, Component detail, String warning) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return false;
		}
		CompletableFuture<Boolean> answer = new CompletableFuture<>();
		pending.add(answer);
		long start = System.currentTimeMillis();
		mc.execute(() -> post(action, detail, warning, answer));
		boolean allowed = false;
		try {
			allowed = answer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			mc.execute(() -> info("No answer within " + TIMEOUT_SECONDS / 60 + " minutes, so Claude was told no."));
		} catch (Exception ignored) {
		} finally {
			pending.remove(answer);
			waitedMillis.addAndGet(System.currentTimeMillis() - start);
		}
		onStatus.accept(Component.literal(allowed ? "Allowed, continuing" : "Denied, continuing"));
		return allowed;
	}

	/** Is a request waiting for the player? The answer timeout doesn't run meanwhile. */
	public static boolean isWaiting() {
		return !pending.isEmpty();
	}

	/** Total time spent waiting for the player, for the answer timeout. */
	public static long waitedMillis() {
		return waitedMillis.get();
	}

	/** Deny everything still waiting: the question ended or the player left the world. */
	public static void cancelAll() {
		pending.forEach(answer -> answer.complete(false));
	}

	private static void post(String action, Component detail, String warning, CompletableFuture<Boolean> answer) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			answer.complete(false);
			return;
		}
		send(Component.empty().append(prefix()).append(Component.literal("wants to " + action + ":").withStyle(ChatFormatting.YELLOW)));
		send(Component.empty().append("  ").append(detail));
		if (warning != null) {
			send(Component.literal("  " + warning).withStyle(ChatFormatting.RED));
		}
		Component allow = ChatActions.button("Allow", ChatFormatting.GREEN, Component.literal("Let Claude " + action), () -> {
			if (!answer.complete(true)) {
				info("Too late: that request was already answered or timed out.");
			}
		});
		Component deny = ChatActions.button("Deny", ChatFormatting.RED, Component.literal("Claude is told no"), () -> {
			if (!answer.complete(false)) {
				info("Too late: that request was already answered or timed out.");
			}
		});
		lastAllow = allow;
		lastDeny = deny;
		send(Component.empty().append("  ").append(allow).append(" ").append(deny));
		mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.5f));
		// Chat lines fade while chat is closed; the status line doesn't.
		onStatus.accept(Component.literal("Waiting for you: open chat to Allow or Deny"));
	}

	// --- Claude Code's permission prompts -------------------------------------------------------------------------

	public static JsonObject definition() {
		return WorldTools.tool(TOOL,
			"Internal: answers Claude Code's permission prompts by asking the player in chat. Not for direct use.",
			"""
			{"type": "object", "properties": {
			  "tool_name": {"type": "string"},
			  "input": {"type": "object"},
			  "tool_use_id": {"type": "string"}
			}, "required": ["tool_name", "input"]}
			""");
	}

	/** The --permission-prompt-tool contract: {behavior: allow, updatedInput} or {behavior: deny, message}. */
	public static JsonElement handlePrompt(JsonObject args) {
		String tool = args.has("tool_name") ? args.get("tool_name").getAsString() : "?";
		JsonObject input = args.has("input") && args.get("input").isJsonObject() ? args.getAsJsonObject("input") : new JsonObject();
		boolean allowed = switch (tool) {
			case "Bash", "PowerShell" -> ask("run a " + (tool.equals("Bash") ? "shell" : "PowerShell") + " command",
				code(string(input, "command"), string(input, "description")), null);
			case "Edit" -> ask("edit " + displayPath(string(input, "file_path")),
				diff(string(input, "old_string"), string(input, "new_string")), null);
			case "Write" -> {
				String path = string(input, "file_path");
				String content = string(input, "content");
				boolean exists = !path.isEmpty() && Files.exists(Path.of(path));
				yield ask((exists ? "overwrite " : "create ") + displayPath(path),
					hoverable(Component.literal(content.lines().count() + " lines").withStyle(ChatFormatting.GRAY), content), null);
			}
			default -> ask("use " + tool, code(input.toString(), null), null);
		};
		JsonObject out = new JsonObject();
		if (allowed) {
			out.addProperty("behavior", "allow");
			out.add("updatedInput", input);
		} else {
			out.addProperty("behavior", "deny");
			out.addProperty("message", "The player denied this. Don't try to do it another way; if it matters, say what you"
				+ " wanted to do and why.");
		}
		return out;
	}

	/** Shell commands and game commands: white, cut short in chat, in full on hover. */
	public static Component code(String text, String note) {
		String hover = note == null || note.isBlank() ? text : note + "\n\n" + text;
		return hoverable(Component.literal(shorten(text, SHOWN_CHARS)).withStyle(ChatFormatting.WHITE), hover);
	}

	private static Component diff(String oldText, String newText) {
		MutableComponent line = Component.empty()
			.append(Component.literal("-" + oldText.lines().count() + " ").withStyle(ChatFormatting.RED))
			.append(Component.literal("+" + newText.lines().count() + " lines").withStyle(ChatFormatting.GREEN));
		return hoverable(line, "Replace:\n" + shorten(oldText, HOVER_CHARS / 2) + "\n\nWith:\n" + shorten(newText, HOVER_CHARS / 2));
	}

	private static Component hoverable(MutableComponent text, String hover) {
		return text.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal(shorten(hover, HOVER_CHARS)))));
	}

	/** Paths inside the game folder relative to it: "config/foo.json" rather than a long absolute path. */
	private static String displayPath(String path) {
		if (path.isEmpty()) {
			return "a file";
		}
		Path gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
		Path p = Path.of(path).toAbsolutePath().normalize();
		return p.startsWith(gameDir) ? gameDir.relativize(p).toString().replace('\\', '/') : p.toString();
	}

	private static String shorten(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max) + "…";
	}

	private static String string(JsonObject obj, String key) {
		return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsString() : "";
	}

	private static void info(String message) {
		send(Component.empty().append(prefix()).append(Component.literal(message).withStyle(ChatFormatting.GRAY)));
	}

	private static Component prefix() {
		return Component.literal("[Claude] ").withStyle(ChatFormatting.GOLD);
	}

	private static void send(Component component) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(component);
		}
	}
}
