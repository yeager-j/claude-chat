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
 * a command that changes something may run; web access changes only whether the web tools are offered.
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
		Policy policy = new Policy(tier, cheats, false);

		assertEquals(PermissionTier.CommandRule.RUN, policy.commandRule(true));
		assertEquals(changes, policy.commandRule(false));
		assertSame(cheats ? LevelBasedPermissionSet.GAMEMASTER : LevelBasedPermissionSet.ALL,
			policy.permissions(LevelBasedPermissionSet.ALL));
		// Cheats never lower a player who has more.
		assertSame(LevelBasedPermissionSet.OWNER, policy.permissions(LevelBasedPermissionSet.OWNER));
	}

	@Test
	void readOnlyOffersNoWriteToolsAndNoGameFolder() {
		List<String> flags = PermissionTier.READ_ONLY.cliFlags(false, true, GAME_DIR);

		assertEquals(PermissionTier.READ_TOOLS, valueAfter(flags, "--tools"));
		assertFalse(flags.contains("--add-dir"), flags.toString());
		assertEquals("default", valueAfter(flags, "--permission-mode"));
		assertFalse(flags.contains("--permission-prompt-tool"), flags.toString());
		assertEquals(PermissionTier.APPROVE_TOOL, valueAfter(flags, "--disallowedTools"));
		assertTrue(PermissionTier.READ_ONLY.prompt(true).contains("read only"));
	}

	@Test
	void askSendsEveryPromptToThePlayer() {
		List<String> flags = PermissionTier.ASK.cliFlags(false, true, GAME_DIR);

		assertTrue(valueAfter(flags, "--tools").contains("Bash"), flags.toString());
		assertEquals(GAME_DIR, valueAfter(flags, "--add-dir"));
		assertEquals(PermissionTier.APPROVE_TOOL, valueAfter(flags, "--permission-prompt-tool"));
		assertEquals("default", valueAfter(flags, "--permission-mode"));
		assertTrue(PermissionTier.ASK.prompt(true).contains("approves each change"));
	}

	@Test
	void askWithoutTheToolsServerDeniesWhatItCantAsk() {
		List<String> flags = PermissionTier.ASK.cliFlags(false, false, GAME_DIR);

		assertEquals("none", valueAfter(flags, "--permission-prompts"));
		assertFalse(flags.contains("--permission-prompt-tool"), flags.toString());
		assertFalse(valueAfter(flags, "--allowedTools").contains(McpServer.CLAUDE_PREFIX), flags.toString());
	}

	/** A bare Read, Grep or Glob rule would pre-approve reading any file the player's account can read. */
	@ParameterizedTest
	@CsvSource({
		"READ_ONLY, true,  true",
		"READ_ONLY, false, false",
		"ASK,       true,  false",
		"ASK,       false, true",
	})
	void fileReadsArePreApprovedOnlyInTheWorkingDirectory(PermissionTier tier, boolean toolsServer, boolean web) {
		List<String> allowed = List.of(valueAfter(tier.cliFlags(web, toolsServer, GAME_DIR), "--allowedTools").split(","));

		for (String fileTool : List.of("Read", "Grep", "Glob")) {
			List<String> rules = allowed.stream().filter(rule -> rule.equals(fileTool) || rule.startsWith(fileTool + "(")).toList();

			assertEquals(List.of(fileTool + "(./**)"), rules, allowed.toString());
		}
	}

	/** Web off leaves the web tools out of --tools, not just unapproved: Ask would otherwise ask about each fetch. */
	@ParameterizedTest
	@CsvSource({
		"READ_ONLY, false",
		"READ_ONLY, true",
		"ASK,       false",
		"ASK,       true",
		"FULL,      false",
		"FULL,      true",
	})
	void webToolsFollowTheWebSettingInEveryTier(PermissionTier tier, boolean web) {
		List<String> flags = tier.cliFlags(web, true, GAME_DIR);
		List<String> offered = List.of(valueAfter(flags, "--tools").split(","));
		List<String> allowed = flags.contains("--allowedTools") ? List.of(valueAfter(flags, "--allowedTools").split(",")) : List.of();

		for (String webTool : List.of("WebSearch", "WebFetch")) {
			assertEquals(web, offered.contains(webTool), flags.toString());
			// Full allows everything through bypassPermissions, not --allowedTools.
			assertEquals(web && tier != PermissionTier.FULL, allowed.contains(webTool), flags.toString());
		}

		assertTrue(offered.containsAll(List.of("Read", "Grep", "Glob")), flags.toString());
	}

	@Test
	void fullBypassesPromptsAndNeedsConfirming() {
		List<String> flags = PermissionTier.FULL.cliFlags(false, true, GAME_DIR);

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
