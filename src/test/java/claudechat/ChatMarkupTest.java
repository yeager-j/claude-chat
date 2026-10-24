package claudechat;

import claudechat.ChatMarkup.Bullet;
import claudechat.ChatMarkup.Code;
import claudechat.ChatMarkup.CodeLine;
import claudechat.ChatMarkup.Emphasis;
import claudechat.ChatMarkup.Heading;
import claudechat.ChatMarkup.Item;
import claudechat.ChatMarkup.Key;
import claudechat.ChatMarkup.Link;
import claudechat.ChatMarkup.Numbered;
import claudechat.ChatMarkup.Paragraph;
import claudechat.ChatMarkup.RecipeGrid;
import claudechat.ChatMarkup.RecipeResult;
import claudechat.ChatMarkup.Style;
import claudechat.ChatMarkup.Text;
import claudechat.ChatMarkup.Waypoint;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMarkupTest {
	private static Identifier mc(String path) {
		return Identifier.withDefaultNamespace(path);
	}

	@Test
	void blockLines() {
		assertEquals(List.of(
				new Heading(List.of(new Text("Crafter"))),
				new Bullet(0, List.of(new Text("one"))),
				new Bullet(1, List.of(new Text("nested"))),
				new Numbered(0, "2", List.of(new Text("two"))),
				new Paragraph(List.of(new Text("plain")))),
			ChatMarkup.parse("# Crafter\n- one\n  * nested\n2) two\nplain"));
	}

	@Test
	void blankLinesAndCarriageReturnsAreDropped() {
		assertEquals(List.of(new Paragraph(List.of(new Text("a"))), new Paragraph(List.of(new Text("b")))),
			ChatMarkup.parse("a\r\n\r\n   \nb"));
	}

	@Test
	void recipeAloneOnItsLineIsAGridWithANormalizedId() {
		assertEquals(List.of(new RecipeGrid(mc("crafter")), new RecipeGrid(Identifier.fromNamespaceAndPath("create", "cogwheel"))),
			ChatMarkup.parse("  [[recipe:Crafter]]  \n[[recipe:create:cogwheel]]"));
	}

	@Test
	void onlyARecipeTokenNeedsTheRecipes() {
		assertFalse(ChatMarkup.usesRecipes(ChatMarkup.parse("# Pistons\n- Use [[iron_ingot]] and `/give`\n**bold** [link](https://x.y)")));
		assertTrue(ChatMarkup.usesRecipes(ChatMarkup.parse("[[recipe:piston]]")));
		assertTrue(ChatMarkup.usesRecipes(ChatMarkup.parse("1. Make **a [[recipe:piston]] first**")));
		assertTrue(ChatMarkup.usesRecipes(ChatMarkup.parse("[see [[recipe:piston]]](https://x.y)")));
	}

	@Test
	void recipeMidSentenceNamesItsResult() {
		assertEquals(List.of(new Text("Smelt it: "), new RecipeResult(mc("iron_ingot_from_smelting_raw_iron"))),
			ChatMarkup.inline("Smelt it: [[recipe:iron_ingot_from_smelting_raw_iron]]"));
	}

	@Test
	void fencedLinesAreLiteral() {
		assertEquals(List.of(
				new CodeLine("# not a heading"),
				new CodeLine("  **not bold** [[stone]]"),
				new CodeLine(""),
				new Paragraph(List.of(new Text("after")))),
			ChatMarkup.parse("```mcfunction\n# not a heading\n  **not bold** [[stone]]\n\n```\nafter"));
	}

	@Test
	void anUnclosedFenceRunsToTheEnd() {
		assertEquals(List.of(new CodeLine("- still code")), ChatMarkup.parse("```\n- still code"));
	}

	@Test
	void tablesBecomeRowsWithoutTheirDivider() {
		assertEquals(List.of(new Paragraph(List.of(new Text("Item · Count"))), new Paragraph(List.of(new Text("Stone · 4")))),
			ChatMarkup.parse("| Item | Count |\n|:-----|-----:|\n| Stone | 4 |"));
		assertEquals(List.of(), ChatMarkup.parse("--- | ---"));
	}

	@Test
	void onlyTableDividersAndRulesAreDropped() {
		assertEquals(List.of(new Paragraph(List.of(new Text("-- not a divider"))), new Paragraph(List.of(new Text("--> arrow")))),
			ChatMarkup.parse("-- not a divider\n---\n***\n--> arrow"));
	}

	@Test
	void minecraftTokens() {
		assertEquals(List.of(
				new Item(mc("iron_ingot")),
				new Text(" "),
				new Item(mc("iron_ingot")),
				new Text(" "),
				new Item(mc("planks")),
				new Text(" "),
				new Waypoint("End City|1 ~ 2|the_end"),
				new Text(" "),
				new Key("key.inventory"),
				new Key("key.use")),
			ChatMarkup.inline("[[Iron Ingot]] [[minecraft:iron_ingot]] [[#minecraft:planks]] [[waypoint:End City|1 ~ 2|the_end]] "
				+ "{{inventory}}{{key.use}}"));
	}

	@Test
	void invalidIdsStayText() {
		assertEquals(List.of(new Text("what?!")), ChatMarkup.inline("[[what?!]]"));
	}

	@Test
	void markdownInline() {
		assertEquals(List.of(
				new Emphasis(Style.BOLD, List.of(new Text("a "), new Item(mc("stone")))),
				new Text(" "),
				new Emphasis(Style.ITALIC, List.of(new Text("b"))),
				new Text(" "),
				new Emphasis(Style.ITALIC, List.of(new Text("c"))),
				new Text(" "),
				new Emphasis(Style.STRIKETHROUGH, List.of(new Text("d"))),
				new Text(" "),
				new Code("/give @s stone"),
				new Text(" "),
				new Link(List.of(new Text("wiki")), URI.create("https://minecraft.wiki/w/Crafter"))),
			ChatMarkup.inline("**a [[stone]]** *b* _c_ ~~d~~ `/give @s stone` [wiki](https://minecraft.wiki/w/Crafter)"));
	}

	@Test
	void snakeCaseIsNotItalic() {
		assertEquals(List.of(new Text("snake_case_name and 2*3*4")), ChatMarkup.inline("snake_case_name and 2*3*4"));
	}

	@Test
	void malformedLinkKeepsItsLabel() {
		assertEquals(List.of(new Text("wiki")), ChatMarkup.inline("[wiki](https://example.com/a|b)"));
	}

	@Test
	void idsLowerCaseTheSameInEveryLocale() {
		Locale before = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("tr"));
			assertEquals(Optional.of(mc("iron_ingot")), ChatMarkup.parseId("IRON_INGOT"));
		} finally {
			Locale.setDefault(before);
		}
	}

	@Test
	void idParsing() {
		assertEquals(Optional.of(mc("oak_planks")), ChatMarkup.parseId(" Oak Planks "));
		assertEquals(Optional.of(Identifier.fromNamespaceAndPath("c", "ingots/iron")), ChatMarkup.parseId("#c:ingots/iron"));
		assertEquals(Optional.empty(), ChatMarkup.parseId("  "));
		assertEquals(Optional.empty(), ChatMarkup.parseId("a:b:c"));
	}
}
