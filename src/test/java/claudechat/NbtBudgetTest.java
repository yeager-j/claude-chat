package claudechat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading gzipped NBT within a budget: decompressed size, memory, nesting, and cancellation each stop a read. */
class NbtBudgetTest {
	private static final NbtBudget ONE_MIB = new NbtBudget(1 << 20, () -> false);

	@TempDir
	Path dir;

	@BeforeAll
	static void bootstrap() {
		GameBootstrap.init();
	}

	@Test
	void smallFileIsRead() throws IOException {
		CompoundTag tag = new CompoundTag();
		tag.putString("name", "hut");
		Path file = dir.resolve("small.nbt");
		SchematicFixtures.write(file, tag);

		assertEquals("hut", ONE_MIB.read(file).getStringOr("name", ""));
	}

	@Test
	void fullReadStopsAtTheMemoryEstimate() throws IOException {
		Path file = dir.resolve("bomb.schem");
		SchematicFixtures.writeZeroBomb(file, 2 << 20);

		TooLargeException e = assertThrows(TooLargeException.class, () -> ONE_MIB.read(file));

		assertTrue(e.getMessage().contains("more than 1 MiB of memory"), e.getMessage());
	}

	@Test
	void skippingReadStopsAtTheDecompressedSize() throws IOException {
		Path file = dir.resolve("bomb.schem");
		SchematicFixtures.writeZeroBomb(file, 2 << 20);
		// A field the file doesn't have, so the visitor skips the whole array looking for it.
		CollectFields missing = new CollectFields(new FieldSelector(StringTag.TYPE, "Missing"));

		TooLargeException e = assertThrows(TooLargeException.class, () -> ONE_MIB.parse(file, missing));

		assertTrue(e.getMessage().contains("decompresses to more than 1 MiB"), e.getMessage());
	}

	@Test
	void skipNeverDecompressesPastTheBudget() throws IOException {
		Path file = dir.resolve("bomb.schem");
		SchematicFixtures.writeZeroBomb(file, 64 << 20);
		CollectFields missing = new CollectFields(new FieldSelector(StringTag.TYPE, "Missing"));
		AtomicLong used = new AtomicLong();

		assertThrows(TooLargeException.class, () -> ONE_MIB.parse(file, missing, used));

		// The array asks for one 64 MiB skip; it is read a chunk at a time and stops one byte past the budget.
		assertTrue(used.get() > 1 << 20 && used.get() <= (1 << 20) + 1, "decompressed " + used.get() + " bytes");
	}

	@Test
	void cancellationStopsALongSkip() throws IOException {
		Path file = dir.resolve("bomb.schem");
		SchematicFixtures.writeZeroBomb(file, 64 << 20);
		CollectFields missing = new CollectFields(new FieldSelector(StringTag.TYPE, "Missing"));
		AtomicInteger checks = new AtomicInteger();
		NbtBudget cancelledSoon = new NbtBudget(128 << 20, () -> checks.incrementAndGet() > 20);
		AtomicLong used = new AtomicLong();

		assertThrows(CancellationException.class, () -> cancelledSoon.parse(file, missing, used));

		assertTrue(used.get() < 2 << 20, "decompressed " + used.get() + " bytes");
	}

	@Test
	void deepNestingIsRefused() throws IOException {
		CompoundTag root = new CompoundTag();
		CompoundTag current = root;
		for (int i = 0; i < NbtBudget.MAX_DEPTH + 10; i++) {
			CompoundTag child = new CompoundTag();
			current.put("c", child);
			current = child;
		}

		Path file = dir.resolve("deep.nbt");
		SchematicFixtures.write(file, root);

		TooLargeException e = assertThrows(TooLargeException.class, () -> ONE_MIB.read(file));

		assertTrue(e.getMessage().contains("nested more than 512 levels"), e.getMessage());
	}

	@Test
	void cancellationStopsTheRead() throws IOException {
		Path file = dir.resolve("small.nbt");
		SchematicFixtures.write(file, new CompoundTag());
		NbtBudget cancelled = new NbtBudget(1 << 20, () -> true);

		assertThrows(CancellationException.class, () -> cancelled.read(file));
	}

	@Test
	void notGzipIsAnIoErrorButNotTooLarge() throws IOException {
		Path file = dir.resolve("text.nbt");
		Files.writeString(file, "not nbt");

		IOException e = assertThrows(IOException.class, () -> ONE_MIB.read(file));

		assertFalse(e instanceof TooLargeException);
	}
}
