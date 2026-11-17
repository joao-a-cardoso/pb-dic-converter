package shared;

import static shared.TextHelper.lower;
import static shared.TextHelper.upper;

import java.util.Locale;
import java.util.Set;

/**
 * Language codes: two-letter ISO 639-1 codes (e.g. pt, en), accepted in any
 * case (language codes are case-insensitive) and used in lower case.
 */
public final class LangHelper {

	private LangHelper() {
		// static members only: not instantiable (Effective Java, Item 4)
	}

	/** All ISO 639-1 codes known to the JDK (lower case). */
	private static final Set<String> ISO2 = Set.of(Locale.getISOLanguages());

	/** Trims and lower-cases a language code; {@code null} stays {@code null}. */
	public static String normalize(String lang) {
		return lang == null ? null : lower(lang.trim());
	}

	/** True when {@code lang} is a two-letter ISO 639-1 code, in any case. */
	public static boolean validIso2(String lang) {
		String code = normalize(lang);
		return code != null && code.length() == 2 && ISO2.contains(code);
	}

	/**
	 * ISO 639-1 → ISO 639-2 (terminology) code in upper case, as used by XDXF,
	 * e.g. pt → POR, de → DEU. {@code null} → ENG.
	 */
	public static String langIso2toIso3(String lang) {
		String code = normalize(lang);
		if (code == null)
			return "ENG";
		return upper(Locale.of(code).getISO3Language());
	}

}
