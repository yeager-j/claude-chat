package claudechat;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeConfigTest {
	private static Properties file(String... keysAndValues) {
		Properties props = new Properties();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			props.setProperty(keysAndValues[i], keysAndValues[i + 1]);
		}

		return props;
	}

	@Test
	void fileWithoutVersionMovesOldHaikuDefaultToSonnet() {
		ClaudeConfig config = ClaudeConfig.fromProperties(file("model", "haiku"));

		assertEquals("sonnet", config.model);
		assertTrue(config.fileOutdated());
		assertEquals(String.valueOf(ClaudeConfig.CURRENT_VERSION), config.toProperties().getProperty("configVersion"));
	}

	@Test
	void haikuChosenAfterVersion1IsKept() {
		ClaudeConfig config = ClaudeConfig.fromProperties(file("configVersion", "1", "model", "haiku"));

		assertEquals("haiku", config.model);
	}

	@Test
	void newerVersionIsNeitherMigratedNorDowngraded() {
		ClaudeConfig config = ClaudeConfig.fromProperties(file("configVersion", "7", "model", "haiku"));

		assertEquals("haiku", config.model);
		assertEquals("7", config.toProperties().getProperty("configVersion"));
	}

	@Test
	void unknownKeysSurviveASave() {
		ClaudeConfig config = ClaudeConfig.fromProperties(file("configVersion", "2", "futureSetting", "on", "modle", "opus"));

		Properties saved = config.toProperties();

		assertEquals("on", saved.getProperty("futureSetting"));
		assertEquals("opus", saved.getProperty("modle"));
	}

	@Test
	void effortIgnoresCase() {
		ClaudeConfig config = ClaudeConfig.fromProperties(file("effort", "High"));

		assertEquals(ClaudeConfig.Effort.HIGH, config.effort);
		assertEquals(Optional.of("high"), config.effort.flag());
		assertEquals("high", config.toProperties().getProperty("effort"));
	}

	@Test
	void emptyEffortIsTheDefaultAndPassesNoFlag() {
		ClaudeConfig config = ClaudeConfig.fromProperties(file("effort", ""));

		assertEquals(ClaudeConfig.Effort.DEFAULT, config.effort);
		assertEquals(Optional.empty(), config.effort.flag());
		assertEquals("", config.toProperties().getProperty("effort"));
	}

	@Test
	void badValuesFallBackToDefaults() {
		ClaudeConfig config = ClaudeConfig.fromProperties(file(
			"effort", "extreme",
			"permissions", "everything",
			"timeoutSeconds", "-5",
			"buildKnowledge", "maybe",
			"chatPrefix", "",
			"model", " "));

		assertEquals(ClaudeConfig.Effort.DEFAULT, config.effort);
		assertEquals(ClaudeConfig.Permissions.READ_ONLY, config.permissions);
		assertEquals(300, config.timeoutSeconds);
		assertTrue(config.buildKnowledge);
		assertEquals("@claude", config.chatPrefix);
		assertEquals("sonnet", config.model);
	}

	@Test
	void unparsedValueStaysInTheFileUntilTheSettingChanges() {
		ClaudeConfig config = ClaudeConfig.fromProperties(file("effort", "ultra"));

		assertEquals("ultra", config.toProperties().getProperty("effort"));

		config.effort = ClaudeConfig.Effort.LOW;
		assertEquals("low", config.toProperties().getProperty("effort"));
	}

	@Test
	void listsAndPathsAreParsed() {
		ClaudeConfig config = ClaudeConfig.fromProperties(file(
			"knowledgeInclude", " create, sodium ,,",
			"claudePath", "",
			"cheats", "TRUE"));

		assertEquals(Set.of("create", "sodium"), config.knowledgeInclude);
		assertEquals(Optional.empty(), config.claudePath);
		assertTrue(config.cheats);
		assertEquals("create,sodium", config.toProperties().getProperty("knowledgeInclude"));
	}

	@Test
	void savedFileReadsBackTheSame() {
		ClaudeConfig config = ClaudeConfig.fromProperties(new Properties());
		config.claudePath = Optional.of(Path.of("claude"));
		config.model = "opus";
		config.effort = ClaudeConfig.Effort.MAX;
		config.timeoutSeconds = 60;
		config.permissions = ClaudeConfig.Permissions.ASK;
		config.knowledgeExclude.add("jei");

		ClaudeConfig reread = ClaudeConfig.fromProperties(config.toProperties());

		assertEquals(Optional.of(Path.of("claude")), reread.claudePath);
		assertEquals("opus", reread.model);
		assertEquals(ClaudeConfig.Effort.MAX, reread.effort);
		assertEquals(60, reread.timeoutSeconds);
		assertEquals(ClaudeConfig.Permissions.ASK, reread.permissions);
		assertEquals(Set.of("jei"), reread.knowledgeExclude);
		assertFalse(reread.fileOutdated());
	}

	@Test
	void missingFileNeedsWriting() {
		assertTrue(ClaudeConfig.fromProperties(new Properties()).fileOutdated());
	}
}
