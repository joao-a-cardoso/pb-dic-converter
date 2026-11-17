import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.GZIPInputStream;

import shared.ArgsHelper;
import shared.IoHelper;
import shared.CliException;
import shared.ToolLog;
import shared.ToolRunner;

/**
 * stardict-2-tab — convert a StarDict dictionary to TSV
 * ({@code word<TAB>definition}), the input of {@code tab-2-xdxf}.
 *
 * <p>
 * For usage, options and examples run {@code linux/stardict-2-tab --help}.
 */
public class ConvStardict2Tab {

	private static final String TOOL = "stardict-2-tab";
	private static final ToolLog LOG = new ToolLog(TOOL);

	record Entry(String word, String definition) {
	}

	record RawEntry(String word, int offset, int size) {
	}

	// ── main ──────────────────────────────────────────────────────────────────
	public static void main(String[] args) {
		ToolRunner.run(TOOL, ConvStardict2Tab::usage, () -> run(args));
	}

	/** The tool's logic; ends with a {@link CliException} instead of {@code System.exit}. */
	static void run(String[] args) throws Exception {
		if (args.length == 0) { // -h / --help is handled by ArgsHelper.parse
			throw new CliException(null, 1, true);
		}

		var cli = parseArgs(args);
		var argsHlp = new ArgsHelper(TOOL, "stardict2tab", er -> LOG.err(er), () -> usage());
		// Load config file if specified
		Properties config = argsHlp.loadConfig(cli);
		argsHlp.mergeConfig2Cli(List.of("in", "out"), cli, config);

		String input = argsHlp.require(cli, "in", "--in/-i");
		String output = argsHlp.require(cli, "out", "--out/-o");

		boolean toStdout = "-".equals(output);

		Path inFile = Path.of(input);
		if (!Files.exists(inFile)) {
			throw CliException.error("Error: file not found: " + inFile);
		}

		argsHlp.listProperties(cli);

		var entries = readStarDict(inFile);

		try (OutputStream outStream = IoHelper.openOutput(output);
				var writer = new BufferedWriter(new OutputStreamWriter(outStream, StandardCharsets.UTF_8))) {
			for (var e : entries)
				writer.write(e.word() + "\t" + e.definition().replace("\n", " ").replace("\t", " ") + "\n");
		}

		LOG.err("Done. " + entries.size() + " entries written to " + (toStdout ? "<stdout>" : ArgsHelper.displayPath(output)) + ".");
	}

	// --- StarDict reader ---

	static List<Entry> readStarDict(Path ifoFile) throws Exception {
		// Parse .ifo
		var ifoProps = new Properties();
		try (var r = Files.newBufferedReader(ifoFile, StandardCharsets.UTF_8)) {
			String firstLine = r.readLine();
			if (!"StarDict's dict ifo file".equals(firstLine))
				throw new IOException("Not a StarDict .ifo file: " + ifoFile);
			String line;
			while ((line = r.readLine()) != null) {
				int eq = line.indexOf('=');
				if (eq > 0)
					ifoProps.setProperty(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
			}
		}

		Path dir = ifoFile.getParent();
		String stem = ifoFile.getFileName().toString().replaceFirst("\\.ifo$", "");
		Path idxFile = dir.resolve(stem + ".idx");

		// Try .dict.dz first, then .dict
		Path dictDz = dir.resolve(stem + ".dict.dz");
		Path dictRaw = dir.resolve(stem + ".dict");
		Path dictFile = Files.exists(dictDz) ? dictDz : dictRaw;

		if (!Files.exists(idxFile))
			throw new IOException("Missing: " + idxFile);
		if (!Files.exists(dictFile))
			throw new IOException("Missing: " + dictFile);

		// Read .idx
		byte[] idxData = Files.readAllBytes(idxFile);
		var rawEntries = new ArrayList<RawEntry>();
		int pos = 0;
		while (pos < idxData.length) {
			int nullPos = pos;
			while (nullPos < idxData.length && idxData[nullPos] != 0)
				nullPos++;
			String word = new String(idxData, pos, nullPos - pos, StandardCharsets.UTF_8);
			int offset = ByteBuffer.wrap(idxData, nullPos + 1, 4).order(ByteOrder.BIG_ENDIAN).getInt();
			int size = ByteBuffer.wrap(idxData, nullPos + 5, 4).order(ByteOrder.BIG_ENDIAN).getInt();
			rawEntries.add(new RawEntry(word, offset, size));
			pos = nullPos + 9;
		}

		// Read .dict(.dz)
		byte[] dictData;
		if (dictFile.toString().endsWith(".dz")) {
			try (var raw = Files.newInputStream(dictFile); // own variable: closed even if the gzip header is bad
					var gzip = new GZIPInputStream(raw);
					var baos = new ByteArrayOutputStream()) {
				gzip.transferTo(baos);
				dictData = baos.toByteArray();
			}
		} else {
			dictData = Files.readAllBytes(dictFile);
		}

		List<Entry> entries = new ArrayList<>(rawEntries.size());
		for (var raw : rawEntries)
			entries.add(new Entry(raw.word(), new String(dictData, raw.offset(), raw.size(), StandardCharsets.UTF_8)));

		LOG.err("Read. " + entries.size() + " entries.");
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
		case "--config", "-c" -> "config";
		case "--def"          -> ArgsHelper.DEF_OPTION;
		case "--in", "-i"     -> "in";
		case "--out", "-o"    -> "out";
		default -> null;
		};
	}

	static Map<String, String> parseArgs(String[] args) {
		return ArgsHelper.parse(args, ConvStardict2Tab::optionKey, er -> LOG.err(er), () -> usage());
	}

	static String usage() {
		return TOOL + ": " + """
				Convert a StarDict dictionary to TSV (word<TAB>definition).

				Usage:
				  linux/stardict-2-tab -i <ifo> -o <tsv> [options]

				Required:
				  -i / --in      StarDict .ifo file; the .idx and .dict.dz (or .dict) files
				                 with the same name are read from the same directory
				  -o / --out     Output TSV file, or - for stdout

				Optional:
				  -c / --config  .properties config file (namespace: stardict2tab.*);
				                 command-line options override it
				  --def <name>=<value>
				                 Set or override a config variable (def); repeatable
				  -h / --help    Show this help

				Examples:
				  linux/stardict-2-tab -i data/stardict/dict-pt-pt/dict-data.ifo -o data/out/dict-pt-pt.tsv
				  linux/stardict-2-tab -i data/stardict/dict-pt-pt/dict-data.ifo -o - | linux/tab-2-xdxf -i - ...
				  linux/stardict-2-tab -c confs/stardict2xdxf-pt-pt.config
				""";
	}
}
