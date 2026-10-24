package claudechat;

import claudechat.BuiltFolder.Content;
import claudechat.BuiltFolder.Progress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltFolderTest {
	@TempDir
	Path root;

	/** Outside {@link #root}: what a link inside it may lead to. */
	@TempDir
	Path elsewhere;

	/** Builds {@code folder} with a data file, plus a source file when {@code target} is CODE. */
	private static void build(BuiltFolder folder, Content target) {
		folder.locked(() -> {
			try {
				folder.build(target, (out, work) -> {
					Files.createDirectories(out.resolve(BuiltFolder.DATA));
					Files.writeString(out.resolve(BuiltFolder.DATA).resolve("recipe.json"), "{}");
					Files.writeString(work.resolve("scratch.txt"), "temporary");

					if (target == Content.CODE) {
						Files.createDirectories(out.resolve(BuiltFolder.SRC));
						Files.writeString(out.resolve(BuiltFolder.SRC).resolve("A.java"), "class A {}");
					}
				});
			} catch (IOException | InterruptedException e) {
				throw new AssertionError(e);
			}
		});
	}

	@Test
	void aBuildIsPublishedAndRememberedForTheSameKey() {
		BuiltFolder folder = new BuiltFolder(root, "mods/foo/1.0", "foo@1.0");
		assertEquals(Content.NONE, folder.content());

		build(folder, Content.CODE);

		assertEquals(Content.CODE, folder.content());
		assertEquals(Progress.IDLE, folder.progress());
		assertTrue(Files.isRegularFile(folder.dir().resolve("src/A.java")));
		assertFalse(Files.exists(root.resolve("mods/foo/1.0.work")));
		assertFalse(Files.exists(root.resolve("mods/foo/1.0.partial")));
		assertEquals(Content.CODE, new BuiltFolder(root, "mods/foo/1.0", "foo@1.0").content());
	}

	@Test
	void aDifferentKeyNeedsARebuild() {
		build(new BuiltFolder(root, "mods/foo/1.0", "foo@1.0,nested@1"), Content.DATA);

		assertEquals(Content.NONE, new BuiltFolder(root, "mods/foo/1.0", "foo@1.0,nested@2").content());
	}

	@Test
	void aRebuildReplacesTheOldFolder() throws IOException {
		BuiltFolder folder = new BuiltFolder(root, "mods/foo/1.0", "foo@1.0");
		build(folder, Content.CODE);
		folder.discardUnlessBuilding();

		build(folder, Content.DATA);

		assertEquals(Content.DATA, folder.content());
		assertFalse(Files.exists(folder.dir().resolve(BuiltFolder.SRC)));
		assertFalse(Files.exists(root.resolve("mods/foo/1.0.old")));
		assertEquals("{}", Files.readString(folder.data().resolve("recipe.json")));
	}

	@Test
	void aFailedBuildKeepsThePublishedFolder() {
		BuiltFolder folder = new BuiltFolder(root, "pack", "v1");
		build(folder, Content.DATA);

		folder.locked(() -> assertThrows(IOException.class, () -> folder.build(Content.CODE, (out, work) -> {
			throw new IOException("decompiler exited with code 1");
		})));

		assertEquals(Content.DATA, folder.content());
		assertEquals(Progress.FAILED, folder.progress());
		assertEquals("decompiler exited with code 1", folder.failure());
		assertTrue(Files.isRegularFile(folder.data().resolve("recipe.json")));
		assertTrue(Files.isDirectory(root.resolve("pack.work")), "the work folder, with any log, stays after a failure");
	}

	@Test
	void aFailedBuildDeletesWhatItCopied() {
		BuiltFolder folder = new BuiltFolder(root, "pack", "v1");

		folder.locked(() -> assertThrows(TooLargeException.class, () -> folder.build(Content.DATA, (out, work) -> {
			Files.writeString(out.resolve("copied.json"), "{}");
			throw new TooLargeException("over 512 MiB uncompressed in the pack (the limit)");
		})));

		assertEquals(Progress.FAILED, folder.progress());
		assertFalse(Files.exists(root.resolve("pack.partial")));
	}

	@Test
	void buildingWithoutTheLockIsRefused() {
		BuiltFolder folder = new BuiltFolder(root, "pack", "v1");

		assertThrows(IllegalStateException.class, () -> folder.build(Content.DATA, (out, work) -> { }));
	}

	@Test
	void droppingCodeKeepsData() {
		BuiltFolder folder = new BuiltFolder(root, "mods/foo/1.0", "foo@1.0");
		build(folder, Content.CODE);

		folder.locked(() -> {
			try {
				folder.dropCode();
			} catch (IOException e) {
				throw new AssertionError(e);
			}
		});

		assertEquals(Content.DATA, folder.content());
		assertFalse(Files.exists(folder.dir().resolve(BuiltFolder.SRC)));
		assertEquals(Content.DATA, new BuiltFolder(root, "mods/foo/1.0", "foo@1.0").content());
	}

	@Test
	void queueingTwiceQueuesOnce() {
		BuiltFolder folder = new BuiltFolder(root, "pack", "v1");

		assertTrue(folder.queue());
		assertFalse(folder.queue());

		folder.settle();
		assertEquals(Progress.IDLE, folder.progress());
	}

	@Test
	void sweepRemovesOnlyUnwantedBuiltFolders() throws IOException {
		BuiltFolder current = new BuiltFolder(root, "mods/foo/2.0", "foo@2.0");
		BuiltFolder oldVersion = new BuiltFolder(root, "mods/foo/1.0", "foo@1.0");
		BuiltFolder uninstalled = new BuiltFolder(root, "mods/bar/1.0", "bar@1.0");
		build(current, Content.DATA);
		build(oldVersion, Content.DATA);
		build(uninstalled, Content.DATA);

		Path legacy = root.resolve("mods/foo-0.9");
		Files.createDirectories(legacy);
		Files.writeString(legacy.resolve(".complete"), "2026-10-16T00:18:50Z");
		Path inProgress = root.resolve("mods/baz/1.0.partial");
		Files.createDirectories(inProgress);
		Path inProgressWork = root.resolve("mods/baz/1.0.work");
		Files.createDirectories(inProgressWork);
		Path leftoverOld = root.resolve("mods/foo/2.0.old");
		Files.createDirectories(leftoverOld);
		Path notOurs = root.resolve("notes/deep");
		Files.createDirectories(notOurs);

		BuiltFolder.removeUnwanted(root, Set.of(current.dir(), root.resolve("mods/baz/1.0")));

		assertTrue(Files.isDirectory(current.dir()));
		assertFalse(Files.exists(oldVersion.dir()));
		assertFalse(Files.exists(root.resolve("mods/bar")));
		assertFalse(Files.exists(legacy));
		assertTrue(Files.isDirectory(inProgress));
		assertTrue(Files.isDirectory(inProgressWork));
		assertFalse(Files.exists(leftoverOld));
		assertTrue(Files.isDirectory(notOurs));
	}

	@Test
	void sweepNeitherEntersNorDeletesJunctions() throws Exception {
		Path outside = Files.createDirectories(elsewhere.resolve("outside"));
		Path markedFolder = Files.createDirectories(outside.resolve("1.0"));
		Files.writeString(markedFolder.resolve(".complete"), "not ours to delete");
		Path oldLooking = Files.createDirectories(outside.resolve("2.0.old"));
		Files.createDirectories(root.resolve("mods"));
		Path link = FileLinks.junction(root.resolve("mods/foo"), outside);
		Path linkedBuilt = FileLinks.junction(root.resolve("mods/bar.old"), outside);

		BuiltFolder.removeUnwanted(root, Set.of());

		assertTrue(Files.isRegularFile(markedFolder.resolve(".complete")));
		assertTrue(Files.isDirectory(oldLooking));
		assertTrue(Files.exists(link, LinkOption.NOFOLLOW_LINKS));
		assertTrue(Files.exists(linkedBuilt, LinkOption.NOFOLLOW_LINKS));
	}

	@Test
	void sweepNeitherEntersNorDeletesSymbolicLinks() throws Exception {
		Path outside = Files.createDirectories(elsewhere.resolve("outside"));
		Path markedFolder = Files.createDirectories(outside.resolve("1.0"));
		Files.writeString(markedFolder.resolve(".complete"), "not ours to delete");
		Files.createDirectories(root.resolve("mods"));
		Path link = FileLinks.symbolicLink(root.resolve("mods/foo"), outside);

		BuiltFolder.removeUnwanted(root, Set.of());

		assertTrue(Files.isRegularFile(markedFolder.resolve(".complete")));
		assertTrue(Files.exists(link, LinkOption.NOFOLLOW_LINKS));
	}

	@Test
	void aBuildALinkLeadsOutsideTheRootIsRefusedBeforeWriting() throws Exception {
		Path outside = Files.createDirectories(elsewhere.resolve("outside"));
		FileLinks.junction(root.resolve("mods"), outside);
		BuiltFolder folder = new BuiltFolder(root, "mods/foo/1.0", "foo@1.0");

		folder.locked(() -> assertThrows(IOException.class, () -> folder.build(Content.DATA, (out, work) -> Files.writeString(out.resolve("x"), "x"))));

		assertEquals(Progress.FAILED, folder.progress());
		assertTrue(folder.failure().contains("Refusing"), folder.failure());
		assertTrue(KnowledgeFiles.isEmptyDirectory(outside));
	}
}
