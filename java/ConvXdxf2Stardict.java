import static shared.XdxfHelper.parseXdxf;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import java.util.zip.GZIPOutputStream;

import shared.ArgsHelper;
import shared.DictzipUtil;
import shared.XdxfHelper.ParsedEntry;

/**
 * xdxf-2-stardict — convert an XDXF dictionary to StarDict format.
 *
 * Produces three files from a base output path: &lt;base&gt;.ifo — metadata
 * &lt;base&gt;.idx — binary index (word\0 + 4-byte offset + 4-byte size,
 * big-endian) &lt;base&gt;.dict.dz — gzip-compressed concatenated HTML
 * definitions
 *
 * Definitions are stored as HTML (sametypesequence=h), preserving the markup
 * produced by tab-2-xdxf for full rendering in KOReader and other StarDict
 * clients.
 *
 * Usage: linux/xdxf-2-stardict -i &lt;xdxf&gt; -o &lt;base&gt; -m
 * ALWAYS|EXACT|NEVER [options]
 *
 * Required: -i / --in Input XDXF file, or - for stdin -o / --out Output base
 * path (e.g. data/out/kaikki-pt) -m / --merge-defs ALWAYS|EXACT|NEVER —
 * duplicate headword handling
 *
 * Optional: -n / --name Dictionary title (default: base filename) -c / --config
 * .properties config file (namespace: xdxf2stardict.*)
 */
public class ConvXdxf2Stardict {

	static final String TOOL = "xdxf-2-stardict";

	static final String version = "2.4.2";

	static void err(String msg) {
		if (msg.isBlank() || msg.startsWith(TOOL)) {
			errRaw(msg);
		} else {
			errRaw(TOOL + ": " + msg);
		}
	}

	static void errRaw(String msg) {
		System.err.println(msg);
	}

	// ── main ──────────────────────────────────────────────────────────────────
	public static void main(String[] args) throws Exception {
		if (args.length == 0 || Arrays.asList(args).contains("--help")) {
			err(usage());
			System.exit(0);
		}

		var cli = parseArgs(args);
		var argsHlp = new ArgsHelper(TOOL, "xdxf2stardict", er -> err(er), () -> usage());
		String configPath = cli.get("config");
		Properties config = argsHlp.loadProperties(configPath, true);
		argsHlp.mergeConfig2Cli(List.of("in", "out", "name", "merge-defs", "compress"), cli, config);

		String inPath = argsHlp.require(cli, "in", "--in/-i");
		String outDir = argsHlp.require(cli, "out", "--out/-o");
		String name = argsHlp.resolve(cli, "name", "--name/-n");
		boolean compress = argsHlp.requireBool(cli, "compress", "--compress/-z");

		boolean fromStdin = "-".equals(inPath);
		Path xdxfFile = fromStdin ? null : Path.of(inPath);

		if (!fromStdin && !Files.exists(xdxfFile)) {
			err(String.format("Error: input file not found: %s", xdxfFile));
			System.exit(1);
		}

		if (name == null) {
			// TODO; read the name from the XDXF content
			String fname = outDir;
			name = fname.contains(".") ? fname.substring(0, fname.lastIndexOf('.')) : fname;
		}

		String mergeDefs = argsHlp.requireUC(cli, "merge-defs", "--merge-defs/-m");
		if (!mergeDefs.equals("ALWAYS") && !mergeDefs.equals("EXACT") && !mergeDefs.equals("NEVER")) {
			err(String.format("Error: -m / --merge-defs must be ALWAYS, EXACT or NEVER, got: %s", mergeDefs));
			System.exit(1);
		}

		cli.put("merge-defs", mergeDefs);
		argsHlp.listProperties(cli);

		// 1. Parse XDXF
		err("Parsing...");
		InputStream input = fromStdin ? System.in : new FileInputStream(xdxfFile.toFile());
		List<ParsedEntry> entries = parseXdxf(ConvXdxf2Stardict::htmlToSdic, input);

		// 2. Sort alphabetically (case-insensitive)
		err("Sorting...");
		entries.sort(Comparator.comparing((ParsedEntry e) -> e.word().toLowerCase(Locale.ROOT))
				.thenComparing(e -> e.word()));

		// 3. Merge/dedup
		err("Merging duplicates...");
		var merged = new ArrayList<ParsedEntry>(entries.size());
		for (ParsedEntry e : entries) {
			if (!merged.isEmpty()) {
				ParsedEntry prev = merged.get(merged.size() - 1);
				boolean sameWord = mergeDefs.equals("ALWAYS") ? prev.word().equalsIgnoreCase(e.word())
						: prev.word().equals(e.word());
				if (sameWord) {
					boolean sameDef = Arrays.equals(prev.definition(), e.definition());
					if (!sameDef && !mergeDefs.equals("NEVER")) {
						merged.remove(merged.size() - 1);
						String keepWord = mergeDefs.equals("ALWAYS")
								? (prev.word().equals(prev.word().toLowerCase(Locale.ROOT)) ? prev.word() : e.word())
								: prev.word();
						merged.add(new ParsedEntry(keepWord, joinDefs(prev.definition(), e.definition())));
					} else if (sameDef) {
						// identical — discard silently
					} else {
						// NEVER mode, different defs — keep both
						merged.add(e);
						continue;
					}
					continue;
				}
			}
			merged.add(e);
		}
		int mergedCount = entries.size() - merged.size();
		if (mergedCount > 0) {
			err(String.format("Merged/deduped %d entries (merge-defs=%s).", mergedCount, mergeDefs));
		}
		entries = merged;

		// 4. Write StarDict files
		err("Writing...");
		Path outPath = Path.of(outDir);
		Files.createDirectories(outPath);
		// remove existing files
		Files.list(outPath).forEach(pa -> pa.toFile().delete());
		writeStardict(compress, entries, outPath, name);

		String extensions = Files.list(outPath) //
				.map(pa -> "." + pa.getFileName().toString().split("\\.", 2)[1])//
				.collect(Collectors.joining(", "));

		err(String.format("Done. %d entries written to %s. {%s}.", entries.size(), //
				outPath.toString(), extensions));
	}

