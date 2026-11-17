package shared;

import java.util.Locale;

/**
 * Locale-independent text helpers: the same result on every machine.
 * {@code String.toLowerCase()} / {@code toUpperCase()} without a locale use the
 * machine's default locale, where e.g. Turkish turns "I" into "ı" and "i" into
 * "İ" (the "Turkish I" problem). Meant for static import:
 *
 * <pre>
 * import static shared.TextHelper.lower;
 * </pre>
 */
public final class TextHelper {

	private TextHelper() {
	}

	/** Lower case, locale-independent; null stays null. */
	public static String lower(String s) {
		return s == null ? null : s.toLowerCase(Locale.ROOT);
	}

	/** Upper case, locale-independent; null stays null. */
	public static String upper(String s) {
		return s == null ? null : s.toUpperCase(Locale.ROOT);
	}

	/**
	 * First character in upper case, the rest unchanged ("informática" →
	 * "Informática"); null and "" are returned unchanged. Works on code points, so
	 * a first character outside the basic plane is not split.
	 */
	public static String capitalize(String s) {
		if (s == null || s.isEmpty()) {
			return s;
		}
		int first = s.codePointAt(0);
		return new StringBuilder(s.length())
				.appendCodePoint(Character.toUpperCase(first))
				.append(s, Character.charCount(first), s.length())
				.toString();
	}

	/**
	 * The kind of a word, judged by its spelling only (not its meaning), from the
	 * most common to the least; see {@link #wordKind}.
	 */
	public enum WordKind {
		/** Does not start with a capital: "casa", "selá", "iPhone". */
		COMMON,
		/** Starts with a capital: "Fausta", "Selá", "A", "PhD". */
		NAME,
		/** Two or more letters, none lowercase: "FUNASA", "A.K.A.". */
		ACRONYM
	}

	/**
	 * The kind of {@code word} by its spelling: {@link WordKind#ACRONYM} if it has
	 * two or more letters and none is lowercase, else {@link WordKind#NAME} if its
	 * first letter is a capital, else {@link WordKind#COMMON} (also for a word
	 * without letters).
	 */
	public static WordKind wordKind(String word) {
		if (word.codePoints().filter(Character::isLetter).count() >= 2
				&& word.codePoints().noneMatch(Character::isLowerCase)) {
			return WordKind.ACRONYM;
		}
		int first = word.codePoints().filter(Character::isLetter).findFirst().orElse('a');
		return Character.isUpperCase(first) ? WordKind.NAME : WordKind.COMMON;
	}
}
