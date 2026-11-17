package shared;

import static shared.TextHelper.wordKind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import shared.TextHelper.WordKind;
import shared.XdxfHelper.ParsedEntry;

/**
 * How duplicate headwords are handled ({@code -m / --merge-defs}): each mode
 * gives the key that decides when two headwords are the same, whether
 * different definitions are joined, and which headword a joined entry
 * keeps.<br>
 * Common to all modes: of the entries with the same headword and the same
 * definition (same bytes), only the first is kept ({@link #dropRepeated}).<br>
 * ALWAYS names a joined entry after its most common spelling
 * ({@link TextHelper#wordKind}): "Fausta" X + "fausta" Y → "fausta".
 */
public enum MergeMode {

	//     word key (same key = same headword)  join different  kept headword (first, second)
	ALWAYS(TextHelper::lower,                    true,           MergeMode::moreCommon),
	EXACT (UnaryOperator.identity(),             true,           (a, b) -> a),
	NEVER (UnaryOperator.identity(),             false,          (a, b) -> a);

	/** What to do with an entry, given the one before it in the sorted list. */
	public enum Action { DIFFERENT, DROP, JOIN, KEEP_BOTH }

	private final UnaryOperator<String> wordKey;
	private final boolean joinsDifferent;
	private final BinaryOperator<String> keptWord;

	MergeMode(UnaryOperator<String> wordKey, boolean joinsDifferent, BinaryOperator<String> keptWord) {
		this.wordKey = wordKey;
		this.joinsDifferent = joinsDifferent;
		this.keptWord = keptWord;
	}

	/**
	 * Drops repeated definitions from {@code entries} (sorted), before merging:
	 * of the entries with the same headword (same word key) and the same
	 * definition (same bytes), only the first is kept, wherever the others are.
	 * The order of the others is kept.<br>
	 * The kept entry takes the headword a join would give ("Selá" X + "selá" X →
	 * "selá"), except that an acronym keeps its capitals ("FUNASA" X + "funasa" X
	 * → "FUNASA"): the same definition means the same acronym.
	 *
	 * @return how many entries were dropped
	 */
	public int dropRepeated(List<ParsedEntry> entries) {
		record Seen(String key, byte[] definition) {
			@Override
			public boolean equals(Object o) {
				return o instanceof Seen other && key.equals(other.key) && Arrays.equals(definition, other.definition);
			}

			@Override
			public int hashCode() {
				return 31 * key.hashCode() + Arrays.hashCode(definition);
			}
		}
		var keptAt = new HashMap<Seen, Integer>();
		var kept = new ArrayList<ParsedEntry>(entries.size());
		for (ParsedEntry e : entries) {
			Integer at = keptAt.putIfAbsent(new Seen(wordKey.apply(e.word()), e.definition()), kept.size());
			if (at == null) {
				kept.add(e);
			} else { // dropped, but its headword counts
				ParsedEntry first = kept.get(at);
				kept.set(at, new ParsedEntry(keptWordOfRepeat(first.word(), e.word()), first.definition()));
			}
		}
		int dropped = entries.size() - kept.size();
		entries.clear();
		entries.addAll(kept);
		return dropped;
	}

	/**
	 * The more common spelling of the two: by {@link TextHelper#wordKind}
	 * (COMMON, NAME, ACRONYM), then by fewer capitals ("aba" before "abA",
	 * "Sexta-feira Santa" before "Sexta-Feira Santa"); {@code first} on a tie.
	 */
	private static String moreCommon(String first, String second) {
		int byKind = wordKind(second).compareTo(wordKind(first));
		if (byKind != 0) {
			return byKind < 0 ? second : first;
		}
		return capitals(second) < capitals(first) ? second : first;
	}

	private static long capitals(String word) {
		return word.codePoints().filter(Character::isUpperCase).count();
	}

	/** The headword kept when {@code second} repeats {@code first}'s definition. */
	private String keptWordOfRepeat(String first, String second) {
		if (wordKind(first) == WordKind.ACRONYM) {
			return first;
		}
		if (wordKind(second) == WordKind.ACRONYM) {
			return second;
		}
		return keptWord(first, second);
	}

	/** What to do with {@code nextWord}/{@code nextDef}, which follows {@code prevWord}/{@code prevDef}. */
	public Action decide(String prevWord, byte[] prevDef, String nextWord, byte[] nextDef) {
		if (!wordKey.apply(prevWord).equals(wordKey.apply(nextWord))) {
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
