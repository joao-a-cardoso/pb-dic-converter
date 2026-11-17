package shared;

import static shared.TextHelper.capitalize;
import static shared.TextHelper.lower;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Immutable label translations (english term → translation), read from a
 * {@code .properties} file ({@code english-term=translation}). Lookups are
 * case-insensitive; a term without translation is returned unchanged.
 *
 * <p>
 * Created once by the tool and passed to the code that needs it, instead of
 * being global state.
 */
public final class TermTranslator {

	private final Map<String, String> translations; // key: term in lower case

	private TermTranslator(Map<String, String> translations) {
		this.translations = Map.copyOf(translations);
	}

	/** No translations: every term is returned unchanged. */
	public static TermTranslator none() {
		return new TermTranslator(Map.of());
	}

	/** Reads the translations from a UTF-8 {@code .properties} file. */
	public static TermTranslator load(Path file) throws IOException {
		var props = new Properties();
		try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			props.load(reader);
		}
		var map = new LinkedHashMap<String, String>();
		for (String key : props.stringPropertyNames()) {
			map.put(lower(key.trim()), props.getProperty(key).trim());
		}
		return new TermTranslator(map);
	}

	/** Number of terms. */
	public int size() {
		return translations.size();
	}

	/** True when {@code term} has a translation. */
	public boolean has(String term) {
		return term != null && translations.containsKey(lower(term.trim()));
	}

	/**
	 * The translation of {@code term}, or the (trimmed) term itself when there is
	 * none; "" for null or blank. Not escaped for XML.
	 */
	public String translate(Object term) {
		String str = term == null ? "" : term.toString().trim();
		if (str.isEmpty()) {
			return "";
		}
		return translations.getOrDefault(lower(str), str);
	}

	/** As {@link #translate}, with the first character in upper case. */
	public String translateCap(Object term) {
		return capitalize(translate(term));
	}
}
