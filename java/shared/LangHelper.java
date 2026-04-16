package shared;

import java.util.Map;

public class LangHelper {

	// ISO 639-1 → ISO 639-2 mapping
	private static final Map<String, String> ISO2_TO_ISO3 = Map.ofEntries(Map.entry("pt", "POR"),
			Map.entry("en", "ENG"), Map.entry("es", "SPA"), Map.entry("fr", "FRA"), Map.entry("de", "DEU"),
			Map.entry("it", "ITA"), Map.entry("nl", "NLD"), Map.entry("ru", "RUS"), Map.entry("zh", "ZHO"),
			Map.entry("ja", "JPN"), Map.entry("ar", "ARA"), Map.entry("pl", "POL"), Map.entry("sv", "SWE"),
			Map.entry("da", "DAN"), Map.entry("fi", "FIN"), Map.entry("nb", "NOB"), Map.entry("cs", "CES"),
			Map.entry("hu", "HUN"), Map.entry("ro", "RON"), Map.entry("tr", "TUR"));

	public static boolean validIso2(String lang) {
		return lang == null ? false : ISO2_TO_ISO3.containsKey(lang);
	}

	public static boolean validIso3(String lang) {
		return lang == null ? false : ISO2_TO_ISO3.containsValue(lang);
	}

	public static String langIso2toIso3(String lang) {
		if (lang == null)
			return "ENG";
		if (lang.length() == 2) {
			String mapped = ISO2_TO_ISO3.get(lang.toLowerCase());
			return mapped != null ? mapped : lang.toUpperCase();
		}
		return lang.toUpperCase();
	}

}
