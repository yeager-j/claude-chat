package claudechat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chat buttons that run code in the mod: each click runs {@code /claude action <id>} with a random id that only this
 * class knows. A fixed command would let any chat message (from a server, or quoted by Claude) carry a working
 * "Allow" or "Turn on Full Access" button; an id that was never shown anywhere else can't be forged.
 */
public final class ChatActions {
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Map<String, Runnable> actions = new ConcurrentHashMap<>();

	private ChatActions() {
	}

	/** A "[label]" button that runs {@code action} on the client thread when clicked, once. */
	public static Component button(String label, ChatFormatting color, Component hover, Runnable action) {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		String id = HexFormat.of().formatHex(bytes);
		actions.put(id, action);
		return Component.literal("[" + label + "]").withStyle(style -> style
			.withColor(color)
			.withClickEvent(new ClickEvent.RunCommand("/claude action " + id))
			.withHoverEvent(new HoverEvent.ShowText(hover)));
	}

	/** Runs and forgets the action. False if the id is unknown: already used, expired, or never issued. */
	public static boolean run(String id) {
		Runnable action = actions.remove(id);
		if (action == null) {
			return false;
		}
		action.run();
		return true;
	}

	/** On disconnect: old buttons stay in chat history but stop working. */
	public static void clear() {
		actions.clear();
	}

	/** For the client game test: the id behind a button, as a player's click would send it. */
	public static String idOf(Component button) {
		if (button.getStyle().getClickEvent() instanceof ClickEvent.RunCommand(String command)) {
			return command.substring(command.lastIndexOf(' ') + 1);
		}
		throw new IllegalArgumentException("Not a chat action button");
	}
}
