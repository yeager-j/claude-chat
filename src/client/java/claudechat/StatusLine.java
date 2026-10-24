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

	/** The status while Claude is working; null when idle. */
	private Active active;

	/** One run of the status line, from {@link #start} until it's removed. */
	private static final class Active {
		final long startedAt = System.currentTimeMillis();
		Component note = Component.literal("Thinking");
		/** The line in chat; null while there's no chat to show it in. */
		GuiMessage line;
		int ticksUntilUpdate;
	}

	public boolean isActive() {
		return active != null;
	}

	public void start() {
		remove();
		active = new Active();
		show();
	}

	/** Replace the text with Claude's latest progress note. */
	public void setNote(Component progress) {
		if (active == null) {
			return;
		}

		active.note = progress;
		show();
	}

	/** Call every client tick to keep the timer moving. */
	public void tick() {
		if (active != null && --active.ticksUntilUpdate <= 0) {
			show();
		}
	}

	public void remove() {
		if (active == null) {
			return;
		}

		GuiMessage line = active.line;
		active = null;
		ChatComponentAccessor chat = chat();
		if (line != null && chat != null && chat.claudechat$allMessages().remove(line)) {
			chat.claudechat$refreshTrimmedMessages();
		}
	}

	/** Forget the line without touching chat, e.g. after leaving a world (chat is cleared anyway). */
	public void reset() {
		active = null;
	}

	private void show() {
		active.ticksUntilUpdate = TICKS_PER_UPDATE;
		Minecraft mc = Minecraft.getInstance();
		ChatComponentAccessor chat = chat();
		if (chat == null) {
			return;
		}

		long seconds = (System.currentTimeMillis() - active.startedAt) / 1000;
		MutableComponent text = Component.empty()
			.append(Component.literal("[Claude] ").withStyle(ChatFormatting.GOLD))
			.append(Component.empty().append(active.note).append("…").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC))
			.append(Component.literal(" " + seconds + "s").withStyle(ChatFormatting.DARK_GRAY));

		// A fresh addedTime keeps the line from fading out while Claude works; re-adding at the front keeps it newest.
		List<GuiMessage> messages = chat.claudechat$allMessages();
		if (active.line != null) {
			messages.remove(active.line);
		}

		active.line = new GuiMessage(mc.gui.hud.getGuiTicks(), text, null, GuiMessageSource.SYSTEM_CLIENT, GuiMessageTag.systemSinglePlayer());
		messages.addFirst(active.line);
		chat.claudechat$refreshTrimmedMessages();
	}

	private static ChatComponentAccessor chat() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? null : (ChatComponentAccessor) mc.gui.hud.getChat();
	}
}
