package claudechat;

import net.minecraft.server.permissions.LevelBasedPermissionSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each tier's policy, with cheats off and on: which game commands run, ask or are refused, the permission level they
 * run at, and the Claude Code flags and prompt the tier gives a question. Cheats change only the level, never whether
 * a command that changes something may run.
 */
class PolicyTest {
	private static final String GAME_DIR = "C:/game";

	@ParameterizedTest
	@CsvSource({
		"READ_ONLY, false, REFUSE",
		"READ_ONLY, true,  REFUSE",
		"ASK,       false, ASK",
		"ASK,       true,  ASK",
		"FULL,      false, RUN",
		"FULL,      true,  RUN",
	})
	void tierDecidesChangesAndCheatsDecideTheLevel(PermissionTier tier, boolean cheats, PermissionTier.CommandRule changes) {
		Policy policy = new Policy(tier, cheats);

		assertEquals(PermissionTier.CommandRule.RUN, policy.commandRule(true));
		assertEquals(changes, policy.commandRule(false));
		assertSame(cheats ? LevelBasedPermissionSet.GAMEMASTER : LevelBasedPermissionSet.ALL,
			policy.permissions(LevelBasedPermissionSet.ALL));
		// Cheats never lower a player who has more.
		assertSame(LevelBasedPermissionSet.OWNER, policy.permissions(LevelBasedPermissionSet.OWNER));
	}

	@Test
	void readOnlyOffersNoWriteToolsAndNoGameFolder() {
		List<String> flags = PermissionTier.READ_ONLY.cliFlags(true, GAME_DIR);

		assertEquals(PermissionTier.READ_TOOLS, valueAfter(flags, "--tools"));
		assertFalse(flags.contains("--add-dir"), flags.toString());
		// Nobody can be asked, so whatever isn't allowed is denied.
		assertEquals("dontAsk", valueAfter(flags, "--permission-mode"));
		assertEquals(PermissionTier.APPROVE_TOOL, valueAfter(flags, "--disallowedTools"));
		assertTrue(PermissionTier.READ_ONLY.prompt(true).contains("read only"));
	}

	/** A bare Read rule would allow every file the player can read, wherever text in the game asks Claude to look. */
	@ParameterizedTest
	@CsvSource({"READ_ONLY, true", "READ_ONLY, false", "ASK, true", "ASK, false"})
	void readsAreAllowedOnlyInTheWorkingDirectory(PermissionTier tier, boolean toolsServer) {
		List<String> allowed = List.of(valueAfter(tier.cliFlags(toolsServer, GAME_DIR), "--allowedTools").split(","));

		assertTrue(allowed.contains("Read(./**)"), allowed.toString());
		for (String tool : List.of("Read", "Grep", "Glob", "Bash", "Edit", "Write")) {
			assertFalse(allowed.contains(tool), tool + " allowed everywhere: " + allowed);
		}
	}

	@Test
	void askSendsEveryPromptToThePlayer() {
		List<String> flags = PermissionTier.ASK.cliFlags(true, GAME_DIR);

		assertTrue(valueAfter(flags, "--tools").contains("Bash"), flags.toString());
		assertEquals(GAME_DIR, valueAfter(flags, "--add-dir"));
		assertEquals(PermissionTier.APPROVE_TOOL, valueAfter(flags, "--permission-prompt-tool"));
		assertFalse(flags.contains("--permission-mode"), flags.toString());
		assertTrue(PermissionTier.ASK.prompt(true).contains("approves each change"));
	}

	@Test
	void askWithoutTheToolsServerDeniesWhatItCantAsk() {
		List<String> flags = PermissionTier.ASK.cliFlags(false, GAME_DIR);

		assertEquals("none", valueAfter(flags, "--permission-prompts"));
		assertFalse(flags.contains("--permission-prompt-tool"), flags.toString());
		assertFalse(valueAfter(flags, "--allowedTools").contains(McpServer.CLAUDE_PREFIX), flags.toString());
	}

	@Test
	void fullBypassesPromptsAndNeedsConfirming() {
		List<String> flags = PermissionTier.FULL.cliFlags(true, GAME_DIR);

		assertEquals("bypassPermissions", valueAfter(flags, "--permission-mode"));
		assertTrue(PermissionTier.FULL.prompt(true).contains("Nothing asks the player first"));
		assertTrue(PermissionTier.FULL.confirmation().isPresent());
		assertTrue(PermissionTier.READ_ONLY.confirmation().isEmpty());
		assertTrue(PermissionTier.ASK.confirmation().isEmpty());
	}

	@Test
	void tiersParseByIdOnly() {
		assertEquals(PermissionTier.ASK, PermissionTier.parse(" Ask ").orElseThrow());
		assertTrue(PermissionTier.parse("Full access").isEmpty());
		assertEquals(List.of("read_only", "ask", "full"), PermissionTier.ids());
	}

	private static String valueAfter(List<String> flags, String flag) {
		int at = flags.indexOf(flag);
		assertTrue(at >= 0 && at + 1 < flags.size(), flag + " missing from " + flags);
		return flags.get(at + 1);
	}
}
