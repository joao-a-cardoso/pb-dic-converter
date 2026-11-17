import static shared.TextHelper.lower;
import static shared.XdxfHelper.parseXdxf;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import shared.ArgsHelper;
import shared.IoHelper;
import shared.MergeMode;
import shared.CliException;
import shared.ToolLog;
import shared.ToolRunner;
import shared.DictzipHelper;
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
	private static final ToolLog LOG = new ToolLog(TOOL);

	/**
	 * Files of a StarDict dictionary named "stardict" (.syn: synonyms, not written
	 * but removed if stale).
	 */
	static final List<String> STARDICT_EXTENSIONS = List.of(".ifo", ".idx", ".dict", ".dict.dz", ".syn");

	static final String version = "2.4.2";

	// ── main ──────────────────────────────────────────────────────────────────
	public static void main(String[] args) {
		ToolRunner.run(TOOL, ConvXdxf2Stardict::usage, () -> run(args));
	}

	/** The tool's logic; ends with a {@link CliException} instead of {@code System.exit}. */
	static void run(String[] args) throws Exception {
		if (args.length == 0) { // -h / --help is handled by ArgsHelper.parse
			throw new CliException(null, 1, true);
		}

		var cli = parseArgs(args);
		var argsHlp = new ArgsHelper(TOOL, "xdxf2stardict", er -> LOG.err(er), () -> usage());
		Properties config = argsHlp.loadConfig(cli);
		argsHlp.mergeConfig2Cli(List.of("in", "out", "name", "merge-defs", "compress"), cli, config);

		String inPath = argsHlp.require(cli, "in", "--in/-i");
		String outDir = argsHlp.require(cli, "out", "--out/-o");
		String name = argsHlp.resolve(cli, "name", null);
		String description = null; // will be discovered later on
		boolean compress = argsHlp.requireBool(cli, "compress", "--compress/-z");

		boolean fromStdin = "-".equals(inPath);
		Path xdxfFile = fromStdin ? null : Path.of(inPath);

		if (!fromStdin && !Files.exists(xdxfFile)) {
			throw CliException.error(String.format("Error: input file not found: %s", xdxfFile));
		}

		MergeMode mergeDefs = argsHlp.requireParsed(MergeMode::parse,cli, "merge-defs", "--merge-defs/-m");
		argsHlp.listProperties(cli);

		// 1. Parse XDXF
		LOG.err("Parsing...");
		var header = new XdxfHeader[1];
		var entries = new ArrayList<ParsedEntry>();
		try (InputStream input = IoHelper.openInput(inPath)) {
			int count = parseXdxf(input, //
					ConvXdxf2Stardict::htmlToSdic, //
					hdr -> header[0] = hdr, //
					entries::add);
			LOG.err(String.format("read %d entries.", count));
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
		LOG.err("Sorting...");
		entries.sort(Comparator.comparing((ParsedEntry e) -> lower(e.word()))
				.thenComparing(e -> e.word()));

		// 3. Merge/dedup headwords according to -m / --merge-defs (see MergeMode)
		LOG.err("Merging duplicates...");
		var merged = new ArrayList<ParsedEntry>(entries.size());
		for (ParsedEntry e : entries) {
			if (merged.isEmpty()) {
				merged.add(e);
				continue;
			}
			ParsedEntry prev = merged.getLast();
			switch (mergeDefs.decide(prev.word(), prev.definition(), e.word(), e.definition())) {
			case DIFFERENT, KEEP_BOTH -> merged.add(e);
			case DROP -> { } // identical definition
			case JOIN -> merged.set(merged.size() - 1, new ParsedEntry(mergeDefs.keptWord(prev.word(), e.word()),
					joinDefs(prev.definition(), e.definition())));
			}
		}
		int mergedCount = entries.size() - merged.size();
		if (mergedCount > 0) {
			LOG.err(String.format("Merged/deduped %d entries (merge-defs=%s).", mergedCount, mergeDefs));
		}
		entries = merged;

		// 4. Write StarDict files
		checkDictSize(entries, compress); // before touching any file
		LOG.err("Writing...");
		Path outPath = Path.of(outDir);
		Files.createDirectories(outPath);
		writeStardict(compress, entries, outPath, name, description);

		String extensions = STARDICT_EXTENSIONS.stream()
				.filter(ext -> Files.exists(outPath.resolve("stardict" + ext)))
				.collect(Collectors.joining(", "));

		LOG.err(String.format("Done. %d entries written to %s. {%s}.", entries.size(), //
				ArgsHelper.displayPath(outPath.toString()), extensions));
	}

	// ── Html Parser for Stardict ──────────────────────────────────────────────

	static byte[] htmlToSdic(CharSequence html) {
		return html.toString().getBytes(StandardCharsets.UTF_8);
	}

	// ── StarDict writer ───────────────────────────────────────────────────────

	/** Largest .dict: the .idx holds offsets as unsigned 32-bit numbers. */
	static final long MAX_DICT_SIZE = 0xFFFF_FFFFL;

	/**
	 * Fails with a clear message when the definitions do not fit in one StarDict
	 * dictionary: 4 GB for .dict, about 1.9 GB for .dict.dz (dictzip limit).
	 */
	static void checkDictSize(List<ParsedEntry> entries, boolean compress) {
		long total = 0;
		for (ParsedEntry e : entries) {
			total += e.definition().length;
		}
		long max = compress ? DictzipHelper.MAX_SIZE : MAX_DICT_SIZE;
		if (total > max) {
			throw CliException.error(String.format(Locale.ROOT,
					"Error: the definitions take %,d bytes, more than %s allows (%,d bytes). Split the dictionary%s.",
					total, compress ? "a compressed .dict.dz" : "a StarDict .dict", max,
					compress ? ", or use --compress false (up to 4 GB)" : ""));
		}
	}

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

		// Build .idx and .dict while going through the entries, in memory and
		// compressed (SafeOutput; a .dict.dz is held as its compressed chunks, see
		// DictzipHelper): the files of a previous build are only replaced once the
		// new ones are complete

		long idxSize = 0;
		if (compress) {
			LOG.err("... compressing dict file");
		}
		try (var idx = IoHelper.openSafeOutput(idxFile.toPath());
				var dict = compress ? null : IoHelper.openSafeOutput(dictFile.toPath());
				var dz = compress ? DictzipHelper.openCompressor() : null) {
			OutputStream idxOut = idx.stream();
			OutputStream dictOut = dict == null ? null : dict.stream();
			long offset = 0; // up to MAX_DICT_SIZE (checkDictSize), written as unsigned 32 bits
			for (ParsedEntry e : entries) {
				byte[] wordBytes = e.word().getBytes(StandardCharsets.UTF_8);
				int size = e.definition().length;

				// .idx: word\0 + 4-byte big-endian offset + 4-byte big-endian size
				idxOut.write(wordBytes);
				idxOut.write(0);
				writeU32BE(idxOut, (int) offset);
				writeU32BE(idxOut, size);
				idxSize += wordBytes.length + 9;

				// .dict: raw definition bytes
				if (dz != null) {
					dz.write(e.definition());
				} else {
					dictOut.write(e.definition());
				}
				offset += size;
			}

			// .ifo (needs the size of the .idx)
			var ifo = new StringBuilder();
			ifo.append("StarDict's dict ifo file\n");
			ifo.append("version=" + version + "\n");
			ifo.append("wordcount=").append(entries.size()).append("\n");
			ifo.append("idxfilesize=").append(idxSize).append("\n");
			ifo.append("bookname=").append(name).append("\n");
			ifo.append("description=").append(description).append("\n");
			ifo.append("sametypesequence=h\n");

			// Everything is built: only now replace the previous build. Remove only the
			// files of a StarDict build (never anything else: the folder may hold other
			// files). The .ifo, which readers look for, is written last.
			for (String ext : STARDICT_EXTENSIONS) {
				Files.deleteIfExists(outPath.resolve(filename + ext));
			}
			if (dz != null) {
				try (var dictFileOut = new BufferedOutputStream(Files.newOutputStream(dictFile.toPath()))) {
					dz.finishTo(dictFileOut);
				}
			} else {
				dict.commit();
			}
			idx.commit();
			Files.writeString(ifoFile.toPath(), ifo, StandardCharsets.UTF_8);
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
	 * Maps a command line word to its option key, or {@code null} when it is not an
	 * option of this tool. Used by {@link ArgsHelper#parse} both to read options
	 * and to detect an option given where a value was expected.
	 */
	static String optionKey(String arg) {
		return switch (arg) {
		case "--config", "-c" -> "config";
		case "--def" -> ArgsHelper.DEF_OPTION;
		case "--in", "-i" -> "in";
		case "--out", "-o" -> "out";
		case "--name", "-n" -> "name";
		case "--merge-defs", "-m" -> "merge-defs";
		case "--compress", "-z" -> "compress";
		default -> null;
		};
	}

	static Map<String, String> parseArgs(String[] args) throws IOException {
		return ArgsHelper.parse(args, ConvXdxf2Stardict::optionKey, er -> LOG.err(er), () -> usage());
	}

	static String usage() {
		return TOOL + ": "
				+ """
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
						  --def <name>=<value>
						                     Set or override a config variable (def); repeatable
						  -h / --help        Show this help

						Examples:
						  linux/xdxf-2-stardict -i data/out/kaikki-pt.xdxf -o data/out/kaikki-pt.d -m EXACT -z true
						  linux/tab-2-xdxf -i data/out/kaikki-pt.tsv -o - -l pt -n "Dicionário PT" | linux/xdxf-2-stardict -i - -o data/out/kaikki-pt.d -m EXACT -z true
						  """;
	}
}
