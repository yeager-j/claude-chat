package claudechat.test;

import claudechat.ChatOutput;
import claudechat.mixin.ChatComponentAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads chat history, the authority on what the player saw: every line the mod posted, newest first, with its styles
 * and click events. Client thread only.
 */
final class ChatHistory {
	/** The last line of every answer ends with this button. */
	private static final String COPY_BUTTON = " [copy]";
	private static final String PREFIX = ChatOutput.prefix().getString();

	private ChatHistory() {
	}

	/** One styled piece of a line. */
	record Segment(String text, Style style) {
	}

	static List<GuiMessage> lines(Minecraft mc) {
		return ((ChatComponentAccessor) mc.gui.hud.getChat()).claudechat$allMessages();
	}

	static boolean has(Minecraft mc, String text) {
		return lines(mc).stream().anyMatch(line -> line.content().getString().contains(text));
	}

	/** How many lines contain {@code text}. */
	static long count(Minecraft mc, String text) {
		return lines(mc).stream().filter(line -> line.content().getString().contains(text)).count();
	}

	static List<Segment> segments(Component line) {
		List<Segment> segments = new ArrayList<>();
		line.visit((style, text) -> {
			if (!text.isEmpty()) {
				segments.add(new Segment(text, style));
			}
			return Optional.empty();
		}, Style.EMPTY);
		return segments;
	}

	/** The command behind the newest "[label]" button in chat, or null if there's none. */
	static String newestButton(Minecraft mc, String label) {
		for (GuiMessage line : lines(mc)) {
			for (Segment segment : segments(line.content())) {
				if (segment.text().equals("[" + label + "]") && segment.style().getClickEvent() instanceof ClickEvent.RunCommand(String run)) {
					return run;
				}
			}
		}

		return null;
	}

	/** The last line of an answer: it ends with the copy button. */
	static boolean endsAnswer(GuiMessage line) {
		return segments(line.content()).stream().anyMatch(segment -> segment.text().equals(COPY_BUTTON)
			&& segment.style().getClickEvent() instanceof ClickEvent.CopyToClipboard);
	}

	/** A failure the mod reports: "[Claude] " and then only red text, with no button in it. */
	static boolean isError(GuiMessage line) {
		List<Segment> segments = segments(line.content());
		if (segments.isEmpty() || !segments.getFirst().text().equals(PREFIX)) {
			return false;
		}

		List<Segment> body = segments.subList(1, segments.size());
		TextColor red = TextColor.fromLegacyFormat(ChatFormatting.RED);
		return !body.isEmpty() && body.stream().allMatch(segment -> red.equals(segment.style().getColor())
			&& segment.style().getClickEvent() == null);
	}

	/**
	 * The lines after the newest echo of {@code question} ("> question"), oldest first; empty if it isn't in chat.
	 * Questions are unique within a world, so the newest echo is the question's own.
	 */
	static List<GuiMessage> after(Minecraft mc, String question) {
		List<GuiMessage> lines = lines(mc);
		String echo = echo(question);
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).content().getString().equals(echo)) {
				return lines.subList(0, i).reversed();
			}
		}

		return List.of();
	}

	/** Whether the question's answer or failure is in chat. */
	static boolean ended(Minecraft mc, String question) {
		return after(mc, question).stream().anyMatch(line -> endsAnswer(line) || isError(line));
	}

	/** How the mod echoes a question in chat. */
	static String echo(String question) {
		return "> " + question;
	}
}
