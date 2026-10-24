package claudechat;

import net.minecraft.resources.Identifier;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Claude's reply into chat lines: a Markdown subset plus four Minecraft tokens, [[item_id]], [[recipe:id]],
 * [[waypoint:Name|x y z|dimension]] and {{key.inventory}}. Pure: {@link ChatFormatter} turns the result into chat
 * components.
 */
public final class ChatMarkup {
	/**
	 * The system prompt's formatting rules: the grammar this class parses, and how {@link ChatFormatter} shows it. Keep
	 * it in step with the patterns below; a [[recipe:…]] grid, for one, is only drawn alone on its line.
	 */
	public static final String PROMPT = """
		Formatting (the mod renders this in chat):
		- Markdown subset only: **bold**, *italic*, ~~strike~~, `code`, [text](https://url), "# " headings, "- " bullets,
		  "1. " lists. No tables and no code blocks; chat is narrow (about 50 characters per line before wrapping).
		- Name items and blocks as [[id]] (e.g. [[iron_ingot]], [[minecraft:crafter]]). It shows the item's icon and name,
		  with the real tooltip on hover. Use exact ids (check lang/*.json if unsure). Counts: "3× [[iron_ingot]]".
		- To show how to make something, put [[recipe:<id>]] on its own line. <id> is a recipe file name under
		  data/<namespace>/recipe/ (e.g. [[recipe:minecraft:crafter]]), or the item it makes. It is drawn as an icon grid
		  from the game's data, so don't also list the ingredients, counts or layout in text. The grid can be the whole
		  answer; add a sentence only if it answers something the grid doesn't, and only if you've verified it.
		- Controls: {{key.inventory}}, {{key.use}}, {{key.attack}}, {{key.jump}}, {{key.sneak}}, {{key.sprint}}, {{key.drop}},
		  {{key.swapOffhand}}, {{key.pickItem}} show the player's own key binding.
		- Commands go in backticks and become click-to-insert, e.g. `/gamerule keep_inventory true`.
		""";

