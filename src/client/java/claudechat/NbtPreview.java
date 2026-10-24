package claudechat;

import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * NBT written as SNBT up to a number of characters, for block entity data from schematics and saved worlds. Those tags
 * come from files anyone can make, so a preview never copies the tag or writes it whole before cutting it short: it
 * walks the tag and stops once the text is full. Every tag it visits adds at least one character, so the work is
 * bounded by the limit, not by the tag. The text follows {@link Tag#toString()}'s form; a compound's keys are sorted
 * when it has few enough of them to sort cheaply.
 */
final class NbtPreview {
	/** What block entity data shows in a tool result. */
	static final int PREVIEW_CHARS = 400;

	/** How much of a block entity a data search reads: plenty for any real block's data. */
	static final int SEARCH_CHARS = 256 * 1024;

	/** Below the reader's own nesting limit; deeper tags are shown as "…". */
	private static final int MAX_DEPTH = 64;

	/** Compounds with more keys are written in their own order rather than sorted. */
	private static final int MAX_SORTED_KEYS = 256;

	private static final Pattern BARE_KEY = Pattern.compile("[A-Za-z0-9._+-]+");

	/** Thrown, without a stack trace, to stop the walk once the text is full. */
	private static final class Full extends RuntimeException {
		Full() {
			super(null, null, false, false);
		}
	}

	private final StringBuilder out = new StringBuilder();
	private final int limit;

	private NbtPreview(int limit) {
		this.limit = limit;
	}

	/**
	 * {@code tag} as SNBT without its top-level keys in {@code omit}, at most {@code maxChars} characters; a preview
	 * cut short ends in "…". A tag with nothing left is "{}".
	 */
	static String of(CompoundTag tag, Set<String> omit, int maxChars) {
		NbtPreview preview = new NbtPreview(maxChars);
		try {
			preview.compound(tag, omit, 0);
			return preview.out.toString();
		} catch (Full full) {
			return preview.out + "…";
		}
	}

	/** Whether the first {@code maxChars} characters of {@code tag}'s SNBT contain {@code text}, ignoring case. */
	static boolean contains(CompoundTag tag, String text, int maxChars) {
		NbtPreview preview = new NbtPreview(maxChars);
		try {
			preview.compound(tag, Set.of(), 0);
		} catch (Full full) {
			// Search what was written.
		}
		return preview.out.toString().toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT));
	}

	private void tag(Tag tag, int depth) {
		switch (tag) {
			case CompoundTag compound -> compound(compound, Set.of(), depth);
			case ListTag list -> {
				if (depth >= MAX_DEPTH) {
					append("[…]");
					return;
				}
				append("[");
				for (int i = 0; i < list.size(); i++) {
					if (i > 0) {
						append(",");
					}
					tag(list.get(i), depth + 1);
				}
				append("]");
			}
			case ByteArrayTag bytes -> {
				append("[B;");
				byte[] values = bytes.getAsByteArray();
				for (int i = 0; i < values.length; i++) {
					append((i > 0 ? "," : "") + values[i] + "B");
				}
				append("]");
			}
			case IntArrayTag ints -> {
				append("[I;");
				int[] values = ints.getAsIntArray();
				for (int i = 0; i < values.length; i++) {
					append((i > 0 ? "," : "") + values[i]);
				}
				append("]");
			}
			case LongArrayTag longs -> {
				append("[L;");
				long[] values = longs.getAsLongArray();
				for (int i = 0; i < values.length; i++) {
					append((i > 0 ? "," : "") + values[i] + "L");
				}
				append("]");
			}
			// Numbers and strings: a string tag holds at most 64 KiB, so writing one whole is bounded.
			default -> append(tag.toString());
		}
	}

	private void compound(CompoundTag compound, Set<String> omit, int depth) {
		if (depth >= MAX_DEPTH) {
			append("{…}");
			return;
		}
		Collection<String> keys = compound.keySet();
		if (keys.size() <= MAX_SORTED_KEYS) {
			List<String> sorted = new ArrayList<>(keys);
			sorted.sort(null);
			keys = sorted;
		}
		append("{");
		boolean first = true;
		for (String key : keys) {
			if (omit.contains(key)) {
				continue;
			}
			Tag value = compound.get(key);
			if (value == null) {
				continue;
			}
			append((first ? "" : ",") + key(key) + ":");
			first = false;
			tag(value, depth + 1);
		}
		append("}");
	}

	private static String key(String key) {
		return BARE_KEY.matcher(key).matches() ? key : "\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private void append(String text) {
		int room = limit - out.length();
		if (text.length() > room) {
			out.append(text, 0, Math.max(room, 0));
			throw new Full();
		}
		out.append(text);
	}
}
