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

	private GuiMessage current;
	private long startedAt;
	private Component note = Component.literal("Thinking");
	private int ticksUntilUpdate;

	public boolean isActive() {
		return current != null || startedAt != 0;
	}

	public void start() {
		remove();
		startedAt = System.currentTimeMillis();
		note = Component.literal("Thinking");
		show();
	}

	/** Replace the text with Claude's latest progress note. */
	public void setNote(Component progress) {
		if (!isActive()) {
			return;
		}
		note = progress;
		show();
	}

	/** Call every client tick to keep the timer moving. */
	public void tick() {
		if (isActive() && --ticksUntilUpdate <= 0) {
			show();
		}
	}

	public void remove() {
		startedAt = 0;
		if (current == null) {
			return;
		}
		ChatComponentAccessor chat = chat();
		if (chat != null && chat.claudechat$allMessages().remove(current)) {
			chat.claudechat$refreshTrimmedMessages();
		}
		current = null;
	}

	/** Forget the line without touching chat, e.g. after leaving a world (chat is cleared anyway). */
	public void reset() {
		current = null;
		startedAt = 0;
	}

	private void show() {
		ticksUntilUpdate = TICKS_PER_UPDATE;
		Minecraft mc = Minecraft.getInstance();
		ChatComponentAccessor chat = chat();
		if (chat == null) {
			return;
		}
		long seconds = (System.currentTimeMillis() - startedAt) / 1000;
		MutableComponent text = Component.empty()
			.append(Component.literal("[Claude] ").withStyle(ChatFormatting.GOLD))
			.append(Component.empty().append(note).append("…").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC))
			.append(Component.literal(" " + seconds + "s").withStyle(ChatFormatting.DARK_GRAY));

		// A fresh addedTime keeps the line from fading out while Claude works; re-adding at the front keeps it newest.
		List<GuiMessage> messages = chat.claudechat$allMessages();
		if (current != null) {
			messages.remove(current);
		}
		current = new GuiMessage(mc.gui.hud.getGuiTicks(), text, null, GuiMessageSource.SYSTEM_CLIENT, GuiMessageTag.systemSinglePlayer());
		messages.addFirst(current);
		chat.claudechat$refreshTrimmedMessages();
	}

	private static ChatComponentAccessor chat() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? null : (ChatComponentAccessor) mc.gui.hud.getChat();
	}
}
