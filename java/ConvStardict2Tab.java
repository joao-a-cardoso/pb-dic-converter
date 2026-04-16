import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.GZIPInputStream;

import shared.ArgsHelper;

/**
 * Converts a StarDict dictionary to a tabfile (TSV) for use with
 * {@code tab-2-xdxf}.
 *
 * <br>
 * <b>Usage:</b>
 * 
 * <pre>
 *   ./stardict-2-tab --in|-i &lt;input.ifo&gt; --out|-o &lt;output.tsv|-&gt;
 *                   [--config|-c &lt;config.properties&gt;]
 * </pre>
 *
 * <br>
 * <b>Examples:</b>
 * 
 * <pre>
 *   ./stardict-2-tab -i data/dict-pt-pt/dict-data.ifo -o data/out/dict-pt-pt.tsv
 *   ./stardict-2-tab -i data/dict-pt-pt/dict-data.ifo -o -   # write to stdout
 *   ./stardict-2-tab -c stardict.properties
 * </pre>
 *
 * <br>
 * <b>Stdout:</b> use {@code -o -} to write TSV to stdout for piping into
 * {@code tab-2-xdxf}. This can also be set in the config file. All progress
 * messages are written to stderr so they do not pollute the stream.
 *
 * <br>
 * <b>Config file</b> ({@code .properties} format — CLI args override):
 * 
 * <pre>
 *   stardict2tab.in=data/dict-pt-pt/dict-data.ifo
 *   stardict2tab.out=data/dict-pt-pt.tsv
 * </pre>
 *
 * <br>
 * <b>Input:</b> StarDict {@code .ifo} file — the {@code .idx} and
 * {@code .dict.dz} files are located automatically in the same directory. <br>
 * <b>Output:</b> TSV — one entry per line: {@code word&lt;TAB&gt;definition}
 *
 * <br>
 * <b>Requirements:</b> Java 17+ JDK ({@code java} must be on PATH)
 */
public class ConvStardict2Tab {

	private static final String TOOL = "stardict-2-tab";

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
		System.err.println(String.format(msg, args));
	}

	record Entry(String word, String definition) {
	}

	record RawEntry(String word, int offset, int size) {
	}

	// ── main ──────────────────────────────────────────────────────────────────
	public static void main(String[] args) throws Exception {
		if (args.length == 0 || Arrays.asList(args).contains("--help")) {
			abort(usage());
		}

		var cli = parseArgs(args);
		var argsHlp = new ArgsHelper(TOOL, "stardict2tab", er -> err(er), () -> usage());
		// Load config file if specified
		Properties config = argsHlp.loadProperties(cli.get("config"), true);
		argsHlp.mergeConfig2Cli(List.of("in", "out"), cli, config);

		String input = argsHlp.require(cli, "in", "--in/-i");
		String output = argsHlp.require(cli, "out", "--out/-o");

		boolean toStdout = "-".equals(output);

		Path inFile = Path.of(input);
		if (!Files.exists(inFile)) {
			abort("Error: file not found: " + inFile);
		}

		argsHlp.listProperties(cli);

		var entries = readStarDict(inFile);

		OutputStream outStream = toStdout ? System.out : new FileOutputStream(output);
		try (var writer = new BufferedWriter(new OutputStreamWriter(outStream, StandardCharsets.UTF_8))) {
			for (var e : entries)
				writer.write(e.word() + "\t" + e.definition().replace("\n", " ").replace("\t", " ") + "\n");
		}

		err("Done. " + entries.size() + " entries written to " + (toStdout ? "<stdout>" : output) + ".");
	}

	// --- StarDict reader ---

	static List<Entry> readStarDict(Path ifoFile) throws Exception {
		// Parse .ifo
		var ifoProps = new Properties();
		try (var r = new BufferedReader(
				new InputStreamReader(new FileInputStream(ifoFile.toFile()), StandardCharsets.UTF_8))) {
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
			try (var gzip = new GZIPInputStream(new FileInputStream(dictFile.toFile()));
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

		err("Read. " + entries.size() + " entries.");
		return entries;
	}

	// --- Helpers ---

	static Map<String, String> parseArgs(String[] args) {
		var map = new LinkedHashMap<String, String>();
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
			case "--config", "-c" -> map.put("config", args[++i]);
			case "--in", "-i" -> map.put("in", args[++i]);
			case "--out", "-o" -> map.put("out", args[++i]);
			default -> err("Warning: unknown argument '" + args[i] + "', ignoring.");
			}
		}
		return map;
	}

	static String usage() {
		return """
				Convert dictionary data from StarDict to TSV.

				Usage: stardict-2-tab --in|-i <input.ifo> --out|-o <output.tsv|->
				                      [--config|-c <config.properties>]

				Use -o - to write TSV to stdout (can also be set in the config file).

				Example:
				  stardict-2-tab -i data/dict-pt-pt/dict-data.ifo -o data/out/dict-pt-pt.tsv
				  stardict-2-tab -i data/dict-pt-pt/dict-data.ifo -o - | tab-2-xdxf -i - ...
				  stardict-2-tab -c stardict.properties
				""";
	}
}
