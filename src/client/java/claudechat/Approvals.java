package claudechat;

import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One question's requests for the player's OK (Ask for approval). Two kinds of request come here: Claude Code's own
 * permission prompts (edits, shell commands), which claude -p hands to the {@code approve} MCP tool
 * (--permission-prompt-tool), and game commands that run_command won't run unasked. The asking thread blocks until the
 * player clicks [Allow] or [Deny]; no answer within {@link #TIMEOUT_SECONDS} counts as Deny.
 * <p>
 * Requests belong to their question: once it ends ({@link #close}), whatever still waits is denied, nothing new is
 * asked, and a late click only says it's too late.
 */
public final class Approvals {
	public static final long TIMEOUT_SECONDS = 120;
	public static final String TOOL = "approve";
	/** What Claude is told when the player says no, whatever it asked about. */
	public static final String DENIED = "The player denied this. Don't try to do it another way; if it matters, say what"
		+ " you wanted to do and why.";
	private static final String TOO_LATE = "Too late: that request was already answered, timed out or its question ended.";
	private static final int SHOWN_CHARS = 300;
	private static final int HOVER_CHARS = 1500;
	private static final int NOTE_CHARS = 300;

	private final Consumer<Component> status;
	private final Set<CompletableFuture<Boolean>> pending = ConcurrentHashMap.newKeySet();
	private final AtomicLong waitedMillis = new AtomicLong();
	private volatile boolean closed;

	/** @param status shows "waiting for you" and then the answer in the question's status line; called on any thread */
	public Approvals(Consumer<Component> status) {
		this.status = status;
	}

	/**
	 * Posts "[Claude] wants to {action}" with {@code detail} and Allow/Deny buttons, then waits for the player.
	 * {@code warning}, if not null, is shown in red (e.g. "This can't be undone"). An interrupt counts as Deny and
	 * stays set on the calling thread.
	 *
	 * @throws IllegalStateException on the client thread, which would have to post the request and handle the click
	 *                               while blocked waiting for it
	 */
	public boolean ask(String action, Component detail, String warning) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.isSameThread()) {
			throw new IllegalStateException("Approvals.ask can't wait for the player on the client thread");
		}
		if (mc.player == null) {
			return false;
		}

		CompletableFuture<Boolean> answer = new CompletableFuture<>();
		pending.add(answer);
		// Checked after adding: a close() from now on denies this request, and an earlier one is seen here.
		if (closed) {
			pending.remove(answer);
			return false;
		}

		mc.execute(() -> post(action, detail, warning, answer));
		boolean allowed = await(answer);
		status.accept(Component.literal(allowed ? "Allowed, continuing" : "Denied, continuing"));
		return allowed;
	}

	/** Is a request waiting for the player? The answer timeout doesn't run meanwhile. */
	public boolean isWaiting() {
		return !pending.isEmpty();
	}

	/** Total time this question spent waiting for the player, for the answer timeout. */
	public long waitedMillis() {
		return waitedMillis.get();
	}

	/** The question ended: deny everything still waiting, and anything asked from now on. Any thread. */
	public void close() {
		closed = true;
		pending.forEach(answer -> answer.complete(false));
	}

	private boolean await(CompletableFuture<Boolean> answer) {
		long start = System.currentTimeMillis();
		try {
			return answer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			ChatOutput.sendLater(ChatOutput.line(Component.literal("No answer within " + TIMEOUT_SECONDS / 60
				+ " minutes, so Claude was told no.").withStyle(ChatFormatting.GRAY)));
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		} catch (ExecutionException e) {
			// Answers are only ever completed with true or false, so this is a bug; deny.
			ClaudeChatClient.LOGGER.warn("Approval request failed", e);
			return false;
		} finally {
			// A click after a timeout or an interrupt must not read as "Allowed".
			answer.complete(false);
			// Counted before the request stops pending, so the wait never shows up as Claude's working time.
			waitedMillis.addAndGet(System.currentTimeMillis() - start);
			pending.remove(answer);
		}
	}

	/** Client thread. */
	private void post(String action, Component detail, String warning, CompletableFuture<Boolean> answer) {
		// The question may have ended while this waited for the client thread.
		if (closed || Minecraft.getInstance().player == null) {
			answer.complete(false);
			return;
		}

		ChatOutput.send(ChatOutput.line(Component.literal("wants to " + action + ":").withStyle(ChatFormatting.YELLOW)));
		ChatOutput.send(Component.empty().append("  ").append(detail));
		if (warning != null) {
			ChatOutput.send(Component.literal("  " + warning).withStyle(ChatFormatting.RED));
		}
		Component allow = ChatActions.button("Allow", ChatFormatting.GREEN, Component.literal("Let Claude " + action), () ->
			ChatOutput.info(answer.complete(true) ? "Allowed." : TOO_LATE));
		Component deny = ChatActions.button("Deny", ChatFormatting.RED, Component.literal("Claude is told no"), () ->
			ChatOutput.info(answer.complete(false) ? "Denied." : TOO_LATE));
		ChatOutput.send(Component.empty().append("  ").append(allow).append(" ").append(deny));
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.5f));
		// Chat lines fade while chat is closed; the status line doesn't.
		status.accept(Component.literal("Waiting for you: open chat to Allow or Deny"));
	}

	// --- Claude Code's permission prompts -------------------------------------------------------------------------

	private static final Param<String> TOOL_NAME = Param.requiredText("tool_name", "");
	private static final Param<JsonObject> INPUT = Param.requiredObject("input", "");
	private static final Param<String> TOOL_USE_ID = Param.text("tool_use_id", "");

	// The asked-about tool's own input: every field the request shows must be there, so it never reads "edit a file".
	private static final Param<String> COMMAND = Param.requiredText("command", "");
	private static final Param<String> DESCRIPTION = Param.text("description", "");
	private static final Param<String> FILE_PATH = Param.requiredText("file_path", "");
	private static final Param<String> OLD_STRING = Param.requiredExactText("old_string", "");
	private static final Param<String> NEW_STRING = Param.requiredExactText("new_string", "");
	private static final Param<Boolean> REPLACE_ALL = Param.flag("replace_all", "", false);
	private static final Param<String> CONTENT = Param.requiredExactText("content", "");

	/**
	 * The {@code approve} tool. Always offered, so --permission-prompt-tool finds it; --disallowedTools hides it from
	 * Claude itself. A prompt it can't read fails, which Claude Code takes as a denial.
	 */
	public static Tool tool() {
		return Tool.of(TOOL, "Internal: answers Claude Code's permission prompts by asking the player in chat. Not for direct use.",
			Tool.Requirement.NOTHING, List.of(TOOL_NAME, INPUT, TOOL_USE_ID),
			(arguments, context) -> Tool.Result.of(context.approvals().answerPrompt(context.policy(), arguments.get(TOOL_NAME),
				arguments.get(INPUT))));
	}

	/**
	 * The --permission-prompt-tool contract: {behavior: allow, updatedInput} or {behavior: deny, message}. The request
	 * shows a copy of the input, every field of it, and an Allow returns exactly that copy, so what runs is what the
	 * player saw.
	 */
	private JsonObject answerPrompt(Policy policy, String tool, JsonObject input) {
		JsonObject out = new JsonObject();
		// Only Ask for approval routes prompts here; a question in another tier has nothing to approve.
		if (policy.tier().changes != PermissionTier.CommandRule.ASK) {
			out.addProperty("behavior", "deny");
			out.addProperty("message", "The player's permissions are " + policy.tier().label + ", which never asks them.");
			return out;
		}

		JsonObject shown = input.deepCopy();
		if (askAbout(tool, shown)) {
			out.addProperty("behavior", "allow");
			out.add("updatedInput", shown);
		} else {
			out.addProperty("behavior", "deny");
			out.addProperty("message", DENIED);
		}

		return out;
	}

	private boolean askAbout(String tool, JsonObject input) {
		return switch (tool) {
			case "Bash", "PowerShell" -> {
				Arguments command = Arguments.parse(List.of(COMMAND, DESCRIPTION), input);
				yield ask("run a " + (tool.equals("Bash") ? "shell" : "PowerShell") + " command",
					withOtherFields(code(command.get(COMMAND), command.get(DESCRIPTION)), input, COMMAND, DESCRIPTION), null);
			}
			case "Edit" -> {
				Arguments edit = Arguments.parse(List.of(FILE_PATH, OLD_STRING, NEW_STRING, REPLACE_ALL), input);
				yield ask("edit " + displayPath(edit.get(FILE_PATH)),
					withOtherFields(diff(edit.get(OLD_STRING), edit.get(NEW_STRING), edit.get(REPLACE_ALL)), input,
						FILE_PATH, OLD_STRING, NEW_STRING, REPLACE_ALL), pathWarning(edit.get(FILE_PATH)));
			}
			case "Write" -> {
				Arguments write = Arguments.parse(List.of(FILE_PATH, CONTENT), input);
				String path = write.get(FILE_PATH);
				String content = write.get(CONTENT);
				String size = lines(content.lines().count()) + ", " + content.length() + " characters";
				Component detail = copyable(Component.literal(size).withStyle(ChatFormatting.GRAY), withCopyHint(content), content);
				// "write", not "create" or "overwrite": finding out would touch the path, and a network path would then
				// reach that computer, perhaps with the player's Windows sign-in, before they agreed to anything.
				yield ask("write " + displayPath(path), withOtherFields(detail, input, FILE_PATH, CONTENT), pathWarning(path));
			}
			default -> ask("use " + tool, code(input.toString(), null), null);
		};
	}

	/**
	 * {@code detail} followed by the input's fields it doesn't show (e.g. a shell command's timeout), so an Allow never
	 * covers a setting the player didn't see.
	 */
	private static Component withOtherFields(Component detail, JsonObject input, Param<?>... shown) {
		Set<String> shownNames = Stream.of(shown).map(Param::name).collect(Collectors.toSet());
		List<String> others = input.entrySet().stream()
			.filter(field -> !shownNames.contains(field.getKey()))
			.map(field -> field.getKey() + "=" + field.getValue())
			.toList();
		if (others.isEmpty()) {
			return detail;
		}

		String text = " (also " + String.join(", ", others) + ")";
		return Component.empty().append(detail)
			.append(copyable(Component.literal(clip(text, SHOWN_CHARS)).withStyle(ChatFormatting.YELLOW), withCopyHint(text), text));
	}

	/**
	 * A shell or game command in white. Chat shows its start and the hover more of it, each cut marked as such; the
	 * hover puts Claude's {@code note} (may be null) after the command, so a long note can't push the command out of
	 * view. A click copies the whole command.
	 */
	public static Component code(String text, String note) {
		String hover = withCopyHint(text);
		if (note != null && !note.isBlank()) {
			hover += "\n\nClaude's description: " + clip(note, NOTE_CHARS);
		}
		return copyable(Component.literal(clip(text, SHOWN_CHARS)).withStyle(ChatFormatting.WHITE), hover, text);
	}

	/** "-3 +4 lines", with both texts on hover. A replace-all edit says so in chat, since it can touch many places. */
	private static Component diff(String oldText, String newText, boolean everywhere) {
		MutableComponent line = Component.empty()
			.append(Component.literal("-" + oldText.lines().count() + " ").withStyle(ChatFormatting.RED))
			.append(Component.literal("+" + lines(newText.lines().count())).withStyle(ChatFormatting.GREEN));
		if (everywhere) {
			line.append(Component.literal(", everywhere it occurs").withStyle(ChatFormatting.YELLOW));
		}
		String hover = "Replace:\n" + clip(oldText, HOVER_CHARS / 2) + "\n\nWith:\n" + clip(newText, HOVER_CHARS / 2)
			+ "\n\nClick to copy the new text.";
		return copyable(line, hover, newText);
	}

	/** {@code text} with {@code hover} shown on hover, copying {@code full} when clicked. */
	private static Component copyable(MutableComponent text, String hover, String full) {
		return text.withStyle(style -> style
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover)))
			.withClickEvent(new ClickEvent.CopyToClipboard(full)));
	}

	/** As much of {@code text} as a hover holds, then where the rest is. */
	private static String withCopyHint(String text) {
		return clip(text, HOVER_CHARS) + "\n\nClick to copy " + (text.length() > HOVER_CHARS ? "all of it." : "it.");
	}

	/**
	 * What the player should know about a file path before allowing a change to it, or null. Works on the text alone:
	 * nothing here may touch the path before the player allows it.
	 */
	static String pathWarning(String path) {
		String p = path.strip();
		if (p.startsWith("\\\\") || p.startsWith("//") || p.startsWith("\\/") || p.startsWith("/\\")) {
			return "This is a network or device path, not a file on this computer.";
		}

		return null;
	}

	/** Paths inside the game folder relative to it: "config/foo.json" rather than a long absolute path. Text only. */
	private static String displayPath(String path) {
		Path gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
		Path p = Path.of(path).toAbsolutePath().normalize();
		return p.startsWith(gameDir) ? gameDir.relativize(p).toString().replace('\\', '/') : p.toString();
	}

	private static String lines(long count) {
		return count + (count == 1 ? " line" : " lines");
	}

	/** {@code text} cut at {@code max} characters, saying how much was cut so a cut never passes for the whole. */
	private static String clip(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max) + "… (" + (text.length() - max) + " more characters)";
	}
}
