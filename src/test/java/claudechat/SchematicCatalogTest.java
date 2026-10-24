package claudechat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading the schematics folder within one tool call's budget, however many files it holds. */
class SchematicCatalogTest {
	@TempDir
	Path dir;

	@BeforeAll
	static void bootstrap() {
		GameBootstrap.init();
	}

	@Test
	void descriptionsStopAtTheirCount() throws IOException {
		for (int i = 0; i < Schematics.MAX_DESCRIBED + 3; i++) {
			SchematicFixtures.write(dir.resolve(String.format("s%05d.litematic", i)), SchematicFixtures.litematic());
		}

		Schematics.Catalog catalog = new Schematics.Folder(dir).entries();

		assertEquals(Schematics.MAX_DESCRIBED, catalog.entries().size());
		assertEquals(3, catalog.notRead());
		assertTrue(catalog.complete());
		assertEquals("s00000.litematic", catalog.entries().getFirst().path());
	}

	@Test
	void descriptionsShareOneByteBudget() throws IOException {
		// Each is within the per-file limit; together they are over the folder's.
		long each = 200L << 20;
		for (int i = 0; i < 6; i++) {
			SchematicFixtures.writeZeroBomb(dir.resolve("big" + i + ".schem"), each);
		}

		Schematics.Catalog catalog = new Schematics.Folder(dir).entries();

		assertEquals(5, catalog.entries().size());
		catalog.entries().forEach(entry -> assertInstanceOf(Schematics.Readable.class, entry));
		assertEquals(1, catalog.notRead());
	}

	@Test
	void exactPathOpensOnlyThatFile() throws IOException {
		Files.createDirectories(dir.resolve("a"));
		Files.write(dir.resolve("a/Broken.litematic"), new byte[] {1, 2, 3});
		SchematicFixtures.write(dir.resolve("b/Hut.schem"), SchematicFixtures.spongeV3());

		Schematics.Lookup byPath = new Schematics.Folder(dir).find("b/hut");

		assertEquals("b/Hut.schem", ((Schematics.Found) byPath.match()).entry().path());
		assertEquals(1, byPath.catalog().entries().size());

		Schematics.Lookup byName = new Schematics.Folder(dir).find("Sponge Three");

		assertEquals("b/Hut.schem", ((Schematics.Found) byName.match()).entry().path());
		assertEquals(2, byName.catalog().entries().size());
	}
}
