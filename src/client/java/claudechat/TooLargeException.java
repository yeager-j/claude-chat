package claudechat;

import java.io.IOException;

/**
 * An imported file (a schematic, a structure template, a data pack) is over one of the limits on what reading it may
 * cost. The message is a clause naming the limit ("it has 70 palettes (the limit is 64)"), for the caller to put after
 * the file's name. Distinct from other read errors so that a caller can retry the file with more room, as the
 * template index does.
 */
public class TooLargeException extends IOException {
	public TooLargeException(String message) {
		super(message);
	}

	/** @throws TooLargeException if {@code count} is over {@code limit}; {@code what} names the counted things ("palettes") */
	static void requireAtMost(long count, long limit, String what) throws TooLargeException {
		if (count > limit) {
			throw new TooLargeException("it has " + count + " " + what + " (the limit is " + limit + ")");
		}
	}
}
