package claudechat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which of a world's data packs {@link KnowledgeBase} copies for Claude: only those really in the world's folder. */
class WorldPackLinksTest {
	@TempDir
	Path temp;

	@Test
	void folderAndZipPacksInTheWorldAreRead() throws IOException {
		Path world = temp.resolve("world");
		Path datapacks = Files.createDirectories(world.resolve("datapacks"));
		Path folder = Files.createDirectories(datapacks.resolve("pack/data")).getParent();
		Path zip = Files.writeString(datapacks.resolve("pack.zip"), "zip");

		assertTrue(KnowledgeBase.isUnlinkedPack(world, folder));
		assertTrue(KnowledgeBase.isUnlinkedPack(world, zip));
		assertFalse(KnowledgeBase.isUnlinkedPack(world, datapacks.resolve("missing")));
	}

	@Test
	void aJunctionedPackIsRefused() throws Exception {
		Path world = temp.resolve("world");
		Path datapacks = Files.createDirectories(world.resolve("datapacks"));

		assertFalse(KnowledgeBase.isUnlinkedPack(world, FileLinks.junction(datapacks.resolve("pack"), outsidePack())));
	}

	@Test
	void packsInAJunctionedDatapacksFolderAreRefused() throws Exception {
		Path world = Files.createDirectories(temp.resolve("world"));
		outsidePack();
		Path datapacks = FileLinks.junction(world.resolve("datapacks"), temp.resolve("elsewhere"));

		assertFalse(KnowledgeBase.isUnlinkedPack(world, datapacks.resolve("outside")));
	}

	@Test
	void aWorldReachedThroughAJunctionKeepsItsPacks() throws Exception {
		Path realWorld = Files.createDirectories(temp.resolve("real-world/datapacks/pack/data")).getParent().getParent().getParent();
		Path world = FileLinks.junction(temp.resolve("saves-link"), realWorld);

		assertTrue(KnowledgeBase.isUnlinkedPack(world, world.resolve("datapacks/pack")));
	}

	@Test
	void symbolicallyLinkedPacksAreRefused() throws Exception {
		Path world = temp.resolve("world");
		Path datapacks = Files.createDirectories(world.resolve("datapacks"));
		Path zip = Files.writeString(temp.resolve("outside.zip"), "zip");

		assertFalse(KnowledgeBase.isUnlinkedPack(world, FileLinks.symbolicLink(datapacks.resolve("pack"), outsidePack())));
		assertFalse(KnowledgeBase.isUnlinkedPack(world, FileLinks.symbolicLink(datapacks.resolve("pack.zip"), zip)));
	}

	/** {@code elsewhere/outside}: a pack folder outside the world, with a data file. */
	private Path outsidePack() throws IOException {
		Path pack = temp.resolve("elsewhere/outside");
		Files.createDirectories(pack.resolve("data/secret/loot_table"));
		Files.writeString(pack.resolve("data/secret/loot_table/chest.json"), "{}");
		return pack;
	}
}
