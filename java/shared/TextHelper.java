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
}
