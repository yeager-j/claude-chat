package claudechat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeFilesTest {
	@TempDir
	Path temp;

	@Test
	void plainNamesAreKept() {
		assertEquals("veinminer", KnowledgeFiles.folderName("veinminer"));
		assertEquals("1.2.3+mc26.3", KnowledgeFiles.folderName("1.2.3+mc26.3"));
	}

	@Test
	void namesThatNeedChangingNeverCollide() {
		assertNotEquals(KnowledgeFiles.folderName("a b"), KnowledgeFiles.folderName("a_b"));
		assertNotEquals(KnowledgeFiles.folderName("a b"), KnowledgeFiles.folderName("a:b"));
		assertTrue(KnowledgeFiles.folderName("a b").startsWith("a_b~"));
	}

	@Test
	void dotNamesAreNotPlain() {
		assertNotEquals("..", KnowledgeFiles.folderName(".."));
		assertNotEquals(".", KnowledgeFiles.folderName("."));
		assertNotEquals("1.0.", KnowledgeFiles.folderName("1.0."));
		assertFalse(KnowledgeFiles.folderName("").isEmpty());
	}

	@Test
	void resolveInsideRejectsEscapes() {
		assertEquals(Optional.of(temp.resolve("a/b.json")), KnowledgeFiles.resolveInside(temp, "a/b.json"));
		assertEquals(Optional.empty(), KnowledgeFiles.resolveInside(temp, "../outside.json"));
		assertEquals(Optional.empty(), KnowledgeFiles.resolveInside(temp, "a/../../outside.json"));
		assertEquals(Optional.empty(), KnowledgeFiles.resolveInside(temp, "."));
	}

	@Test
	void copyTreeCopiesMatchingFiles() throws IOException {
		Path from = temp.resolve("from");
		Files.createDirectories(from.resolve("x/y"));
		Files.writeString(from.resolve("x/y/A.class"), "a");
		Files.writeString(from.resolve("x/readme.txt"), "b");

		Path to = temp.resolve("to");
		KnowledgeFiles.copyTree(from, to, Decompiler::isClassFile);

		assertEquals("a", Files.readString(to.resolve("x/y/A.class")));
		assertFalse(Files.exists(to.resolve("x/readme.txt")));
	}

	@Test
	void copyTreeOfAMissingFolderDoesNothing() throws IOException {
		KnowledgeFiles.copyTree(temp.resolve("missing"), temp.resolve("to"), file -> true);

		assertFalse(Files.exists(temp.resolve("to")));
	}
}
