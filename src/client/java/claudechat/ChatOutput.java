package claudechat;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** The mod's own chat lines: the gold "[Claude] " prefix, and sending only while there's a player to show them to. */
public final class ChatOutput {
	private ChatOutput() {
	}

	public static MutableComponent prefix() {
		return Component.literal("[Claude] ").withStyle(ChatFormatting.GOLD);
	}

	/**
	 * {@code body} after the prefix. Siblings of an empty component, so {@code body} doesn't inherit the prefix's
	 * gold.
	 */
	public static MutableComponent line(Component body) {
		return Component.empty().append(prefix()).append(body);
	}

	/** Client thread. Shows {@code component} in chat; dropped when no world is open. */
	public static void send(Component component) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(component);
		}
	}

	/** Client thread. A gray "[Claude] message" line. */
	public static void info(String message) {
		send(line(Component.literal(message).withStyle(ChatFormatting.GRAY)));
	}

	/** Any thread: {@link #send} on the client thread. */
	public static void sendLater(Component component) {
		Minecraft.getInstance().execute(() -> send(component));
	}
}
