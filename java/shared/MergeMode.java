package shared;

import static shared.TextHelper.lower;

import java.util.Arrays;
import java.util.function.BiPredicate;
import java.util.function.BinaryOperator;

/**
 * How duplicate headwords are handled ({@code -m / --merge-defs}): each mode
 * gives when two headwords are the same, whether different definitions are
 * joined, and which headword a joined entry keeps.<br>
 * Common to all modes: a definition identical (same bytes) to the previous
 * one of the same headword is dropped.
 */
public enum MergeMode {

	//     same headword             join different  kept headword (first, second)
	ALWAYS(String::equalsIgnoreCase, true,           (a, b) -> a.equals(lower(a)) ? a : b),
	EXACT (String::equals,           true,           (a, b) -> a),
	NEVER (String::equals,           false,          (a, b) -> a);

	/** What to do with an entry, given the one before it in the sorted list. */
	public enum Action { DIFFERENT, DROP, JOIN, KEEP_BOTH }

	private final BiPredicate<String, String> sameWord;
	private final boolean joinsDifferent;
	private final BinaryOperator<String> keptWord;

	MergeMode(BiPredicate<String, String> sameWord, boolean joinsDifferent, BinaryOperator<String> keptWord) {
		this.sameWord = sameWord;
		this.joinsDifferent = joinsDifferent;
		this.keptWord = keptWord;
	}

	/** What to do with {@code nextWord}/{@code nextDef}, which follows {@code prevWord}/{@code prevDef}. */
	public Action decide(String prevWord, byte[] prevDef, String nextWord, byte[] nextDef) {
		if (!sameWord.test(prevWord, nextWord)) {
			return Action.DIFFERENT;
		}
		if (Arrays.equals(prevDef, nextDef)) {
			return Action.DROP;
		}
		return joinsDifferent ? Action.JOIN : Action.KEEP_BOTH;
	}

	/** The headword of the entry joined from {@code first} and {@code second}. */
	public String keptWord(String first, String second) {
		return keptWord.apply(first, second);
	}

	/** The mode named {@code value}, ignoring case; otherwise a usage error. */
	public static MergeMode parse(String value) throws CliException {
		for (MergeMode mode : values()) {
			if (mode.name().equalsIgnoreCase(value)) {
				return mode;
			}
		}
		throw CliException.error("Error: -m / --merge-defs must be ALWAYS, EXACT or NEVER, got: %s", value);
	}
}
