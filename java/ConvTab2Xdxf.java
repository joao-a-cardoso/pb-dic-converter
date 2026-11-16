
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import shared.ArgsHelper;
import shared.LangHelper;
import shared.XdxfHelper;
import shared.XdxfHelper.XdxfEntry;
import shared.XdxfHelper.XdxfHeader;

/**
 * tab-2-xdxf — convert a TSV dictionary ({@code word<TAB>definition}) to
 * XDXF, the input of {@code xdxf-2-pbdic} and {@code xdxf-2-stardict}.
 *
 * <p>
 * For usage, options and examples run {@code linux/tab-2-xdxf --help}.
 *
 * <p>
 * <b>Definition cleaning</b> ({@code shared.XmlHelper.cleanHtml}): only the
 * tags in {@code XmlHelper.ALLOWED_TAGS} are kept, without attributes (void tags
 * such as {@code br} are self-closed); other tags are dropped, keeping their
 * text; unbalanced tags are fixed; {@code &} and {@code <} that are not XML are
 * escaped; common HTML entities become characters.
 * The output is validated with Java's SAX parser; on failure the output is
 * deleted and the tool exits with code 1.
 */
public class ConvTab2Xdxf {

	static final String TOOL = "tab-2-xdxf";

	static void err(String msg, Object... args) {
		if (msg.isBlank() || msg.startsWith(TOOL)) {
			errRaw(msg, args);
		} else {
			errRaw(TOOL + ": " + msg, args);
		}
	}

	static void abort(String msg, Object... args) {
		err(msg, args);
		System.exit(1);
	}

	static void errRaw(String msg, Object... args) {
		// only format when arguments are given: pre-built messages may contain a literal '%'
		System.err.println(args.length == 0 ? msg : String.format(msg, args));
	}

	// ── main ──────────────────────────────────────────────────────────────────
	public static void main(String[] args) throws Exception {
		if (args.length == 0) { // -h / --help is handled by ArgsHelper.parse
			ArgsHelper.displayUsage(ConvTab2Xdxf::usage, System.err);
			System.exit(1);
		}

		var cli = parseArgs(args);

		// Load config file if specified
		var argsHlp = new ArgsHelper(TOOL, "tab2xdxf", er -> err(er), () -> usage());
		Properties config = argsHlp.loadConfig(cli);
		argsHlp.mergeConfig2Cli(List.of("in", "out", "name", "desc", "lang", "lang-to", "validate"), cli, config);

		String input = argsHlp.require(cli, "in", "--in/-i");
		String output = argsHlp.require(cli, "out", "--out/-o");
		String lang = argsHlp.requireLang(cli, "lang", "--lang/-l");
		String langTo = argsHlp.resolveLang(cli, "lang-to", "--lang-to/-t", lang);
		String name = argsHlp.require(cli, "name", "--name/-n");
		String desc = argsHlp.resolve(cli, "desc", name);
		Boolean validate = argsHlp.resolveBool(cli, "validate", true);

		boolean fromStdin = "-".equals(input);
		boolean toStdout = "-".equals(output);

		Path outFile = toStdout ? null : Path.of(output);
		String lang3 = LangHelper.langIso2toIso3(lang);
		String langTo3 = LangHelper.langIso2toIso3(langTo);

		if (!fromStdin && !Files.exists(Path.of(input))) {
			abort(String.format("Error: file not found: %s", input));
		}

		argsHlp.listProperties(cli);

		var header = new XdxfHeader(name, desc, lang3, langTo3, null);
		var entries = fromStdin ? readTabfile(System.in) : readTabfile(new FileInputStream(input));
		if (toStdout && validate) {
			// Write to temp file, validate, then stream to stdout
			Path tmp = Files.createTempFile("tab2xdxf-", ".xdxf");
			try {
				try (var out = Files.newOutputStream(tmp)) {
					XdxfHelper.writeXdxf(header, entries, out, er -> err(er));
				}
				// exits on failure, deletes tmp
				XdxfHelper.validateXdxf(tmp, true, er -> err(er));
				try (var in = Files.newInputStream(tmp)) {
					in.transferTo(System.out);
				}
				System.out.flush();
			} finally {
				Files.deleteIfExists(tmp);
			}
		} else if (toStdout) {
			XdxfHelper.writeXdxf(header, entries, System.out, er -> err(er));
		} else
			try (var out = Files.newOutputStream(outFile)) {
				XdxfHelper.writeXdxf(header, entries, out, er -> err(er));
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

	/**
	 * Maps a command line word to its option key, or {@code null} when it is not
	 * an option of this tool. Used by {@link ArgsHelper#parse} both to read options
	 * and to detect an option given where a value was expected.
	 */
	static String optionKey(String arg) {
		return switch (arg) {
		case "--config", "-c"   -> "config";
		case "--def"            -> ArgsHelper.DEF_OPTION;
		case "--in", "-i"       -> "in";
		case "--out", "-o"      -> "out";
		case "--lang", "-l"     -> "lang";
		case "--lang-to", "-t"  -> "lang-to";
		case "--name", "-n"     -> "name";
		case "--desc"           -> "desc";
		case "--validate", "-v" -> "validate";
		default -> null;
		};
	}

	static Map<String, String> parseArgs(String[] args) {
		return ArgsHelper.parse(args, ConvTab2Xdxf::optionKey, er -> err(er), () -> usage());
	}

	static String usage() {
		return TOOL + ": " + """
				Convert a TSV dictionary (word<TAB>definition) to XDXF.
				Blank lines and lines starting with # are ignored.

				Usage:
				  linux/tab-2-xdxf -i <tsv> -o <xdxf> -l <lang> -n <name> [options]

				Required:
				  -i / --in        Input TSV file, or - for stdin
				  -o / --out       Output XDXF file, or - for stdout
				  -l / --lang      Language of the headwords, ISO 639-1 code (e.g. pt, en)
				  -n / --name      Dictionary name

				Optional:
				  -t / --lang-to   Language of the definitions, ISO 639-1 code (default: --lang)
				       --desc      Dictionary description (default: --name)
				  -v / --validate  true|false — validate the XML output (default: true);
				                   with -o - it is validated in a temp file before streaming
				  -c / --config    .properties config file (namespace: tab2xdxf.*);
				                   command-line options override it
				  --def <name>=<value>
				                   Set or override a config variable (def); repeatable
				  -h / --help      Show this help

				Examples:
				  linux/tab-2-xdxf -i data/out/kaikki-pt.tsv -o data/out/kaikki-pt.xdxf -l pt -n "Dicionário PT"
				  linux/kaikki-2-tab -c confs/kaikki2xdxf-pt-pt.config | linux/tab-2-xdxf -c confs/kaikki2xdxf-pt-pt.config
				  linux/tab-2-xdxf -i - -o - -l en -t pt -n "EN-PT" < data/out/kaikki-en-pt.tsv > data/out/kaikki-en-pt.xdxf
				""";
	}
}
