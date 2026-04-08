
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import shared.ArgsHelper;
import shared.XdxfHelper;
import shared.XdxfHelper.XdxfEntry;

/**
 * Converts a tabfile (TSV) dictionary to XDXF for use with
 * {@code xdxf-2-pbdic}.
 *
 * <br>
 * <b>Usage:</b>
 * 
 * <pre>
 *   ./tab-2-xdxf --in|-i         &lt;input.tsv|-&gt;
 *                --out|-o         &lt;output.xdxf|-&gt;
 *                --lang|-l        &lt;ISO 639-1 or 639-2&gt;
 *                --name|-n        &lt;dictionary name&gt;
 *                [--validate|-v]  true/false
 *                [--config|-c     &lt;config.properties&gt;]
 * </pre>
 *
 * <br>
 * <b>Examples:</b>
 * 
 * <pre>
 *   ./tab-2-xdxf -i data/out/dict-pt-pt.tsv -o data/out/dict-pt-pt.xdxf -l pt -n "Dicionário PT-PT"
 *   ./tab-2-xdxf -i - -o data/out/kaikki-pt.xdxf -l pt -n "Dicionário PT (Kaikki)"  # stdin → file
 *   ./tab-2-xdxf -i - -o - -l pt -n "Kaikki PT"                                     # stdin → stdout
 *   ./tab-2-xdxf -c dict.properties
 *   kaikki-2-tab -c dict.properties | tab-2-xdxf -c dict.properties | xdxf-2-pbdic -c dict.properties
 * </pre>
 *
 * <br>
 * <b>Stdin/stdout:</b> use {@code -i -} to read from stdin and {@code -o -} to
 * write to stdout. When writing to stdout, XML is validated via a temporary
 * file before streaming. All progress messages are written to stderr so they do
 * not contaminate the pipe.
 *
 * <br>
 * <b>Validation:</b> output XML is validated with Java's SAX parser after
 * writing. If validation fails, the tool reports the line and column number,
 * deletes the output, and exits with code 1. Use {@code --validate false} to
 * skip validation.
 *
 * <br>
 * <b>Config file</b> ({@code .properties} format, namespace {@code tab2xdxf.*}
 * — CLI args override):
 * 
 * <pre>
 *   tab2xdxf.in=data/kaikki-pt.tsv
 *   tab2xdxf.out=-
 *   tab2xdxf.lang=pt
 *   tab2xdxf.name=Dicionário PT (Kaikki)
 *   tab2xdxf.validate=false
 * </pre>
 *
 * <br>
 * <b>Tabfile format:</b> one entry per line: {@code word&lt;TAB&gt;definition}.
 * Lines starting with {@code #} and blank lines are ignored. Definitions may
 * contain HTML — it is cleaned and made XML-safe before writing.
 *
 * <br>
 * <b>Processing pipeline:</b>
 * <ol>
 * <li>Reads TSV input line by line</li>
 * <li>Cleans HTML definitions:
 * <ul>
 * <li>Strips all tag attributes</li>
 * <li>Whitelists safe tags: {@code p, b, i, em, strong, ol, ul, li,
 *           sub, sup, br, a, div, span, table, tr, td, th}</li>
 * <li>Self-closes void elements: {@code <br/>
 * }, {@code 
 * 
<hr/>
 * }, {@code <img/>}</li>
 * <li>Escapes bare {@code &}, {@code <}, {@code >} characters</li>
 * <li>Replaces non-breaking spaces ({@code \u00a0}) with regular spaces</li>
 * </ul>
 * </li>
 * <li>Formats definitions for readability on the e-reader</li>
 * <li>Writes valid XDXF with one {@code <ar>} entry per line</li>
 * <li>Validates output XML with Java's SAX parser (unless
 * {@code --validate false})</li>
 * </ol>
 *
 * <br>
 * <b>Requirements:</b> Java 17+ JDK ({@code java} must be on PATH)
 */
public class ConvTab2Xdxf {

	static final String TOOL = "tab-2-xdxf";

	public static void err(String msg) {
		System.err.println(TOOL + ": " + msg);
	}

	static void errRaw(String msg) {
		System.err.println(msg);
	}

	// ISO 639-1 → ISO 639-2 mapping
	private static final Map<String, String> ISO1_TO_ISO2 = Map.ofEntries(Map.entry("pt", "POR"),
			Map.entry("en", "ENG"), Map.entry("es", "SPA"), Map.entry("fr", "FRA"), Map.entry("de", "DEU"),
			Map.entry("it", "ITA"), Map.entry("nl", "NLD"), Map.entry("ru", "RUS"), Map.entry("zh", "ZHO"),
			Map.entry("ja", "JPN"), Map.entry("ar", "ARA"), Map.entry("pl", "POL"), Map.entry("sv", "SWE"),
			Map.entry("da", "DAN"), Map.entry("fi", "FIN"), Map.entry("nb", "NOB"), Map.entry("cs", "CES"),
			Map.entry("hu", "HUN"), Map.entry("ro", "RON"), Map.entry("tr", "TUR"));

