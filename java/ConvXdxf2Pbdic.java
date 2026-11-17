
import static shared.TextHelper.lower;
import static shared.TextHelper.upper;
import static shared.XmlHelper.decodeEntities;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.zip.Deflater;

import shared.ArgsHelper;
import shared.IoHelper;
import shared.CliException;
import shared.ToolLog;
import shared.ToolRunner;
import shared.Constants;
import shared.XdxfHelper;
import shared.XdxfHelper.ParsedEntry;
import shared.XdxfHelper.XdxfHeader;

/**
 * xdxf-2-pbdic — convert an XDXF dictionary to PocketBook's native .dic
 * (SDIC) format.
 *
 * <p>
 * For usage, options and examples run {@code linux/xdxf-2-pbdic --help}.
 *
 * <p>
 * Language files (collates.txt, morphems.txt, keyboard.txt):
 * https://github.com/Markismus/LanguageFilesPocketbookConverter
 *
 * <p>
 * SDIC definition bytecodes: 0x02 = begin bold, 0x03 = begin italic, 0x01 =
 * end span, 0x0a = newline. Format details: {@code docs/sdic_format.pdf}.
 */
public class ConvXdxf2Pbdic {

	// ── SDIC format constants ─────────────────────────────────────────────────
	static final byte[] MAGIC = "SDIC".getBytes(StandardCharsets.US_ASCII);
	static final int VERSION = 0x00000101;
	static final int UNKNOWN_0C = 0x0000FFFC;
	static final int HEADER_SIZE = 0x80;

	static final byte FMT_END = 0x01;
	static final byte FMT_BOLD = 0x02;
	static final byte FMT_ITALIC = 0x03;
	static final byte FMT_NEWLINE = 0x0a;
	static final byte[] FMT_BULLET = (String.valueOf(Constants.UC_BULLET) + " ").getBytes(StandardCharsets.UTF_8);

	static final int TARGET_BLOCK_COMPRESSED = 4_096; // match converter.exe: target compressed size ≤ 4096 bytes
	static final int TARGET_BLOCK_DECOMPRESSED = 27_000; // hard ceiling on decompressed block size (firmware buffer)
	static final int TARGET_BLOCK_ENTRIES = 110; // ~110 entries/block keeps index_dsz ~51k, safely under 55k firmware
													// limit

	// ── main ──────────────────────────────────────────────────────────────────
	public static void main(String[] args) {
		ToolRunner.run(TOOL, ConvXdxf2Pbdic::usage, () -> run(args));
	}

