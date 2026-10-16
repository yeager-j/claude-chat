package claudechat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns Claude's reply into chat components. Supports a Markdown subset plus three Minecraft tokens:
 * [[item_id]] (icon + name + tooltip), [[recipe:id]] (icon grid, on its own line) and {{key.inventory}} (the player's key).
 */
public final class ChatFormatter {
	private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*)$");
	private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
	private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d+)[.)]\\s+(.*)$");
	private static final Pattern RECIPE_LINE = Pattern.compile("^\\s*\\[\\[recipe:([^\\]]+)\\]\\]\\s*$");
	private static final Pattern TABLE_DIVIDER = Pattern.compile("^\\s*\\|?\\s*:?-{2,}.*$");

	// Order matters: earlier alternatives win when two start at the same place.
	private static final Pattern INLINE = Pattern.compile(String.join("|",
		"\\[\\[recipe:(?<recipe>[^\\]]+)\\]\\]",
		"\\[\\[(?<item>[^\\]]+)\\]\\]",
		"\\{\\{(?<key>[\\w.]+)\\}\\}",
		"`(?<code>[^`]+)`",
		"\\*\\*(?<bold>.+?)\\*\\*",
		"~~(?<strike>.+?)~~",
		"\\[(?<linktext>[^\\]]+)\\]\\((?<url>https?://[^)\\s]+)\\)",
		"(?<![\\w*])\\*(?![\\s*])(?<italic>.+?)(?<![\\s*])\\*(?![\\w*])",
		"(?<![\\w_])_(?![\\s_])(?<italic2>.+?)(?<![\\s_])_(?![\\w_])"));

	private final RecipeRenderer recipes;

	public ChatFormatter(RecipeRenderer recipes) {
		this.recipes = recipes;
	}

	public static boolean startsWithRecipe(String text) {
		return text.strip().lines().findFirst().map(line -> RECIPE_LINE.matcher(line).matches()).orElse(false);
	}

	/** A progress note ("Checking the recipe data") as one dim italic line. Only the first line is kept. */
	public MutableComponent formatProgress(String text) {
		String line = text.replace("\r", "").replace("§", "").strip().lines()
			.filter(l -> !l.isBlank()).findFirst().orElse("").strip();
		line = line.replaceAll("^#+\\s*", "").replaceAll("[:.…]+$", "");
		if (line.length() > 80) {
			line = line.substring(0, 77).strip() + "...";
		}
		return styled(inline(line), s -> s.withItalic(true).withColor(ChatFormatting.GRAY));
	}

	public List<Component> format(String text) {
		List<Component> lines = new ArrayList<>();
		for (String raw : text.replace("\r", "").replace("§", "").split("\n")) {
			String line = raw.stripTrailing();
			if (line.isBlank() || line.strip().startsWith("```") || TABLE_DIVIDER.matcher(line).matches() && line.contains("-")) {
				continue;
			}

			Matcher m;
			if ((m = RECIPE_LINE.matcher(line)).matches()) {
				lines.addAll(recipes.render(m.group(1)));
			} else if ((m = HEADING.matcher(line)).matches()) {
				lines.add(styled(inline(m.group(1)), s -> s.withBold(true).withColor(ChatFormatting.GOLD)));
			} else if ((m = BULLET.matcher(line)).matches()) {
				lines.add(Component.empty()
					.append(Component.literal(indent(m.group(1)) + "• ").withStyle(ChatFormatting.GRAY))
					.append(inline(m.group(2))));
			} else if ((m = NUMBERED.matcher(line)).matches()) {
				lines.add(Component.empty()
					.append(Component.literal(indent(m.group(1)) + m.group(2) + ". ").withStyle(ChatFormatting.GRAY))
					.append(inline(m.group(3))));
			} else if (line.strip().startsWith("|")) {
				// Chat uses a proportional font, so tables can't line up; show each row as "a · b · c".
				String row = line.strip().replaceAll("^\\||\\|$", "");
				lines.add(inline(String.join(" · ", row.split("\\s*\\|\\s*")).strip()));
			} else {
				lines.add(inline(line));
			}
		}
		if (lines.isEmpty()) {
			lines.add(Component.literal("(no answer)").withStyle(ChatFormatting.GRAY));
		}
		return lines;
	}

	/** Inline Markdown and tokens. Bold/italic/strike content is parsed recursively, so they can contain icons. */
	private MutableComponent inline(String text) {
		// No color here: children override their parent's style, so headings and links set the color around this.
		MutableComponent out = Component.empty();
		Matcher m = INLINE.matcher(text);
		int last = 0;
		while (m.find()) {
			if (m.start() > last) {
				out.append(text.substring(last, m.start()));
			}
			out.append(token(m));
			last = m.end();
		}
		if (last < text.length()) {
			out.append(text.substring(last));
		}
		return out;
	}

	private MutableComponent token(Matcher m) {
		if (m.group("recipe") != null) {
			// A recipe in the middle of a sentence: show what it makes instead of a grid.
			return item(m.group("recipe"));
		}
		if (m.group("item") != null) {
			return item(m.group("item"));
		}
		if (m.group("key") != null) {
			String key = m.group("key").startsWith("key.") ? m.group("key") : "key." + m.group("key");
			return Component.literal("[").append(Component.keybind(key)).append("]").withStyle(ChatFormatting.YELLOW);
		}
		if (m.group("code") != null) {
			return code(m.group("code"));
		}
		if (m.group("bold") != null) {
			return styled(inline(m.group("bold")), s -> s.withBold(true));
		}
		if (m.group("strike") != null) {
			return styled(inline(m.group("strike")), s -> s.withStrikethrough(true));
		}
		if (m.group("url") != null) {
			return link(m.group("linktext"), m.group("url"));
		}
		String italic = m.group("italic") != null ? m.group("italic") : m.group("italic2");
		return styled(inline(italic), s -> s.withItalic(true));
	}

	private static MutableComponent item(String id) {
		if (id.startsWith("decompile:")) {
			return Component.empty(); // a leftover request for mod code, not meant for the player
		}
		String clean = id.replaceFirst("^\\s*#", "");
		return ItemIcons.find(clean)
			.map(ItemIcons::named)
			.orElseGet(() -> Component.literal(clean.replaceAll(".*:", "").replace('_', ' ')));
	}

	/** `/command` is click-to-insert into the chat box; other code is just highlighted. */
	private static MutableComponent code(String code) {
		if (code.startsWith("/")) {
			return Component.literal(code).withStyle(style -> style
				.withColor(ChatFormatting.GREEN)
				.withClickEvent(new ClickEvent.SuggestCommand(code))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to put this command in the chat box"))));
		}
		return Component.literal(code).withStyle(ChatFormatting.AQUA);
	}

	private MutableComponent link(String label, String url) {
		try {
			URI uri = URI.create(url);
			return styled(inline(label), style -> style
				.withColor(ChatFormatting.BLUE)
				.withUnderlined(true)
				.withClickEvent(new ClickEvent.OpenUrl(uri))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(url))));
		} catch (IllegalArgumentException e) {
			return inline(label);
		}
	}

	private static MutableComponent styled(MutableComponent component, UnaryOperator<Style> style) {
		return Component.empty().withStyle(style).append(component);
	}

	private static String indent(String whitespace) {
		return "  ".repeat(Math.min(3, whitespace.length() / 2));
	}
}
