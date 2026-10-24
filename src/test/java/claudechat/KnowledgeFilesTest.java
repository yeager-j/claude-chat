package claudechat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

	@Test
	void copyTreeSkipsJunctions() throws Exception {
		Path from = sourceWithLinkedFolder((link, target) -> FileLinks.junction(link, target));

		KnowledgeFiles.copyTree(from, temp.resolve("to"), file -> true);

		assertTrue(Files.isRegularFile(temp.resolve("to/own.json")));
		assertFalse(Files.exists(temp.resolve("to/linked")));
	}

	@Test
	void copyTreeSkipsSymbolicLinks() throws Exception {
		Path from = sourceWithLinkedFolder(FileLinks::symbolicLink);
		FileLinks.symbolicLink(from.resolve("secret-link.txt"), temp.resolve("outside/secret.txt"));

		KnowledgeFiles.copyTree(from, temp.resolve("to"), file -> true);

		assertTrue(Files.isRegularFile(temp.resolve("to/own.json")));
		assertFalse(Files.exists(temp.resolve("to/linked")));
		assertFalse(Files.exists(temp.resolve("to/secret-link.txt")));
	}

	@Test
	void deleteTreeRemovesAJunctionButNotWhatItLeadsTo() throws Exception {
		Path outside = outsideFolder();
		Path tree = temp.resolve("tree");
		Files.createDirectories(tree);
		FileLinks.junction(tree.resolve("linked"), outside);

		KnowledgeFiles.deleteTree(tree);

		assertFalse(Files.exists(tree));
		assertEquals("keep me", Files.readString(outside.resolve("secret.txt")));
	}

	@Test
	void deleteTreeGivenAJunctionRemovesOnlyTheJunction() throws Exception {
		Path outside = outsideFolder();
		Path link = FileLinks.junction(temp.resolve("link"), outside);

		KnowledgeFiles.deleteTree(link);

		assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS));
		assertEquals("keep me", Files.readString(outside.resolve("secret.txt")));
	}

	@Test
	void deleteTreeRemovesASymbolicLinkButNotWhatItLeadsTo() throws Exception {
		Path outside = outsideFolder();
		Path tree = temp.resolve("tree");
		Files.createDirectories(tree);
		FileLinks.symbolicLink(tree.resolve("linked"), outside);

		KnowledgeFiles.deleteTree(tree);

		assertFalse(Files.exists(tree));
		assertEquals("keep me", Files.readString(outside.resolve("secret.txt")));
	}

	@Test
	void pruningDoesNotEnterJunctions() throws Exception {
		Path outside = outsideFolder();
		Files.createDirectories(outside.resolve("empty"));
		Path tree = temp.resolve("tree");
		Files.createDirectories(tree.resolve("a/b"));
		FileLinks.junction(tree.resolve("linked"), outside);

		KnowledgeFiles.pruneEmptyDirectories(tree);

		assertFalse(Files.exists(tree.resolve("a")));
		assertTrue(Files.isDirectory(outside.resolve("empty")));
	}

	@Test
	void realLocationThroughAJunctionIsRefused() throws Exception {
		Path root = temp.resolve("root");
		Files.createDirectories(root.resolve("mods"));
		FileLinks.junction(root.resolve("linked"), outsideFolder());

		KnowledgeFiles.requireRealLocationInside(root, root.resolve("mods/foo/1.0.partial"));
		assertThrows(IOException.class, () -> KnowledgeFiles.requireRealLocationInside(root, root.resolve("linked/foo/1.0.partial")));
	}

	@Test
	void plainFoldersAreNotLinks() throws IOException {
		Path folder = Files.createDirectories(temp.resolve("plain"));
		Path file = Files.writeString(temp.resolve("file.txt"), "x");

		assertTrue(KnowledgeFiles.isPlainDirectory(folder));
		assertFalse(KnowledgeFiles.isPlainDirectory(file));
		assertFalse(KnowledgeFiles.isLink(folder, Files.readAttributes(folder, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)));
	}

	/** {@code outside/secret.txt}, which no operation inside the other folders may read or delete. */
	private Path outsideFolder() throws IOException {
		Path outside = temp.resolve("outside");
		Files.createDirectories(outside);
		Files.writeString(outside.resolve("secret.txt"), "keep me");
		return outside;
	}

	/** {@code from/own.json}, and {@code from/linked} leading to {@link #outsideFolder}. */
	private Path sourceWithLinkedFolder(LinkMaker makeLink) throws Exception {
		Path outside = outsideFolder();
		Path from = temp.resolve("from");
		Files.createDirectories(from);
		Files.writeString(from.resolve("own.json"), "{}");
		makeLink.make(from.resolve("linked"), outside);
		return from;
	}

	@FunctionalInterface
	private interface LinkMaker {
		Path make(Path link, Path target) throws Exception;
	}
}