	/** The tool's logic; ends with a {@link CliException} instead of {@code System.exit}. */
	static void run(String[] args) throws Exception {
		if (args.length == 0) { // -h / --help is handled by ArgsHelper.parse
			throw new CliException(null, 1, true);
		}

		var cli = parseArgs(args);
		var argsHlp = new ArgsHelper(TOOL, "xdxf2pbdic", er -> LOG.err(er), () -> usage());
		Properties config = argsHlp.loadConfig(cli);
		argsHlp.mergeConfig2Cli(List.of("in", "out", "lang", "langdir", "name", "merge-defs"), cli, config);

		String inPath = argsHlp.require(cli, "in", "--in/-i");
		String outPath = argsHlp.require(cli, "out", "--out/-o");
		String lang = argsHlp.requireLang(cli, "lang", "--lang/-l");
		String langDir = argsHlp.resolve(cli, "langdir", "lang/" + lang);
		String name = argsHlp.resolve(cli, "name", null);

		boolean fromStdin = "-".equals(inPath);
		Path xdxfPath = fromStdin ? null : Path.of(inPath);
		Path dicFile = Path.of(outPath);
		Path langDirPath = Path.of(langDir);

		if (!fromStdin && !Files.exists(xdxfPath)) {
			throw CliException.error(String.format("Error: input file not found: %s", xdxfPath));
		}
		if (!Files.isDirectory(langDirPath)) {
			LOG.err(String.format("Error: language directory not found: %s", langDirPath));
			LOG.raw("       Download from: https://github.com/Markismus/LanguageFilesPocketbookConverter");
			throw new CliException(null, 1, false); // messages already logged
		}

		String mergeDefs = argsHlp.requireUC(cli, "merge-defs", "--merge-defs/-m");
		mergeDefs = upper(mergeDefs);
		if (!mergeDefs.equals("ALWAYS") && !mergeDefs.equals("EXACT") && !mergeDefs.equals("NEVER")) {
			throw CliException.error(String.format("Error: -m / --merge-defs must be ALWAYS, EXACT or NEVER, got: %s", mergeDefs));
		}

		cli.put("merge-defs", mergeDefs);
		argsHlp.listProperties(cli);

		// 1. Language files
		var collation = loadCollation(langDirPath.resolve("collates.txt"));
		byte[] morphem = readAsUtf16Le(langDirPath.resolve("morphems.txt"));
		byte[] keyboard = Files.readAllBytes(langDirPath.resolve("keyboard.txt"));
		LOG.err(String.format("Language files loaded. %d collation pairs.", collation.size()));

		// 2. Parse XDXF
		LOG.err("Parsing...");
		var header = new XdxfHeader[1];
		var entries = new ArrayList<ParsedEntry>();
		try (InputStream input = IoHelper.openInput(inPath)) {
			int count = XdxfHelper.parseXdxf(input, //
					ConvXdxf2Pbdic::htmlToSdic, //
					hdr -> header[0] = hdr, //
					entries::add);
			LOG.err(String.format("read %d entries.", count));
		}

		// Use data from the header
		if (header[0] != null) {
			if (name == null || name.isBlank()) { // read the dict name from the header
				name = header[0].name();
			}
		}

		// there is no name, derive it from the output file name
		if (name == null || name.isBlank()) {
			name = ArgsHelper.baseName(outPath);
		}

		// 2b. A single definition too large for one block is truncated, ending with [...]
		truncateOversized(entries);

		// 3. Sort by collated headword
		LOG.err("Sorting...");
		entries.sort(Comparator.comparing(e -> collatedKey(e.word(), collation)));

		// 4. Merge/dedup headwords according to -m / --merge-defs mode:
		// ALWAYS — headword match is case-insensitive; merge different defs, drop
		// identical
		// EXACT — headword match is case-sensitive; merge different defs, drop
		// identical
		// NEVER — headword match is case-sensitive; never merge, only drop
		// byte-identical duplicates
		LOG.err("Merging duplicates...");
		var merged = new ArrayList<ParsedEntry>(entries.size());
		for (ParsedEntry e : entries) {
			if (!merged.isEmpty()) {
				ParsedEntry prev = merged.get(merged.size() - 1);
				boolean sameWord = mergeDefs.equals("ALWAYS") ? prev.word().equalsIgnoreCase(e.word())
						: prev.word().equals(e.word());
				if (sameWord) {
					boolean sameDef = Arrays.equals(prev.definition(), e.definition());
					if (!sameDef && !mergeDefs.equals("NEVER")) {
						// ALWAYS: keep lowercase headword; EXACT: keep first
						String keepWord = mergeDefs.equals("ALWAYS")
								? (prev.word().equals(lower(prev.word())) ? prev.word()
										: e.word())
								: prev.word();
						byte[] joined = joinDefsBlankLine(prev.definition(), e.definition());
						if (joined.length > maxDefinitionBytes(keepWord)) {
							// merged entry would not fit in a block: start a new entry for this word;
							// the following definitions are merged into it
							LOG.err(String.format("Note: '%s' — merged definitions reached the block limit (%,d bytes): new entry started",
									e.word(), maxDefinitionBytes(keepWord)));
							merged.add(e);
							continue;
						}
						merged.remove(merged.size() - 1);
						merged.add(new ParsedEntry(keepWord, joined));
					} else if (sameDef) {
						// byte-identical duplicate — discard silently
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
		if (mergedCount > 0)
			LOG.err(String.format("Merged/deduped %d entries (merge-defs=%s)", mergedCount, mergeDefs));
		entries = merged;

		// 5. Pack into blocks
		LOG.err("Packing...");
		var blocks = packBlocks(entries);
		LOG.err(String.format("Packed. %d blocks.", blocks.size()));

		// 5. Write .dic
		LOG.err("Writing...");
		if (dicFile.getParent() != null)
			Files.createDirectories(dicFile.getParent());
		writeDic(dicFile, name, entries.size(), collation, morphem, keyboard, blocks);

		LOG.err(String.format("%d entries written to %s.", entries.size(), ArgsHelper.displayPath(dicFile.toString())));
	}

	// ── 1. Collation table ────────────────────────────────────────────────────

	/**
	 * Parse collates.txt into a TreeMap&lt;sourceCodepoint,
	 * normalisedCodepoint&gt;. First line = ignored chars (map to 0). Subsequent
	 * lines: chars=BASE.
	 */
	static TreeMap<Integer, Integer> loadCollation(Path file) throws IOException {
		var map = new TreeMap<Integer, Integer>();
		String[] lines = Files.readString(file, StandardCharsets.UTF_8).replace("\uFEFF", "").split("\\r?\\n");
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i].strip();
			if (line.isEmpty())
				continue;
			if (i == 0) {
				// Strip trailing '=' (used as field separator on other lines)
				int eq0 = line.lastIndexOf('=');
				String chars0 = eq0 >= 0 ? line.substring(0, eq0) : line;
				chars0.codePoints().forEach(cp -> map.put(cp, 0));
			} else {
				int eq = line.lastIndexOf('=');
				if (eq < 0 || eq == line.length() - 1)
					continue;
				String chars = line.substring(0, eq);
				int base = line.codePointAt(eq + 1);
				chars.codePoints().forEach(cp -> map.put(cp, base));
				map.put(base, base);
			}
		}
		return map;
	}

	/** Normalise a word for sorting: map codepoints, drop ignored (0). */
	static String collatedKey(String word, Map<Integer, Integer> collation) {
		var sb = new StringBuilder();
		word.codePoints().forEach(cp -> {
			int n = collation.getOrDefault(cp, cp);
			if (n != 0)
				sb.appendCodePoint(n);
		});
		return sb.toString();
	}

	/**
	 * Serialise collation table: uint32 byte_count + array of uint32 packed
	 * entries. Each entry = (base_uppercase_char << 16) | source_codepoint, sorted
	 * by value. Punctuation (base=0): entry = source_codepoint only (high word =
	 * 0). Identity mappings (base == src) are excluded — the firmware only needs
	 * entries for chars that fold to a different base.
	 */
	static byte[] collationToBytes(TreeMap<Integer, Integer> map) throws IOException {
		var packed = new ArrayList<Long>();
		for (var e : map.entrySet()) {
			int src = e.getKey(), base = e.getValue();
			if (src > 0xFFFF || base > 0xFFFF)
				continue;
			if (src == base)
				continue; // skip identity mappings (base chars)
			packed.add(((long) base << 16) | src);
		}
		packed.sort(Comparator.comparingLong(v -> v & 0xFFFFL)); // sort by source codepoint
		var out = new ByteArrayOutputStream();
		writeU32(out, packed.size() * 4); // byte count prefix
		for (long v : packed)
			writeU32(out, (int) v);
		return out.toByteArray();
	}

	// ── 3. HTML → SDIC bytecodes ──────────────────────────────────────────────

	/**
	 * Convert an HTML definition string to SDIC inline bytecodes. Uses a minimal
	 * recursive-descent tokeniser — no external libraries needed.
	 */
	public static byte[] htmlToSdic(CharSequence csHtml) {

		String html = csHtml == null ? "" : csHtml.toString();

		var out = new ByteArrayOutputStream();
		int i = 0;
		int len = html.length();
		while (i < len) {
			if (html.charAt(i) == '<') {
				int end = html.indexOf('>', i);
				if (end < 0) {
					appendUtf8(out, html.substring(i));
					break;
				}
				String tag = lower(html.substring(i + 1, end).toString().trim());
				boolean closing = tag.startsWith("/");
				boolean opening = !closing;

				if (closing)
					tag = tag.substring(1).trim();
				// strip attributes
				int sp = tag.indexOf(' ');
				if (sp > 0)
					tag = tag.substring(0, sp);

				if (opening) {
					switch (tag) {
					case "b", "strong" -> {
						out.write(FMT_BOLD);
						out.write(' ');
					}
					case "i", "em" -> {
						out.write(FMT_ITALIC);
						out.write(' ');
					}
					case "br" -> {
						out.write(' ');
						out.write(FMT_NEWLINE);
					}
					case "p", "div" -> out.write(' ');
					case "li" -> out.writeBytes(FMT_BULLET);
					case "ol", "ul" -> {
						/* container — no output */ }
					}
				} else {
					switch (tag) {
					case "b", "strong", "i", "em" -> {
						out.write(FMT_END);
						out.write(' ');
					}
					case "p", "div" -> {
						out.write(' ');
						out.write(FMT_NEWLINE);
					}
					case "li" -> {
						out.write(' ');
						out.write(FMT_NEWLINE);
					}
					// </ol>, </ul> — no output
					}
				}
				i = end + 1;
			} else {
				// Text node — skip literal newlines (formatting artefacts from tab-2-xdxf)
				int next = html.indexOf('<', i);
				String text = next < 0 ? html.substring(i) : html.substring(i, next);
				text = text.replace("\n", "").replace("\r", "");
				appendUtf8(out, decodeEntities(text));
				i = next < 0 ? len : next;
			}
		}
		byte[] trimmed = trimBytes(out.toByteArray());
		// Post-process: enforce all firmware spacing rules:
		// (a) definition must start with 0x20
		// (b) 0x02, 0x03 must be preceded and followed by 0x20 (handled in tag
		// rendering)
		// (c) 0x01 must be followed by 0x20
		// (d) 0x0A must be preceded by exactly one 0x20
		// (e) definition must end with 0x20
		// (f) 0x0A must be followed by exactly one 0x20 (not two or more)
		var fixed = new ByteArrayOutputStream();
		fixed.write(' '); // (a) leading space
		for (int j = 0; j < trimmed.length; j++) {
			byte b = trimmed[j];
			// (d) insert space before 0x0A if needed
			if (b == FMT_NEWLINE && (j == 0 || trimmed[j - 1] != ' '))
				fixed.write(' ');
			fixed.write(b);
			// (c) insert space after 0x01 if needed
			if (b == FMT_END && (j + 1 >= trimmed.length || trimmed[j + 1] != ' '))
				fixed.write(' ');
			// (f) after 0x0A, skip extra spaces so exactly one follows
			if (b == FMT_NEWLINE) {
				// write exactly one space, then skip any additional spaces
				fixed.write(' ');
				while (j + 1 < trimmed.length && trimmed[j + 1] == ' ')
					j++;
			}
		}
		// (e) trailing space
		var result = fixed.toByteArray();
		if (result.length == 0 || result[result.length - 1] != ' ')
			fixed.write(' ');
		return fixed.toByteArray();
	}

	static void appendUtf8(ByteArrayOutputStream out, String s) {
		out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
	}

	static byte[] trimBytes(byte[] data) {
		int s = 0;
		while (s < data.length && (data[s] == ' ' || data[s] == '\n' || data[s] == '\r')) {
			s++;
		}
		int e = data.length - 1;
		while (e > s && (data[e] == ' ' || data[e] == '\n' || data[e] == '\r')) {
			e--;
		}
		return (s == 0 && e == data.length - 1) ? data : Arrays.copyOfRange(data, s, e + 1);
	}

	// ── 4. Block packer ───────────────────────────────────────────────────────

	/**
	 * A compressed block and the first headword it contains (UTF-8). The arrays
	 * are shared, not copied, and must not be modified; {@code equals},
	 * {@code hashCode} and {@code toString} use their contents (records would
	 * compare arrays by identity).
	 */
	record Block(byte[] firstWordUtf8, byte[] container) {
		@Override
		public boolean equals(Object o) {
			return o instanceof Block other && Arrays.equals(firstWordUtf8, other.firstWordUtf8)
					&& Arrays.equals(container, other.container);
		}

		@Override
		public int hashCode() {
			return 31 * Arrays.hashCode(firstWordUtf8) + Arrays.hashCode(container);
		}

		@Override
		public String toString() {
			return "Block[firstWord=" + new String(firstWordUtf8, StandardCharsets.UTF_8) + ", container="
					+ container.length + " bytes]";
		}
	}

	/**
	 * Largest definition (bytes) that fits in a block together with its headword:
	 * block = entry (2 length + word + 0x00 + definition + 0x00) + 2 terminator.
	 */
	static int maxDefinitionBytes(String word) {
		return TARGET_BLOCK_DECOMPRESSED - 2 - word.getBytes(StandardCharsets.UTF_8).length - 1 - 1 - 2;
	}

	/** End marker of a truncated definition. */
	static final byte[] TRUNCATED_MARK = "[...] ".getBytes(StandardCharsets.UTF_8);

	/**
	 * A definition that does not fit in one block on its own (firmware
	 * decompression buffer) is truncated and ends with "[...]"; each one is logged
	 * with the word. Entries are replaced in place.
	 */
	static void truncateOversized(List<ParsedEntry> entries) {
		for (int k = 0; k < entries.size(); k++) {
			ParsedEntry e = entries.get(k);
			int max = maxDefinitionBytes(e.word());
			if (e.definition().length > max) {
				entries.set(k, new ParsedEntry(e.word(), truncateDefinition(e.definition(), max)));
				LOG.err(String.format("Warning: definition of '%s' is %,d bytes, more than fits in one block (%,d): truncated, ending with [...]",
						e.word(), e.definition().length, max));
			}
		}
	}

	/**
	 * Truncates an SDIC definition to at most {@code max} bytes, ending with
	 * "[...] ". Cuts outside bold/italic spans, preferably at a newline
	 * ({@code 0x20 0x0A 0x20}, keeping the space before it), else after a space;
	 * never expected: at a UTF-8 character boundary, closing open spans. The
	 * result ends with 0x20, as the firmware requires.
	 */
	static byte[] truncateDefinition(byte[] def, int max) {
		int limit = max - TRUNCATED_MARK.length; // def[0, cut) + mark must fit
		int cutNewline = -1, cutSpace = -1, depth = 0;
		for (int i = 0; i < limit; i++) {
			byte b = def[i];
			if (b == FMT_BOLD || b == FMT_ITALIC) {
				depth++;
			} else if (b == FMT_END) {
				depth = Math.max(0, depth - 1);
			} else if (depth == 0 && i > 0) {
				if (b == FMT_NEWLINE && def[i - 1] == 0x20) {
					cutNewline = i; // keep the space before the newline
				} else if (b == 0x20) {
					cutSpace = i + 1; // keep the space
				}
			}
		}
		var out = new ByteArrayOutputStream(max);
		if (cutNewline > 0 || cutSpace > 0) {
			int cut = cutNewline > 0 ? cutNewline : cutSpace;
			out.write(def, 0, cut);
		} else {
			// hard cut at a UTF-8 boundary, closing open spans (0x01 must be followed by 0x20)
			int cut = limit - 2 * depth - 1;
			while (cut > 0 && (def[cut] & 0xC0) == 0x80) {
				cut--;
			}
			int open = 0;
			for (int i = 0; i < cut; i++) {
				if (def[i] == FMT_BOLD || def[i] == FMT_ITALIC) {
					open++;
				} else if (def[i] == FMT_END) {
					open = Math.max(0, open - 1);
				}
			}
			out.write(def, 0, cut);
			out.write(0x20);
			for (int i = 0; i < open; i++) {
				out.write(FMT_END);
				out.write(0x20);
			}
		}
		out.write(TRUNCATED_MARK, 0, TRUNCATED_MARK.length);
		return out.toByteArray();
	}

	static List<Block> packBlocks(List<ParsedEntry> entries) throws IOException {
		var blocks = new ArrayList<Block>();
		var current = new ArrayList<ParsedEntry>();
		int currentDsz = 0;

		for (ParsedEntry e : entries) {
			int entrySize = e.serialisedLength();
			current.add(e);
			currentDsz += entrySize;
			boolean flush = current.size() > TARGET_BLOCK_ENTRIES || // entry count hard cap
					currentDsz + 2 > TARGET_BLOCK_DECOMPRESSED || // decompressed size hard cap
					(currentDsz > 3_000 && compressBlock(current).container().length > TARGET_BLOCK_COMPRESSED); // compressed
																													// cap
			if (flush && current.size() > 1) {
				current.remove(current.size() - 1);
				currentDsz -= entrySize;
				blocks.add(compressBlock(current));
				current.clear();
				current.add(e);
				currentDsz = entrySize;
			}
		}
		if (!current.isEmpty())
			blocks.add(compressBlock(current));
		return blocks;
	}

	/** Join two definition byte arrays with a newline separator. */
	static byte[] joinDefs(byte[] a, byte[] b) {
		return joinDefsBlankLine(a, b);
	}

	// Join two definitions with a blank line: 0x0a 0x20 0x0a (satisfies all spacing
	// rules)
	static byte[] joinDefsBlankLine(byte[] a, byte[] b) {
		byte[] sep = new byte[] { FMT_NEWLINE, 0x20, FMT_NEWLINE };
		byte[] out = new byte[a.length + sep.length + b.length];
		System.arraycopy(a, 0, out, 0, a.length);
		System.arraycopy(sep, 0, out, a.length, sep.length);
		System.arraycopy(b, 0, out, a.length + sep.length, b.length);
		return out;
	}

	static Block compressBlock(List<ParsedEntry> entries) throws IOException {
		var raw = new ByteArrayOutputStream();
		for (ParsedEntry e : entries) {
			byte[] wBytes = e.word().getBytes(StandardCharsets.UTF_8);
			int len = 2 + wBytes.length + 1 + e.definition().length + 1;
			if (len > 0xFFFF)
				len = 0xFFFF;
			writeU16(raw, len);
			raw.write(wBytes);
			raw.write(0);
			raw.write(e.definition());
			raw.write(0);
		}
		// Terminator: elen=0x0000 signals end of block to firmware
		raw.write(0);
		raw.write(0);
		byte[] rawBytes = raw.toByteArray();
		byte[] compressed = deflate(rawBytes);
		return new Block(entries.get(0).word().getBytes(StandardCharsets.UTF_8), compressed);
	}

	// ── 5. Word index ─────────────────────────────────────────────────────────

	static byte[] buildIndex(List<Block> blocks) throws IOException {
		var out = new ByteArrayOutputStream();
		for (Block b : blocks) {
			int csz = b.container().length;
			writeU16(out, csz);
			out.write(b.firstWordUtf8());
			out.write(0);
		}
		out.write(0);
		out.write(0); // terminator — matches converter.exe
		return out.toByteArray();
	}

	// ── 6. .dic writer ────────────────────────────────────────────────────────

	static void writeDic(Path out, String name, int entryCount, TreeMap<Integer, Integer> collation, byte[] morphem,
			byte[] keyboard, List<Block> blocks) throws IOException {

		byte[] collBytes = collationToBytes(collation);
		byte[] morphBlock = wrapBlock(morphem);
		byte[] keyboardBlock = wrapBlock(keyboard);
		byte[] indexBlock = wrapBlock(buildIndex(blocks));

		int collationOffset = HEADER_SIZE;
		int morphemOffset = collationOffset + collBytes.length;
		int keyboardOffset = morphemOffset + morphBlock.length;
		int indexOffset = keyboardOffset + keyboardBlock.length;
		int defblocksOffset = indexOffset + indexBlock.length;

		byte[] header = buildHeader(entryCount, collationOffset, morphemOffset, keyboardOffset, indexOffset,
				defblocksOffset, name);

		try (var file = Files.newOutputStream(out); var os = new BufferedOutputStream(file)) {
			os.write(header);
			os.write(collBytes);
			os.write(morphBlock);
			os.write(keyboardBlock);
			os.write(indexBlock);
			int n = 0;
			for (Block b : blocks) {
				os.write(b.container());
				if (++n % 200 == 0)
					LOG.err(String.format("  %d / %d blocks written", n, blocks.size()));
			}
		}
	}

	static byte[] buildHeader(int entryCount, int collOff, int morphOff, int kbOff, int idxOff, int defOff,
			String name) {
		byte[] h = new byte[HEADER_SIZE];
		System.arraycopy(MAGIC, 0, h, 0x00, 4);
		putU32(h, 0x04, VERSION);
		putU32(h, 0x08, entryCount);
		putU32(h, 0x0c, UNKNOWN_0C);
		putU32(h, 0x24, collOff);
		putU32(h, 0x28, morphOff);
		putU32(h, 0x2c, kbOff);
		putU32(h, 0x38, idxOff);
		putU32(h, 0x3c, defOff);
		byte[] nb = name.getBytes(StandardCharsets.UTF_8);
		int nl = Math.min(nb.length, 63);
		System.arraycopy(nb, 0, h, 0x40, nl);
		return h;
	}

	// ── Compression helpers ───────────────────────────────────────────────────

	/** Compress raw bytes and prepend uint32_le decompressed size. */
	static byte[] wrapBlock(byte[] raw) throws IOException {
		byte[] comp = deflate(raw);
		var out = new ByteArrayOutputStream(4 + comp.length);
		writeU32(out, raw.length);
		out.write(comp);
		return out.toByteArray();
	}

	static byte[] deflate(byte[] data) throws IOException {
		var d = new Deflater(Deflater.DEFAULT_COMPRESSION, false);
		d.setInput(data);
		d.finish();
		var out = new ByteArrayOutputStream(data.length / 2 + 64);
		var buf = new byte[8192];
		while (!d.finished()) {
			int n = d.deflate(buf);
			out.write(buf, 0, n);
		}
		d.end();
		return out.toByteArray();
	}

	// ── Integer write helpers ─────────────────────────────────────────────────

	static void writeU16(OutputStream out, int v) throws IOException {
		out.write(v & 0xFF);
		out.write((v >> 8) & 0xFF);
	}

	static void writeU32(OutputStream out, int v) throws IOException {
		out.write(v & 0xFF);
		out.write((v >> 8) & 0xFF);
		out.write((v >> 16) & 0xFF);
		out.write((v >> 24) & 0xFF);
	}

	static void putU32(byte[] b, int off, int v) {
		b[off] = (byte) (v & 0xFF);
		b[off + 1] = (byte) ((v >> 8) & 0xFF);
		b[off + 2] = (byte) ((v >> 16) & 0xFF);
		b[off + 3] = (byte) ((v >> 24) & 0xFF);
	}

	// ── Morphem encoding ──────────────────────────────────────────────────────

	/** Read a text file (UTF-8, strip BOM) and re-encode as UTF-16 LE. */
	static byte[] readAsUtf16Le(Path file) throws IOException {
		// Morphem format: uppercase chars, lines separated by NUL (uint16 0x0000),
		// no trailing newline bytes — matches reference firmware expectation.
		String raw = upper(Files.readString(file, StandardCharsets.UTF_8).replace("\uFEFF", ""));
		var out = new ByteArrayOutputStream();
		for (String line : raw.split("\\r?\\n")) {
			if (line.isEmpty())
				continue;
			out.write(line.getBytes(StandardCharsets.UTF_16LE));
			out.write(0);
			out.write(0); // NUL separator (uint16 LE)
		}
		out.write(0);
		out.write(0); // extra NUL — double-NUL terminator required by firmware
		return out.toByteArray();
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
		case "--def"              -> ArgsHelper.DEF_OPTION;
		case "--in", "-i"         -> "in";
		case "--out", "-o"        -> "out";
		case "--lang", "-l"       -> "lang";
		case "--langdir", "-D"    -> "langdir";
		case "--name", "-n"       -> "name";
		case "--merge-defs", "-m" -> "merge-defs";
		default -> null;
		};
	}

	static Map<String, String> parseArgs(String[] args) throws IOException {
		return ArgsHelper.parse(args, ConvXdxf2Pbdic::optionKey, er -> LOG.err(er), () -> usage());
	}

	static final String TOOL = "xdxf-2-pbdic";
	private static final ToolLog LOG = new ToolLog(TOOL);

	static String usage() {
		return TOOL + ": " + """
				Convert an XDXF dictionary to PocketBook .dic (SDIC) format.

				Usage:
				  linux/xdxf-2-pbdic -i <xdxf> -o <dic> -l <lang> -m ALWAYS|EXACT|NEVER [options]

				Required:
				  -i / --in          Input XDXF file, or - for stdin
				  -o / --out         Output .dic file
				  -l / --lang        Language code (e.g. pt, en); selects the language files
				  -m / --merge-defs  Duplicate headword handling:
				                       ALWAYS — case-insensitive match; merge different defs, drop identical
				                       EXACT  — case-sensitive match;   merge different defs, drop identical
				                       NEVER  — never merge; only drop byte-identical duplicates

				Optional:
				  -D / --langdir     Language files dir with collates.txt, morphems.txt, keyboard.txt
				                     (default: lang/<lang>)
				  -n / --name        Dictionary title (default: name in the XDXF header,
				                     else the output path without extension)
				  -c / --config      .properties config file (namespace: xdxf2pbdic.*);
				                     command-line options override it
				  --def <name>=<value>
				                     Set or override a config variable (def); repeatable
				  -h / --help        Show this help

				Examples:
				  linux/xdxf-2-pbdic -i data/out/kaikki-pt.xdxf -o data/out/kaikki-pt.dic -l pt -m ALWAYS
				  linux/tab-2-xdxf -i data/out/kaikki-pt.tsv -o - -l pt -n "Dicionário PT" | linux/xdxf-2-pbdic -i - -o data/out/kaikki-pt.dic -l pt -m ALWAYS
				  """;
	}
}
