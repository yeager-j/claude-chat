package claudechat;

import claudechat.ChatMarkup.Bullet;
import claudechat.ChatMarkup.Code;
import claudechat.ChatMarkup.CodeLine;
import claudechat.ChatMarkup.Emphasis;
import claudechat.ChatMarkup.Heading;
import claudechat.ChatMarkup.Inline;
import claudechat.ChatMarkup.Item;
import claudechat.ChatMarkup.Key;
import claudechat.ChatMarkup.Line;
import claudechat.ChatMarkup.Link;
import claudechat.ChatMarkup.Numbered;
import claudechat.ChatMarkup.Paragraph;
import claudechat.ChatMarkup.RecipeGrid;
import claudechat.ChatMarkup.RecipeResult;
import claudechat.ChatMarkup.Text;
import claudechat.ChatMarkup.Waypoint;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Turns Claude's reply into chat components, from the lines {@link ChatMarkup} parses: Markdown, item icons, recipe
 * grids, waypoint buttons and key bindings.
 */
public final class ChatFormatter {
	/**
	 * A formatted answer. {@code startsWithGrid} is true when the first line is a recipe grid, whose rows must all
	 * start at the same x, so nothing (such as a [Claude] prefix) may go in front of it.
	 */
	public record Answer(List<Component> lines, boolean startsWithGrid) {
	}

	private final RecipeRenderer recipes;

	public ChatFormatter(RecipeRenderer recipes) {
		this.recipes = recipes;
	}

	/** A progress note ("Checking the recipe data") as one dim italic line. Only the first line is kept. */
	public MutableComponent formatProgress(String text) {
		String line = text.replace("\r", "").replace("§", "").strip().lines()
			.filter(l -> !l.isBlank()).findFirst().orElse("").strip();
		line = line.replaceAll("^#+\\s*", "").replaceAll("[:.…]+$", "");
		if (line.length() > 80) {
			line = line.substring(0, 77).strip() + "...";
		}
		return styled(inline(ChatMarkup.inline(line)), s -> s.withItalic(true).withColor(ChatFormatting.GRAY));
	}

	/** The answer's chat lines; never empty. */
	public Answer format(String text) {
		List<Component> lines = new ArrayList<>();
		boolean startsWithGrid = false;

		for (Line line : ChatMarkup.parse(text)) {
			if (line instanceof RecipeGrid(var id)) {
				RecipeRenderer.Lines recipe = recipes.render(id);
				if (lines.isEmpty()) {
					startsWithGrid = recipe.grid();
				}
				lines.addAll(recipe.lines());
			} else {
				lines.add(line(line));
			}
		}

		if (lines.isEmpty()) {
			lines.add(Component.literal("(no answer)").withStyle(ChatFormatting.GRAY));
		}

		return new Answer(lines, startsWithGrid);
	}

	private MutableComponent line(Line line) {
		return switch (line) {
			case Heading(var text) -> styled(inline(text), s -> s.withBold(true).withColor(ChatFormatting.GOLD));
			case Bullet(var depth, var text) -> Component.empty()
				.append(Component.literal(indent(depth) + "• ").withStyle(ChatFormatting.GRAY))
				.append(inline(text));
			case Numbered(var depth, var number, var text) -> Component.empty()
				.append(Component.literal(indent(depth) + number + ". ").withStyle(ChatFormatting.GRAY))
				.append(inline(text));
			case Paragraph(var text) -> inline(text);
			case CodeLine(var text) -> Component.literal(text).withStyle(ChatFormatting.AQUA);
			case RecipeGrid grid -> throw new IllegalArgumentException("Recipe grids take several lines: " + grid);
		};
	}

	/** Bold/italic/strike content is built recursively, so it can contain icons. */
	private MutableComponent inline(List<Inline> parts) {
		// No color here: children override their parent's style, so headings and links set the color around this.
		MutableComponent out = Component.empty();
		for (Inline part : parts) {
			out.append(inline(part));
		}
		return out;
	}

	private MutableComponent inline(Inline part) {
		return switch (part) {
			case Text(var text) -> Component.literal(text);
			case Item(var id) -> ItemIcons.named(id);
			// A recipe in the middle of a sentence: show what it makes instead of a grid.
			case RecipeResult(var id) -> recipes.resultName(id);
			case Waypoint(var tag) -> Waypoints.button(tag);
			case Key(var key) -> Component.literal("[").append(Component.keybind(key)).append("]").withStyle(ChatFormatting.YELLOW);
			case Code(var code) -> code(code);
			case Emphasis(var style, var content) -> styled(inline(content), switch (style) {
				case BOLD -> s -> s.withBold(true);
				case ITALIC -> s -> s.withItalic(true);
				case STRIKETHROUGH -> s -> s.withStrikethrough(true);
			});
			case Link(var label, var url) -> styled(inline(label), style -> style
				.withColor(ChatFormatting.BLUE)
				.withUnderlined(true)
				.withClickEvent(new ClickEvent.OpenUrl(url))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(url.toString()))));
		};
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

	private static MutableComponent styled(MutableComponent component, UnaryOperator<Style> style) {
		return Component.empty().withStyle(style).append(component);
	}

	private static String indent(int depth) {
		return "  ".repeat(depth);
	}
}