	// ── main ──────────────────────────────────────────────────────────────────
	public static void main(String[] args) throws Exception {
		if (args.length == 0 || Arrays.asList(args).contains("--help")) {
			err(usage());
			System.exit(0);
		}

		var cli = parseArgs(args);

		// Load config file if specified
		String configPath = cli.get("config");
		var argsHlp = new ArgsHelper("tab2xdxf", er -> err(er), () -> usage());
		Properties config = argsHlp.loadProperties(configPath, true);
		argsHlp.mergeConfig2Cli(List.of("in", "out", "name", "lang", "validate"), cli, config);

		String input = argsHlp.require(cli, "in", "--in/-i");
		String output = argsHlp.require(cli, "out", "--out/-o");
		String lang = argsHlp.require(cli, "lang", "--lang/-l");
		String name = argsHlp.require(cli, "name", "--name/-n");
		Boolean validate = argsHlp.resolveBool(cli, "validate", true);

		boolean fromStdin = "-".equals(input);
		boolean toStdout = "-".equals(output);

		Path outFile = toStdout ? null : Path.of(output);
		String lang639 = toIso6392(lang);

		if (!fromStdin && !Files.exists(Path.of(input))) {
			err(String.format("Error: file not found: %s", input));
			System.exit(1);
		}

		err(TOOL);
		errRaw(String.format("  input   : %s", (fromStdin ? "<stdin>" : input)));
		errRaw(String.format("  output  : %s", (toStdout ? "<stdout>" : outFile)));
		errRaw(String.format("  lang    : %s", lang639));
		errRaw(String.format("  name    : %s", name));
		if (!validate)
			errRaw("  validate: no");
		else if (toStdout)
			errRaw("  validate: yes (via temp file)");
		else
			errRaw("  validate: yes");
		if (configPath != null)
			errRaw(String.format("  config  : %s", configPath));
		errRaw("");

		var entries = fromStdin ? readTabfile(System.in) : readTabfile(new FileInputStream(input));
		if (toStdout && !validate) {
			// Write to temp file, validate, then stream to stdout
			Path tmp = Files.createTempFile("tab2xdxf-", ".xdxf");
			try {
				XdxfHelper.writeXdxf(entries, name, lang639, tmp, er -> err(er));
				// exits on failure, deletes tmp
				XdxfHelper.validateXdxf(tmp, true, er -> err(er));
				try (var in = new java.io.FileInputStream(tmp.toFile())) {
					in.transferTo(System.out);
				}
			} finally {
				Files.deleteIfExists(tmp);
			}
		} else if (toStdout) {
			XdxfHelper.writeXdxf(entries, name, lang639, System.out);
		} else {
			XdxfHelper.writeXdxf(entries, name, lang639, outFile, er -> err(er));
			if (validate) {
				// exits on failure, deletes tmp
				XdxfHelper.validateXdxf(outFile, true, er -> err(er));
			}
		}
	}

	// --- Tabfile reader ---

	static List<XdxfEntry> readTabfile(InputStream stream) throws Exception {
		var entries = new ArrayList<XdxfEntry>();
		try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			String line;
			int lineNum = 0;
			while ((line = reader.readLine()) != null) {
				lineNum++;
				if (line.isBlank() || line.startsWith("#"))
					continue;
				int tab = line.indexOf('\t');
				if (tab == -1) {
					err(String.format("Warning: line %d has no tab, skipping.", lineNum));
					continue;
				}
				entries.add(new XdxfEntry(line.substring(0, tab).strip(), line.substring(tab + 1)));
			}
		}
		err(String.format("read %d entries.", entries.size()));
		return entries;
	}

	// --- Helpers ---

	static String toIso6392(String lang) {
		if (lang == null)
			return "ENG";
		if (lang.length() == 2) {
			String mapped = ISO1_TO_ISO2.get(lang.toLowerCase());
			return mapped != null ? mapped : lang.toUpperCase();
		}
		return lang.toUpperCase();
	}

	static Map<String, String> parseArgs(String[] args) {
		var map = new LinkedHashMap<String, String>();
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
			case "--config", "-c" -> map.put("config", args[++i]);
			case "--in", "-i" -> map.put("in", args[++i]);
			case "--out", "-o" -> map.put("out", args[++i]);
			case "--lang", "-l" -> map.put("lang", args[++i]);
			case "--name", "-n" -> map.put("name", args[++i]);
			case "--validate", "-v" -> map.put("validate", args[++i]);
			default -> err(String.format("Warning: unknown argument '%s', ignoring.", args[i]));
			}
		}
		return map;
	}

	static String usage() {
		return """
				Convert dictionary data from TSV to XDXF.

				Usage: tab-2-xdxf --in|-i  <input.tsv|->   --out|-o <output.xdxf|->
				                   --lang|-l <ISO 639-1 or 639-2>
				                   --name|-n <dictionary name>
				                   [--validate|-v] <true/false>
				                   [--config|-c <config.properties>]

				--in|-i        Input tabfile or - for stdin.
				--out|-o       Output XDXF file or - for stdout. Stdout output validates via a temp file.
				--validate|-v  If false skip XML validation.

				Examples:
				  tab-2-xdxf -i data/out/kaikki-pt.tsv -o data/out/kaikki-pt.xdxf -l pt -n "Dicionário PT"
				  tab-2-xdxf -i - -o data/out/kaikki-pt.xdxf -l pt -n "Dicionário PT"
				  kaikki-2-tab -c dict.properties | tab-2-xdxf -c dict.properties | xdxf-2-pbdic -c dict.properties
				  tab-2-xdxf -c dict.properties
				""";
	}
}
