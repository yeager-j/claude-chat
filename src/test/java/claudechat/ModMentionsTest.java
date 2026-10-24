package claudechat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModMentionsTest {
	private final ModMentions mentions = new ModMentions(Set.of(
		"how", "do", "i", "a", "the", "create", "nether", "portal", "better", "combat", "get", "stats", "s", "mod", "menu", "open"));

	private boolean mentions(String question, String name) {
		return mentions.mentions(ModMentions.words(question), name);
	}

	@Test
	void wordsSplitOnAnythingButLettersAndDigits() {
		assertEquals(List.of("xaero", "s", "minimap", "2"), ModMentions.words("Xaero's Minimap (2)"));
		assertEquals(List.of(), ModMentions.words("  ?! "));
	}

	@Test
	void namesMatchWithOrWithoutSpaces() {
		assertTrue(mentions("how does vein miner work", "Vein Miner"));
		assertTrue(mentions("how does veinminer work", "Vein Miner"));
		assertTrue(mentions("is Vein-Miner configurable", "veinminer"));
		assertTrue(mentions("what does xaeros minimap show", "Xaero's Minimap"));
		assertTrue(mentions("what does xaero minimap show", "xaerominimap"));
	}

	@Test
	void namesMatchOnlyWholeWords() {
		assertFalse(mentions("superveinminerx is broken", "veinminer"));
		assertFalse(mentions("veinminers", "veinminer"));
	}

	@Test
	void commonWordsAloneNeverMatch() {
		assertFalse(mentions("how do I create a nether portal", "Create"));
		assertFalse(mentions("how do I get better combat stats", "Better Combat"));
		assertFalse(mentions("how do I get better combat stats", "bettercombat"));
		assertFalse(mentions("open the mod menu", "Mod Menu"));
	}

	@Test
	void anUncommonWordMakesTheSpellingDistinctive() {
		assertTrue(mentions("is bettercombat installed", "bettercombat"));
		assertTrue(mentions("what's in modmenu", "Mod Menu"));
	}

	@Test
	void shortNamesNeverMatch() {
		assertFalse(mentions("does rei show it", "rei"));
	}
}
