package claudechat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Writes the schematic game test's folder: {@code CLAUDECHAT_EXPORT_SCHEMATICS=<folder> gradlew test --rerun --tests
 * claudechat.ExportSchematicFixtures}, then run the game test with {@code CLAUDECHAT_SCHEMATIC_TEST=<folder>}.
 */
@EnabledIfEnvironmentVariable(named = "CLAUDECHAT_EXPORT_SCHEMATICS", matches = ".+")
class ExportSchematicFixtures {
	@BeforeAll
	static void bootstrap() {
		GameBootstrap.init();
	}

	@Test
	void export() throws IOException {
		SchematicFixtures.writeAll(Path.of(System.getenv("CLAUDECHAT_EXPORT_SCHEMATICS")));
	}
}