	private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*)$");
	private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
	private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d+)[.)]\\s+(.*)$");
	private static final Pattern RECIPE_LINE = Pattern.compile("^\\s*\\[\\[recipe:([^\\]]+)\\]\\]\\s*$");
	private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~).*$");
	// "|---|:--:|" or "--- | ---": only pipes, colons, dashes and spaces, with a pipe and a dash.
	private static final Pattern TABLE_DIVIDER = Pattern.compile("^(?=.*\\|)(?=.*-)[\\s|:-]+$");
	// "---", "***", "___" alone: a horizontal rule, which chat can't draw.
	private static final Pattern RULE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");

	// Order matters: earlier alternatives win when two start at the same place.
	private static final Pattern INLINE = Pattern.compile(String.join("|",
		"\\[\\[recipe:(?<recipe>[^\\]]+)\\]\\]",
		"\\[\\[waypoint:(?<waypoint>[^\\]]+)\\]\\]",
		"\\[\\[(?<item>[^\\]]+)\\]\\]",
		"\\{\\{(?<key>[\\w.]+)\\}\\}",
		"`(?<code>[^`]+)`",
		"\\*\\*(?<bold>.+?)\\*\\*",
		"~~(?<strike>.+?)~~",
		"\\[(?<linktext>[^\\]]+)\\]\\((?<url>https?://[^)\\s]+)\\)",
		"(?<![\\w*])\\*(?![\\s*])(?<italic>.+?)(?<![\\s*])\\*(?![\\w*])",
		"(?<![\\w_])_(?![\\s_])(?<italic2>.+?)(?<![\\s_])_(?![\\w_])"));

	/** One chat line of the reply. */
	public sealed interface Line {
	}

	/** A "# Heading". */
	public record Heading(List<Inline> text) implements Line {
	}

	/** A "- bullet"; {@code depth} is the nesting level, 0 to 3. */
	public record Bullet(int depth, List<Inline> text) implements Line {
	}

	/** A "1. numbered item"; {@code depth} is the nesting level, 0 to 3. */
	public record Numbered(int depth, String number, List<Inline> text) implements Line {
	}

	/** Ordinary text, including table rows (chat's proportional font can't line columns up, so cells join with " · "). */
	public record Paragraph(List<Inline> text) implements Line {
	}

	/** A line inside a ``` fence, kept exactly as written. */
	public record CodeLine(String text) implements Line {
	}

	/** A [[recipe:id]] alone on its line, shown as a grid. */
	public record RecipeGrid(Identifier id) implements Line {
	}

	/** A run of text within a line. */
	public sealed interface Inline {
	}

	/** Plain text. */
	public record Text(String text) implements Inline {
	}

	/** [[item_id]]: an item, or the name of something that isn't one (a tag, an unknown id). */
	public record Item(Identifier id) implements Inline {
	}

	/** [[recipe:id]] mid-sentence: names what the recipe makes. */
	public record RecipeResult(Identifier id) implements Inline {
	}

	/** [[waypoint:...]], with the text between "waypoint:" and "]]" for {@link Waypoints} to read. */
	public record Waypoint(String tag) implements Inline {
	}

	/** {{key.inventory}}: the player's key binding, always with the "key." prefix. */
	public record Key(String key) implements Inline {
	}

	/** `code`. */
	public record Code(String code) implements Inline {
	}

	/** **bold**, *italic* or ~~struck~~ text, which may contain any other inline. */
	public record Emphasis(Style style, List<Inline> content) implements Inline {
	}

	/** [label](https://...). */
	public record Link(List<Inline> label, URI url) implements Inline {
	}

	/** The kinds of {@link Emphasis}. */
	public enum Style {
		BOLD, ITALIC, STRIKETHROUGH
	}

	private ChatMarkup() {
	}

	/** The reply's lines, without blank lines, fences, table dividers and horizontal rules. */
	public static List<Line> parse(String text) {
		List<Line> lines = new ArrayList<>();
		boolean inFence = false;

		for (String raw : clean(text).split("\n")) {
			String line = raw.stripTrailing();

			if (FENCE.matcher(line).matches()) {
				inFence = !inFence;
				continue;
			}

			if (inFence) {
				lines.add(new CodeLine(line));
				continue;
			}

			if (line.isBlank() || TABLE_DIVIDER.matcher(line).matches() || RULE.matcher(line).matches()) {
				continue;
			}

			lines.add(line(line));
		}

		return lines;
	}

	/** Whether any of the lines draws a recipe, as a grid or by name: only then are the game's recipes needed. */
	public static boolean usesRecipes(List<Line> lines) {
		return lines.stream().anyMatch(line -> switch (line) {
			case RecipeGrid _ -> true;
			case Heading(var text) -> usesRecipe(text);
			case Bullet(_, var text) -> usesRecipe(text);
			case Numbered(_, _, var text) -> usesRecipe(text);
			case Paragraph(var text) -> usesRecipe(text);
			case CodeLine _ -> false;
		});
	}

	private static boolean usesRecipe(List<Inline> parts) {
		return parts.stream().anyMatch(part -> switch (part) {
			case RecipeResult _ -> true;
			case Emphasis(_, var content) -> usesRecipe(content);
			case Link(var label, _) -> usesRecipe(label);
			case Text _, Item _, Waypoint _, Key _, Code _ -> false;
		});
	}

	/** Inline Markdown and tokens in one line of text. */
	public static List<Inline> inline(String text) {
		List<Inline> out = new ArrayList<>();
		Matcher m = INLINE.matcher(text);
		int last = 0;

		while (m.find()) {
			if (m.start() > last) {
				out.add(new Text(text.substring(last, m.start())));
			}

			out.add(token(m));
			last = m.end();
		}

		if (last < text.length()) {
			out.add(new Text(text.substring(last)));
		}

		return out;
	}

	/**
	 * An id as Claude writes it in markup ("Iron Ingot", "minecraft:iron_ingot", "#minecraft:planks") as an
	 * identifier: lower case, spaces as underscores, "minecraft" when no namespace is given, a tag's "#" dropped.
	 * Empty when it can't be an identifier.
	 */
	public static Optional<Identifier> parseId(String raw) {
		String id = raw.strip().replaceFirst("^#", "").strip().toLowerCase(Locale.ROOT).replace(' ', '_');
		if (id.isEmpty()) {
			return Optional.empty();
		}

		return Optional.ofNullable(Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id));
	}

	/** Without carriage returns, and without § (it would start a formatting code). */
	private static String clean(String text) {
		return text.replace("\r", "").replace("§", "");
	}

	private static Line line(String line) {
		Matcher m;

		if ((m = RECIPE_LINE.matcher(line)).matches()) {
			Optional<Identifier> id = parseId(m.group(1));
			if (id.isPresent()) {
				return new RecipeGrid(id.get());
			}
		}

		if ((m = HEADING.matcher(line)).matches()) {
			return new Heading(inline(m.group(1)));
		}

		if ((m = BULLET.matcher(line)).matches()) {
			return new Bullet(depth(m.group(1)), inline(m.group(2)));
		}

		if ((m = NUMBERED.matcher(line)).matches()) {
			return new Numbered(depth(m.group(1)), m.group(2), inline(m.group(3)));
		}

		if (line.strip().startsWith("|")) {
			String row = line.strip().replaceAll("^\\||\\|$", "");
			return new Paragraph(inline(String.join(" · ", row.split("\\s*\\|\\s*")).strip()));
		}

		return new Paragraph(inline(line));
	}

	private static Inline token(Matcher m) {
		if (m.group("recipe") != null) {
			return idToken(m.group("recipe"), RecipeResult::new);
		}

		if (m.group("waypoint") != null) {
			return new Waypoint(m.group("waypoint"));
		}

		if (m.group("item") != null) {
			return idToken(m.group("item"), Item::new);
		}

		if (m.group("key") != null) {
			String key = m.group("key");
			return new Key(key.startsWith("key.") ? key : "key." + key);
		}

		if (m.group("code") != null) {
			return new Code(m.group("code"));
		}

		if (m.group("bold") != null) {
			return new Emphasis(Style.BOLD, inline(m.group("bold")));
		}

		if (m.group("strike") != null) {
			return new Emphasis(Style.STRIKETHROUGH, inline(m.group("strike")));
		}

		if (m.group("url") != null) {
			return link(m.group("linktext"), m.group("url"));
		}

		String italic = m.group("italic") != null ? m.group("italic") : m.group("italic2");
		return new Emphasis(Style.ITALIC, inline(italic));
	}

	/** An id token, or its text as written when it isn't a valid id. */
	private static Inline idToken(String raw, Function<Identifier, Inline> token) {
		return parseId(raw).map(token).orElseGet(() -> new Text(raw.strip()));
	}

	/** A link, or just its label when the URL is malformed. */
	private static Inline link(String label, String url) {
		try {
			return new Link(inline(label), URI.create(url));
		} catch (IllegalArgumentException e) {
			return new Text(label);
		}
	}

	/** Two spaces per nesting level, at most three levels. */
	private static int depth(String whitespace) {
		return Math.min(3, whitespace.length() / 2);
	}
}
