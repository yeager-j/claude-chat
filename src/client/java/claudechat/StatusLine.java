package claudechat;

import claudechat.mixin.ChatComponentAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/**
 * One chat line that shows Claude is working: "[Claude] Checking the recipe data… 12s". It updates in place every
 * second and with each progress note, always stays the newest line, and disappears when the answer arrives.
 * Client thread only.
 */
public final class StatusLine {
	private static final int TICKS_PER_UPDATE = 20;

	/** The line while Claude is working; null when idle. */
	private Handle active;

	/**
	 * One question's line, from {@link #start} until it's removed. Once another question's line replaces it, or the
	 * status line is reset, the handle does nothing, so a question that was cancelled can't touch the next one's line.
	 */
	public final class Handle {
		private final long startedAt = System.currentTimeMillis();
		private Component note = Component.literal("Thinking");
		/** The line in chat; null while there's no chat to show it in. */
		private GuiMessage line;
		private int ticksUntilUpdate;

		/** Replace the text with Claude's latest progress note. */
		public void setNote(Component progress) {
			if (this != active) {
				return;
			}

			note = progress;
			show();
		}

		/** Takes the line out of chat; the answer replaces it. */
		public void remove() {
			if (this != active) {
				return;
			}

			active = null;
			ChatComponentAccessor chat = chat();
			if (line != null && chat != null && chat.claudechat$allMessages().remove(line)) {
				chat.claudechat$refreshTrimmedMessages();
			}
		}

		private void show() {
			ticksUntilUpdate = TICKS_PER_UPDATE;
			ChatComponentAccessor chat = chat();
			if (chat == null) {
				return;
			}

			long seconds = (System.currentTimeMillis() - startedAt) / 1000;
			MutableComponent text = ChatOutput.line(Component.empty()
				.append(Component.empty().append(note).append("…").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC))
				.append(Component.literal(" " + seconds + "s").withStyle(ChatFormatting.DARK_GRAY)));

			// A fresh addedTime keeps the line from fading out while Claude works; re-adding at the front keeps it newest.
			List<GuiMessage> messages = chat.claudechat$allMessages();
			if (line != null) {
				messages.remove(line);
			}

			line = new GuiMessage(Minecraft.getInstance().gui.hud.getGuiTicks(), text, null, GuiMessageSource.SYSTEM_CLIENT,
				GuiMessageTag.systemSinglePlayer());
			messages.addFirst(line);
			chat.claudechat$refreshTrimmedMessages();
		}
	}

	/** Shows a new line for a question, replacing any earlier one. */
	public Handle start() {
		stop();
		active = new Handle();
		active.show();
		return active;
	}

	/** Call every client tick to keep the timer moving. */
	public void tick() {
		if (active != null && --active.ticksUntilUpdate <= 0) {
			active.show();
		}
	}

	/** Takes the current line, if any, out of chat. */
	public void stop() {
		if (active != null) {
			active.remove();
		}
	}

	/** Forget the line without touching chat, e.g. after leaving a world (chat is cleared anyway). */
	public void reset() {
		active = null;
	}

	private static ChatComponentAccessor chat() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? null : (ChatComponentAccessor) mc.gui.hud.getChat();
	}
}
