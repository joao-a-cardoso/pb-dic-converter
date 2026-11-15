import static shared.XdxfHelper.parseXdxf;

import java.io.ByteArrayOutputStream;
import java.io.File;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import shared.ArgsHelper;
import shared.DictzipUtil;
import shared.XdxfHelper.ParsedEntry;
import shared.XdxfHelper.XdxfHeader;

/**
 * xdxf-2-stardict — convert an XDXF dictionary to StarDict format.
 *
 * <p>
 * For usage, options and examples run {@code linux/xdxf-2-stardict --help}.
 *
 * <p>
 * Output files, written to the output directory:
 * <ul>
 * <li>{@code stardict.ifo} — metadata</li>
 * <li>{@code stardict.idx} — index: {@code word\0} + 4-byte offset + 4-byte
 * size, big-endian</li>
 * <li>{@code stardict.dict.dz} (or {@code stardict.dict} uncompressed) —
 * concatenated definitions</li>
 * </ul>
 * Definitions are stored as HTML ({@code sametypesequence=h}), preserving the
 * markup produced by {@code tab-2-xdxf} for KOReader and other StarDict
 * clients.
 */
public class ConvXdxf2Stardict {

	static final String TOOL = "xdxf-2-stardict";

	static final String version = "2.4.2";

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
		if (args.length == 0 || Arrays.asList(args).contains("--help")) {
			abort(usage());
		}

		var cli = parseArgs(args);
		var argsHlp = new ArgsHelper(TOOL, "xdxf2stardict", er -> err(er), () -> usage());
		String configPath = cli.get("config");
		Properties config = argsHlp.loadProperties(configPath, true);
		argsHlp.mergeConfig2Cli(List.of("in", "out", "name", "merge-defs", "compress"), cli, config);

		String inPath = argsHlp.require(cli, "in", "--in/-i");
		String outDir = argsHlp.require(cli, "out", "--out/-o");
		String name = argsHlp.resolve(cli, "name", null);
		String description = null; // will be discovered later on
		boolean compress = argsHlp.requireBool(cli, "compress", "--compress/-z");

		boolean fromStdin = "-".equals(inPath);
		Path xdxfFile = fromStdin ? null : Path.of(inPath);

		if (!fromStdin && !Files.exists(xdxfFile)) {
			abort(String.format("Error: input file not found: %s", xdxfFile));
		}

		String mergeDefs = argsHlp.requireUC(cli, "merge-defs", "--merge-defs/-m");
		if (!mergeDefs.equals("ALWAYS") && !mergeDefs.equals("EXACT") && !mergeDefs.equals("NEVER")) {
			abort(String.format("Error: -m / --merge-defs must be ALWAYS, EXACT or NEVER, got: %s", mergeDefs));
		}

		cli.put("merge-defs", mergeDefs);
		argsHlp.listProperties(cli);

		// 1. Parse XDXF
		err("Parsing...");
		var header = new XdxfHeader[1];
		var entries = new ArrayList<ParsedEntry>();
		try (InputStream input = fromStdin ? System.in : Files.newInputStream(xdxfFile)) {
			int count = parseXdxf(input, //
					ConvXdxf2Stardict::htmlToSdic, //
					hdr -> header[0] = hdr, //
					entries::add);
			err(String.format("read %d entries.", count));
		}

		// Use data from the header
		if (header[0] != null) {
			if (name == null || name.isBlank()) {
				name = header[0].name();
			}
			description = header[0].description();
		}

		// there is no name, derive it from the output directory name
		if (name == null || name.isBlank()) {
			name = ArgsHelper.baseName(outDir);
		}

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
		writeStardict(compress, entries, outPath, name, description);

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

	static void writeStardict(boolean compress, List<ParsedEntry> entries, Path outPath, String name,
			String description) throws IOException {

		if (description == null || description.isBlank()) {
			description = name;
		}

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
			ifo.append("description=").append(description).append("\n");
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

	/**
	 * Maps a command line word to its option key, or {@code null} when it is not
	 * an option of this tool. Used by {@link ArgsHelper#parse} both to read options
	 * and to detect an option given where a value was expected.
	 */
	static String optionKey(String arg) {
		return switch (arg) {
		case "--config", "-c"     -> "config";
		case "--in", "-i"         -> "in";
		case "--out", "-o"        -> "out";
		case "--name", "-n"       -> "name";
		case "--merge-defs", "-m" -> "merge-defs";
		case "--compress", "-z"   -> "compress";
		default -> null;
		};
	}

	static Map<String, String> parseArgs(String[] args) throws IOException {
		return ArgsHelper.parse(args, ConvXdxf2Stardict::optionKey, er -> err(er), () -> usage());
	}

	static String usage() {

		return """
				Convert an XDXF dictionary to StarDict format (HTML definitions).

				Usage:
				  linux/xdxf-2-stardict -i <xdxf> -o <dir> -m ALWAYS|EXACT|NEVER -z true|false [options]

				Required:
				  -i / --in          Input XDXF file, or - for stdin
				  -o / --out         Output directory (created if missing); receives
				                     stardict.ifo, stardict.idx and stardict.dict.dz (or stardict.dict)
				  -m / --merge-defs  Duplicate headword handling:
				                       ALWAYS — case-insensitive match; merge different defs, drop identical
				                       EXACT  — case-sensitive match;   merge different defs, drop identical
				                       NEVER  — never merge; only drop byte-identical duplicates
				  -z / --compress    true — write stardict.dict.dz (compressed); false — write stardict.dict

				Optional:
				  -n / --name        Dictionary title (default: name in the XDXF header,
				                     else the output path without extension)
				  -c / --config      .properties config file (namespace: xdxf2stardict.*);
				                     command-line options override it

				Examples:
				  linux/xdxf-2-stardict -i data/out/kaikki-pt.xdxf -o data/out/kaikki-pt.d -m EXACT -z true
				  linux/tab-2-xdxf -i data/out/kaikki-pt.tsv -o - -l pt -n "Dicionário PT" | linux/xdxf-2-stardict -i - -o data/out/kaikki-pt.d -m EXACT -z true
				  """;
	}
}