	// ── Html Parser for Stardict ──────────────────────────────────────────────

	static byte[] htmlToSdic(CharSequence html) {
		return html.toString().getBytes(StandardCharsets.UTF_8);
	}

	// ── StarDict writer ───────────────────────────────────────────────────────

	static void writeStardict(boolean compress, List<ParsedEntry> entries, Path outPath, String name)
			throws IOException {

		String filename = "stardict";

		File parentFile = outPath.toFile();
		File idxFile = new File(parentFile, filename + ".idx");
		File dictFile = new File(parentFile, filename + ".dict" + (compress ? ".dz" : ""));
		File ifoFile = new File(parentFile, filename + ".ifo");

		// Build .dict content and .idx simultaneously

		ByteArrayOutputStream dictStrm;
		ByteArrayOutputStream idxStrm;
		{
			dictStrm = new ByteArrayOutputStream();
			idxStrm = new ByteArrayOutputStream();
			int offset = 0;
			for (ParsedEntry e : entries) {
				byte[] wordBytes = e.word().getBytes(StandardCharsets.UTF_8);
				int size = e.definition().length;

				// .idx: word\0 + 4-byte big-endian offset + 4-byte big-endian size
				idxStrm.write(wordBytes);
				idxStrm.write(0);
				writeU32BE(idxStrm, offset);
				writeU32BE(idxStrm, size);

				// .dict: raw definition bytes
				dictStrm.write(e.definition());
				offset += size;
			}
		}

		// Build the .ifoPath file
		ByteArrayOutputStream ifoStrm;
		{
			ifoStrm = new ByteArrayOutputStream();
			var ifo = new StringBuilder();
			ifo.append("StarDict's dict ifo file\n");
			ifo.append("version=" + version + "\n");
			ifo.append("wordcount=").append(entries.size()).append("\n");
			ifo.append("idxfilesize=").append(idxStrm.size()).append("\n");
			ifo.append("bookname=").append(name).append("\n");
			ifo.append("sametypesequence=h\n");
			ifoStrm.write(ifo.toString().getBytes(StandardCharsets.UTF_8));
		}

		// Write all files

		try (var ifoOut = new FileOutputStream(ifoFile);
				var idxOut = new FileOutputStream(idxFile);
				var dictOut = new FileOutputStream(dictFile);) {

			// Write .ifo
			ifoStrm.writeTo(ifoOut);
			// Write .idx
			idxStrm.writeTo(idxOut);
			// Write .dict
			if (compress) {
				err("... compressing dict file");
				DictzipUtil.write(dictStrm, dictOut);
			} else {
				dictStrm.writeTo(dictOut);
			}
		}
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	/** Join two HTML definitions with a horizontal rule separator. */
	static byte[] joinDefs(byte[] a, byte[] b) throws IOException {
		byte[] sep = "<hr/>".getBytes(StandardCharsets.UTF_8);
		var out = new ByteArrayOutputStream(a.length + sep.length + b.length);
		out.write(a);
		out.write(sep);
		out.write(b);
		return out.toByteArray();
	}

	static void writeU32BE(OutputStream out, int v) throws IOException {
		out.write((v >> 24) & 0xFF);
		out.write((v >> 16) & 0xFF);
		out.write((v >> 8) & 0xFF);
		out.write(v & 0xFF);
	}

	// ── Config / CLI helpers ──────────────────────────────────────────────────

	static Map<String, String> parseArgs(String[] args) throws IOException {
		var cli = new LinkedHashMap<String, String>();
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
			case "--config", "-c" -> cli.put("config", args[++i]);
			case "--in", "-i" -> cli.put("in", args[++i]);
			case "--out", "-o" -> cli.put("out", args[++i]);
			case "--name", "-n" -> cli.put("name", args[++i]);
			case "--merge-defs", "-m" -> cli.put("merge-defs", args[++i]);
			case "--compress", "-z" -> cli.put("compress", args[++i]);
			default -> err(String.format("Warning: unknown argument '%s', ignoring.", args[i]));
			}
		}
		return cli;
	}

	static String usage() {

		return """
				Convert dictionary data from XDXF to StarDict format

				Usage:
				  linux/xdxf-2-stardict -i <xdxf> -o <base> -m ALWAYS|EXACT|NEVER [options]

				Required:
				  -i / --in          Input XDXF file, or - for stdin
				  -o / --out         Output base path (e.g. data/out/kaikki-pt)
				                     Produces: <base>.ifo, <base>.idx, <base>.dict.dz
				  -m / --merge-defs  ALWAYS — headword match is case-insensitive; merge different defs, drop identical
				                     EXACT  — headword match is case-sensitive;   merge different defs, drop identical
				                     NEVER  — never merge; only drop byte-identical duplicates
				  -z / --compress     true - dictoinary data is compress; false - is not compressed

				Optional:
				  -n / --name        Dictionary title (default to output file without extension. TODO: Read the name from the input XDXF content)
				  -c / --config      .properties config file (namespace: xdxf2stardict.*)

				Example:
				  linux/xdxf-2-stardict -i data/out/kaikki-pt.xdxf -o data/out/kaikki-pt -m EXACT -z false
				  linux/xdxf-2-stardict -c dict.properties
				  """;
	}
}
