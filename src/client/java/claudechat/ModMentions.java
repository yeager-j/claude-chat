package claudechat;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides whether a question names a mod, so its code can be decompiled before Claude answers. A false match costs a
 * decompile of up to a minute, so the words that spell the name must include one that isn't common: a mod named only
 * with everyday Minecraft words ("Create", "Better Combat") could be ordinary speech ("how do I create a portal"),
 * and is left to its in-world namespace or Claude's decompile_mod call instead.
 */
final class ModMentions {
	private static final Pattern NOT_WORD = Pattern.compile("[^a-z0-9]+");
	/** Shorter ids ("ae2", "rei") are too often part of something else. */
	private static final int MIN_NAME_LENGTH = 4;

	private final Set<String> commonWords;

	/** {@code commonWords}: lowercase words that alone never identify a mod, such as those in Minecraft's own text. */
	ModMentions(Set<String> commonWords) {
		this.commonWords = commonWords;
	}

	/** Lowercase runs of letters and digits: "Xaero's Minimap" is [xaero, s, minimap]. */
	static List<String> words(String text) {
		String spaced = NOT_WORD.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
		return spaced.isEmpty() ? List.of() : Arrays.asList(spaced.split(" "));
	}

	/**
	 * True when consecutive whole words of the question spell {@code name} (a mod id or display name), with or without
	 * spaces: "vein miner", "veinminer" and "Vein-Miner" all spell Vein Miner. At least one of those words must not be
	 * common, and names shorter than four letters never match.
	 */
	boolean mentions(List<String> questionWords, String name) {
		String spelled = String.join("", words(name));

		if (spelled.length() < MIN_NAME_LENGTH) {
			return false;
		}

		for (int start = 0; start < questionWords.size(); start++) {
			int end = spellingEnd(questionWords, start, spelled);

			if (end > start && hasUncommonWord(questionWords.subList(start, end))) {
				return true;
			}
		}

		return false;
	}

	/** The end of the run of words from {@code start} that spells {@code spelled} exactly, or -1. */
	private static int spellingEnd(List<String> words, int start, String spelled) {
		StringBuilder run = new StringBuilder();

		for (int end = start; end < words.size(); end++) {
			run.append(words.get(end));

			if (!spelled.startsWith(run.toString())) {
				return -1;
			}

			if (run.length() == spelled.length()) {
				return end + 1;
			}
		}

		return -1;
	}

	private boolean hasUncommonWord(List<String> words) {
		return words.stream().anyMatch(word -> !commonWords.contains(word));
	}
}
