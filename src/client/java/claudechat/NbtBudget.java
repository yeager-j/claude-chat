package claudechat;

import net.minecraft.ReportedException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtAccounterException;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StreamTagVisitor;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPInputStream;

/**
 * Limits for reading one gzipped NBT file that doesn't come from the game's own saves: a schematic, or a structure
 * template from a world or a pack. The game reads these with no memory limit, but a few kilobytes of gzip can expand
 * to gigabytes, so here a file over budget fails with a {@link TooLargeException} instead of exhausting memory or time.
 * <p>
 * {@code maxBytes} bounds two things: the bytes the file decompresses to, counted as they are read, and the game's
 * estimate of the memory the parsed tags take ({@link NbtAccounter}). The estimate is never below the decompressed
 * size, so it is the bound that applies to a full read; the decompressed size bounds the data a partial read skips.
 * Reading also stops, with a {@link CancellationException}, once {@code cancelled} says so.
 */
record NbtBudget(long maxBytes, BooleanSupplier cancelled) {
	/**
	 * 256 MiB. The largest structure template among 7,218 in a heavily modded game (Terralith's spire) takes 59 MB once
	 * read; a Litematica schematic of {@link BlockVolume#MAX_POSITIONS} positions stores at most 120 MiB of block data.
	 */
	static final long FILE_BYTES = 256L << 20;

	/** The game's own nesting limit for NBT; it keeps the recursive parser from overflowing the stack. */
	static final int MAX_DEPTH = 512;

	/** A file read for a tool call: the full budget, stopping if the reading thread is interrupted. */
	static final NbtBudget FILE = new NbtBudget(FILE_BYTES, () -> Thread.currentThread().isInterrupted());

	/**
	 * Reads a whole file.
	 *
	 * @throws TooLargeException if it is over budget
	 * @throws CancellationException if cancelled while reading
	 */
	CompoundTag read(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			return read(in);
		}
	}

	/** Reads a whole gzipped stream; see {@link #read(Path)}. */
	CompoundTag read(InputStream compressed) throws IOException {
		Accounter accounter = new Accounter(maxBytes);

		try (DataInputStream in = decompressed(compressed)) {
			return NbtIo.read(in, accounter);
		} catch (RuntimeException e) {
			throw translated(e, accounter);
		}
	}

	/** Streams a file through {@code visitor}, which may skip what it doesn't need; see {@link #read(Path)}. */
	void parse(Path file, StreamTagVisitor visitor) throws IOException {
		Accounter accounter = new Accounter(maxBytes);

		try (InputStream compressed = Files.newInputStream(file); DataInputStream in = decompressed(compressed)) {
			NbtIo.parse(in, visitor, accounter);
		} catch (RuntimeException e) {
			throw translated(e, accounter);
		}
	}

	private DataInputStream decompressed(InputStream compressed) throws IOException {
		return new DataInputStream(new BufferedInputStream(new Limited(new GZIPInputStream(compressed))));
	}

	/**
	 * The game's parser reports a failed read as a runtime exception, wrapping the stream's own IOExceptions; this turns
	 * them back into IOExceptions, keeping a {@link TooLargeException} for a limit. A cancellation passes through.
	 */
	private IOException translated(RuntimeException e, Accounter accounter) {
		if (e instanceof CancellationException cancellation) {
			throw cancellation;
		}

		if (e instanceof NbtAccounterException) {
			return accounter.tooDeep
				? new TooLargeException("its NBT data is nested more than " + MAX_DEPTH + " levels deep (the limit)")
				: new TooLargeException("its NBT data needs more than " + mebibytes() + " MiB of memory once read (the limit)");
		}

		if (e instanceof ReportedException && e.getCause() instanceof IOException cause) {
			return cause;
		}

		return new IOException("Not valid NBT data: " + e.getMessage(), e);
	}

	private long mebibytes() {
		return maxBytes >> 20;
	}

	/** The game's accounter, remembering whether it refused for nesting: the parser unwinds the depth before the exception arrives. */
	private static final class Accounter extends NbtAccounter {
		private boolean tooDeep;

		Accounter(long quota) {
			super(quota, MAX_DEPTH);
		}

		@Override
		public void pushDepth() {
			tooDeep = getDepth() >= MAX_DEPTH;
			super.pushDepth();
		}
	}

	/** Counts the decompressed bytes as they are read or skipped, and checks for cancellation on every read. */
	private final class Limited extends FilterInputStream {
		private long count;

		Limited(InputStream in) {
			super(in);
		}

		@Override
		public int read() throws IOException {
			int b = super.read();
			counted(b < 0 ? 0 : 1);
			return b;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			int read = super.read(buffer, offset, length);
			counted(Math.max(read, 0));
			return read;
		}

		@Override
		public long skip(long n) throws IOException {
			long skipped = super.skip(n);
			counted(skipped);
			return skipped;
		}

		private void counted(long bytes) throws TooLargeException {
			if (cancelled.getAsBoolean()) {
				throw new CancellationException("Reading NBT data was cancelled");
			}

			count += bytes;

			if (count > maxBytes) {
				throw new TooLargeException("its NBT data decompresses to more than " + mebibytes() + " MiB (the limit)");
			}
		}
	}
}
