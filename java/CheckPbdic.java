import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/**
 * check-pbdic — validate a PocketBook .dic file against its source XDXF.
 *
 * Checks performed:
 *   1. Entry count  — total entries in .dic matches XDXF
 *   2. Headwords    — every XDXF headword is present in .dic
 *   3. Sort order   — .dic entries are ordered by collation key
 *   4. Definitions  — spot-check N sampled entries: definition text matches
 *   5. Firmware fmt — every definition starts and ends with 0x20; 0x01 followed by 0x20; 0x0A preceded by 0x20
 *   6. Block sizes  — compressed ≤ 4096 bytes; decompressed ≤ 27774 bytes; index ≤ 55000 bytes
 *
 * Usage:
 *   CheckPbdic -x data/out/dict-pt.xdxf -d data/out/dict-pt.dic -l pt
 *                     [-n 50] [-c dict.properties]
 *
 * Required:
 *   -x / --xdxf    Source XDXF file
 *   -d / --dic     PocketBook .dic file to validate
 *   -l / --lang    Language code (e.g. pt) — used to load collates.txt
 *
 * Optional:
 *   -n / --sample  Number of entries to spot-check definitions (default: 20)
 *   -D / --langdir Language files directory (default: windows/<lang>/)
 *   -c / --config  .properties config file (namespace: checkpbdic.*)
 */
class CheckPbdic {

    // ── SDIC bytecodes ────────────────────────────────────────────────────────
    static final byte FMT_END     = 0x01;
    static final byte FMT_BOLD    = 0x02;
    static final byte FMT_ITALIC  = 0x03;
    static final byte FMT_NEWLINE = 0x0a;

    // ── Entry ─────────────────────────────────────────────────────────────────
    record Entry(String word, String definition) {}

    // ── Result tracking ───────────────────────────────────────────────────────
    static int passed = 0, failed = 0, warned = 0;

    static void pass(String msg)  { err("  [PASS] " + msg); passed++; }
    static void fail(String msg)  { err("  [FAIL] " + msg); failed++; }
    static void warn(String msg)  { err("  [WARN] " + msg); warned++; }
    static void head(String msg)  { err("\n── " + msg + " ──"); }
    static void err(String msg)   { System.err.println(msg); }

    // ── main ──────────────────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || Arrays.asList(args).contains("--help")) {
            printUsage(); System.exit(0);
        }

        var config = parseArgs(args);
        String cp      = config.get("config");
        String xdxfPath = require(config, "xdxf",  "--xdxf  : source XDXF file");
        String dicPath  = require(config, "dic",   "--dic   : .dic file to validate");
        String lang     = require(config, "lang",  "--lang  : language code, e.g. pt");
        String langDir  = config.getOrDefault("langdir", "windows/" + lang);
        int    sample   = Integer.parseInt(config.getOrDefault("sample", "20"));

        Path xdxfFile    = Path.of(xdxfPath);
        Path dicFile     = Path.of(dicPath);
        Path langDirPath = Path.of(langDir);
        Path collatesFile = langDirPath.resolve("collates.txt");

        for (Path p : List.of(xdxfFile, dicFile, collatesFile)) {
            if (!Files.exists(p)) { err("Error: file not found: " + p); System.exit(1); }
        }

        err("check-pbdic");
        err("  xdxf    : " + xdxfFile);
        err("  dic     : " + dicFile);
        err("  lang    : " + lang);
        err("  langdir : " + langDirPath);
        err("  sample  : " + sample);
        if (cp != null) err("  config  : " + cp);
        err("");

        // Load collation
        var collation = loadCollation(collatesFile);

        // Parse XDXF
        err("Reading XDXF...");
        var xdxfEntries = parseXdxf(xdxfFile);
        err("Read. " + xdxfEntries.size() + " entries.");

        // Parse .dic
        err("Reading .dic...");
        var dicEntries = parseDic(dicFile);
        err("Read. " + dicEntries.size() + " entries.");
        err("");

        // ── Check 1: Entry count ──────────────────────────────────────────────
        head("Check 1: Entry count");
        int xdxfCount = xdxfEntries.size();
        int dicCount  = dicEntries.size();
        if (xdxfCount == dicCount) {
            pass("Entry count matches: " + dicCount);
        } else {
            fail("Count mismatch — XDXF: " + xdxfCount + ", .dic: " + dicCount
                 + " (delta: " + (dicCount - xdxfCount) + ")");
        }

        // ── Check 2: Headwords ────────────────────────────────────────────────
        head("Check 2: Headwords");
        var dicWords = new HashSet<String>();
        for (Entry e : dicEntries) dicWords.add(e.word());

        int missing = 0;
        var missingWords = new ArrayList<String>();
        for (Entry e : xdxfEntries) {
            if (!dicWords.contains(e.word())) {
                missing++;
                if (missingWords.size() < 10) missingWords.add(e.word());
            }
        }
        if (missing == 0) {
            pass("All " + xdxfCount + " XDXF headwords found in .dic.");
        } else {
            fail(missing + " XDXF headwords missing from .dic.");
            missingWords.forEach(w -> err("         missing: " + repr(w)));
            if (missing > 10) err("         ... and " + (missing - 10) + " more.");
        }

        // ── Check 3: Sort order ───────────────────────────────────────────────
        head("Check 3: Sort order");
        int outOfOrder = 0;
        String prevKey = "";
        String prevWord = "";
        var outOfOrderExamples = new ArrayList<String>();
        for (Entry e : dicEntries) {
            String key = collatedKey(e.word(), collation);
            if (key.compareTo(prevKey) < 0) {
                outOfOrder++;
                if (outOfOrderExamples.size() < 5)
                    outOfOrderExamples.add(repr(prevWord) + " > " + repr(e.word()));
            }
            prevKey  = key;
            prevWord = e.word();
        }
        if (outOfOrder == 0) {
            pass("All " + dicCount + " entries are in correct collation order.");
        } else {
            fail(outOfOrder + " sort order violation(s).");
            outOfOrderExamples.forEach(ex -> err("         " + ex));
        }

        // ── Check 4: Spot-check definitions ───────────────────────────────────
        head("Check 4: Definitions (sample " + sample + " entries)");

        // Build XDXF lookup map
        var xdxfMap = new LinkedHashMap<String, String>();
        for (Entry e : xdxfEntries) xdxfMap.put(e.word(), e.definition());

        // Build .dic lookup map
        var dicMap = new LinkedHashMap<String, String>();
        for (Entry e : dicEntries) dicMap.put(e.word(), e.definition());

        // Pick a deterministic random sample from XDXF entries that exist in dic
        var candidates = xdxfEntries.stream()
            .filter(e -> dicMap.containsKey(e.word()))
            .toList();
        var rng = new Random(42);
        int step = Math.max(1, candidates.size() / sample);
        int checked = 0, defMismatches = 0;
        var mismatchExamples = new ArrayList<String>();

        for (int i = 0; i < candidates.size() && checked < sample; i += step) {
            Entry xe = candidates.get(i);
            String dicDef  = stripFmt(dicMap.get(xe.word()));
            String xdxfDef = stripFmt(htmlToText(xe.definition()));
            if (!dicDef.equals(xdxfDef)) {
                defMismatches++;
                if (mismatchExamples.size() < 3) {
                    mismatchExamples.add(repr(xe.word()));
                    mismatchExamples.add("  xdxf: " + truncate(xdxfDef, 80));
                    mismatchExamples.add("  .dic: " + truncate(dicDef,  80));
                }
            }
            checked++;
        }

        if (defMismatches == 0) {
            pass("All " + checked + " sampled definitions match.");
        } else {
            warn(defMismatches + " / " + checked + " sampled definitions differ.");
            mismatchExamples.forEach(ex -> err("         " + ex));
        }

        // ── Check 5: Firmware format constraints ──────────────────────────────
        head("Check 5: Firmware format constraints");
        int noLeadingSpace = 0;
        int noTrailingSpace = 0;
        int endSpanNoSpace = 0;
        int newlineWithoutSpace = 0;
        int doubleSpaceAfterNewline = 0;
        var noSpaceExamples         = new ArrayList<String>();
        var noTrailExamples         = new ArrayList<String>();
        var endSpanExamples         = new ArrayList<String>();
        var newlineExamples         = new ArrayList<String>();
        var doubleSpaceExamples     = new ArrayList<String>();

        for (Entry e : dicEntries) {
            byte[] def = e.definition().getBytes(StandardCharsets.UTF_8);
            if (def.length == 0 || def[0] != 0x20) {
                noLeadingSpace++;
                if (noSpaceExamples.size() < 3) noSpaceExamples.add(repr(e.word()));
            }
            if (def.length == 0 || def[def.length-1] != 0x20) {
                noTrailingSpace++;
                if (noTrailExamples.size() < 3) noTrailExamples.add(repr(e.word()));
            }
            for (int j = 0; j < def.length - 1; j++) {
                if (def[j] == FMT_END && def[j+1] != 0x20) {
                    endSpanNoSpace++;
                    if (endSpanExamples.size() < 3) endSpanExamples.add(repr(e.word()));
                    break;
                }
            }
            for (int j = 1; j < def.length; j++) {
                if (def[j] == FMT_NEWLINE && def[j-1] != 0x20) {
                    newlineWithoutSpace++;
                    if (newlineExamples.size() < 3) newlineExamples.add(repr(e.word()));
                    break;
                }
            }
            for (int j = 0; j < def.length - 2; j++) {
                if (def[j] == FMT_NEWLINE && def[j+1] == 0x20 && def[j+2] == 0x20) {
                    doubleSpaceAfterNewline++;
                    if (doubleSpaceExamples.size() < 3) doubleSpaceExamples.add(repr(e.word()));
                    break;
                }
            }
        }

        if (noLeadingSpace == 0) pass("All definitions start with 0x20 (leading space).");
        else { fail(noLeadingSpace + " definition(s) missing leading 0x20."); noSpaceExamples.forEach(w -> err("         " + w)); }

        if (noTrailingSpace == 0) pass("All definitions end with 0x20 (trailing space).");
        else { fail(noTrailingSpace + " definition(s) missing trailing 0x20."); noTrailExamples.forEach(w -> err("         " + w)); }

        if (endSpanNoSpace == 0) pass("All 0x01 (end-span) bytes are followed by 0x20 (space).");
        else { fail(endSpanNoSpace + " definition(s) have 0x01 not followed by 0x20."); endSpanExamples.forEach(w -> err("         " + w)); }

        if (newlineWithoutSpace == 0) pass("All 0x0A (newline) bytes are preceded by 0x20 (space).");
        else { fail(newlineWithoutSpace + " definition(s) have 0x0A not preceded by 0x20."); newlineExamples.forEach(w -> err("         " + w)); }

        if (doubleSpaceAfterNewline == 0) pass("All 0x0A (newline) bytes are followed by exactly one 0x20.");
        else { fail(doubleSpaceAfterNewline + " definition(s) have 0x0A followed by two or more 0x20 bytes."); doubleSpaceExamples.forEach(w -> err("         " + w)); }

        // ── Check 6: Block size limits ────────────────────────────────────────
        head("Check 6: Block size limits");
        {
            byte[] data = Files.readAllBytes(dicFile);
            int defblocksOffset = readU32(data, 0x3c);
            int indexOffset     = readU32(data, 0x38);
            int indexDsz        = readU32(data, indexOffset);
            byte[] idxRaw;
            try {
                var inf = new Inflater(false);
                inf.setInput(data, indexOffset + 4, defblocksOffset - indexOffset - 4);
                idxRaw = new byte[indexDsz]; inf.inflate(idxRaw); inf.end();
            } catch (Exception ex) {
                fail("Could not decompress index: " + ex.getMessage());
                idxRaw = new byte[0];
            }
            int MAX_INDEX_DSZ = 55_000;
            int MAX_BLOCK_CSZ = 4_096;
            int MAX_BLOCK_DSZ = 27_774; // observed max in converter.exe output

            if (indexDsz > MAX_INDEX_DSZ)
                fail(String.format("Index decompressed size %,d exceeds firmware limit ~%,d.", indexDsz, MAX_INDEX_DSZ));
            else
                pass(String.format("Index decompressed size %,d is within firmware limit ~%,d.", indexDsz, MAX_INDEX_DSZ));

            int pos = defblocksOffset; int ii = 0;
            int cszOver = 0, dszOver = 0, maxCsz = 0, maxDsz = 0, blocks = 0;
            while (ii < idxRaw.length - 2) {
                int csz = readU16(idxRaw, ii);
                int nul = indexOf(idxRaw, (byte)0, ii + 2); if (nul < 0) break;
                ii = nul + 1;
                if (pos + csz > data.length) break;
                try {
                    var inf = new Inflater(false);
                    inf.setInput(data, pos, csz);
                    var baos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096]; int n;
                    while ((n = inf.inflate(buf)) > 0) baos.write(buf, 0, n);
                    inf.end();
                    int dsz = baos.size();
                    maxCsz = Math.max(maxCsz, csz);
                    maxDsz = Math.max(maxDsz, dsz);
                    if (csz > MAX_BLOCK_CSZ) cszOver++;
                    if (dsz > MAX_BLOCK_DSZ) dszOver++;
                    blocks++;
                } catch (Exception ex) { break; }
                pos += csz;
            }
            if (cszOver == 0)
                pass(String.format("All %,d blocks have compressed size ≤ %,d (max observed: %,d).", blocks, MAX_BLOCK_CSZ, maxCsz));
            else
                warn(String.format("%,d / %,d blocks exceed compressed size %,d (max: %,d). Single-entry blocks may legitimately exceed this.", cszOver, blocks, MAX_BLOCK_CSZ, maxCsz));
            if (dszOver == 0)
                pass(String.format("All %,d blocks have decompressed size ≤ %,d (max observed: %,d).", blocks, MAX_BLOCK_DSZ, maxDsz));
            else
                fail(String.format("%,d / %,d blocks exceed decompressed size %,d (max: %,d). Firmware decompression buffer will overflow.", dszOver, blocks, MAX_BLOCK_DSZ, maxDsz));
        }

        // ── Summary ───────────────────────────────────────────────────────────
        err("");
        err("────────────────────────────────────");
        int total = passed + failed + warned;
        err(String.format("Result: %d passed, %d failed, %d warnings  (%d checks)",
                          passed, failed, warned, total));
        err("────────────────────────────────────");
        System.exit(failed > 0 ? 1 : 0);
    }

    // ── .dic parser ───────────────────────────────────────────────────────────

    static List<Entry> parseDic(Path dicFile) throws IOException {
        byte[] data = Files.readAllBytes(dicFile);

        // Read header fields
        int defblocksOffset = readU32(data, 0x3c);
        int entryCount      = readU32(data, 0x08);

        // Read word index to get compressed sizes for each def block
        int indexOffset  = readU32(data, 0x38);
        int indexDsz     = readU32(data, indexOffset);
        byte[] idxRaw;
        try {
            var inf = new Inflater(false);
            inf.setInput(data, indexOffset + 4, defblocksOffset - indexOffset - 4);
            idxRaw = new byte[indexDsz];
            inf.inflate(idxRaw); inf.end();
        } catch (Exception e) {
            throw new IOException("Failed to decompress word index: " + e.getMessage());
        }
        // Parse index: u16 csz + word\0 per block
        var blockSizes = new ArrayList<Integer>();
        int ii = 0;
        while (ii < idxRaw.length - 2) {
            int csz = readU16(idxRaw, ii);
            int nul = indexOf(idxRaw, (byte)0, ii + 2);
            if (nul < 0) break;
            blockSizes.add(csz);
            ii = nul + 1;
        }

        // Scan def blocks — raw zlib streams (no dsz prefix)
        var entries = new ArrayList<Entry>(entryCount);
        int pos = defblocksOffset;

        for (int bi = 0; bi < blockSizes.size() && pos < data.length; bi++) {
            int csz = blockSizes.get(bi);
            if (pos + csz > data.length) break;

            // Decompress
            byte[] chunk;
            try {
                var inf = new Inflater(false);
                inf.setInput(data, pos, csz);
                var baos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = inf.inflate(buf)) > 0) baos.write(buf, 0, n);
                inf.end();
                chunk = baos.toByteArray();
                pos += csz;
            } catch (Exception e) {
                break;
            }

            // Parse entries in chunk: uint16 len | word\0 | def\0
            int ci = 0;
            while (ci < chunk.length - 2) {
                int elen = readU16(chunk, ci);
                if (elen < 4 || ci + elen > chunk.length) break;
                int nul = indexOf(chunk, (byte)0, ci + 2);
                if (nul < 0 || nul >= ci + elen) break;
                String word = new String(chunk, ci + 2, nul - (ci + 2), StandardCharsets.UTF_8);
                String def  = new String(chunk, nul + 1, ci + elen - nul - 2, StandardCharsets.UTF_8);
                entries.add(new Entry(word, def));
                ci += elen;
            }
        }
        return entries;
    }

    // ── XDXF parser ──────────────────────────────────────────────────────────

    static List<Entry> parseXdxf(Path xdxfFile) throws Exception {
        var factory = SAXParserFactory.newInstance();
        factory.setValidating(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        var entries   = new ArrayList<Entry>();
        var headwords = new ArrayList<String>();
        var defRaw    = new StringBuilder();
        var kBuf      = new StringBuilder();
        var state     = new boolean[2]; // [0]=inAr, [1]=inK

        factory.newSAXParser().parse(xdxfFile.toFile(), new DefaultHandler() {
            @Override public void startElement(String u, String l, String qName, Attributes a) {
                switch (qName.toLowerCase()) {
                    case "ar" -> { state[0]=true; headwords.clear(); defRaw.setLength(0); }
                    case "k"  -> { state[1]=true; kBuf.setLength(0); }
                    default   -> { if (state[0] && !state[1]) defRaw.append('<').append(qName.toLowerCase()).append('>'); }
                }
            }
            @Override public void endElement(String u, String l, String qName) {
                switch (qName.toLowerCase()) {
                    case "ar" -> {
                        if (!headwords.isEmpty() && defRaw.length() > 0) {
                            String def = defRaw.toString().trim();
                            for (String w : headwords)
                                if (!w.isBlank()) entries.add(new Entry(w.trim(), def));
                        }
                        state[0] = false;
                    }
                    case "k"  -> { headwords.add(kBuf.toString()); state[1] = false; }
                    default   -> { if (state[0] && !state[1]) defRaw.append("</").append(qName.toLowerCase()).append('>'); }
                }
            }
            @Override public void characters(char[] ch, int start, int length) {
                if (state[1]) kBuf.append(ch, start, length);
                else if (state[0]) {
                    String t = new String(ch, start, length);
                    defRaw.append(t.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;"));
                }
            }
            @Override public InputSource resolveEntity(String pub, String sys) {
                return new InputSource(new StringReader(""));
            }
        });
        return entries;
    }

    // ── Collation ─────────────────────────────────────────────────────────────

    static TreeMap<Integer,Integer> loadCollation(Path file) throws IOException {
        var map = new TreeMap<Integer,Integer>();
        String[] lines = Files.readString(file, StandardCharsets.UTF_8)
                              .replace("\uFEFF","").split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) continue;
            if (i == 0) {
                line.codePoints().forEach(cp -> map.put(cp, 0));
            } else {
                int eq = line.lastIndexOf('=');
                if (eq < 0 || eq == line.length()-1) continue;
                int base = line.codePointAt(eq+1);
                line.substring(0, eq).codePoints().forEach(cp -> map.put(cp, base));
                map.put(base, base);
            }
        }
        return map;
    }

    static String collatedKey(String word, Map<Integer,Integer> col) {
        var sb = new StringBuilder();
        word.codePoints().forEach(cp -> {
            int n = col.getOrDefault(cp, cp);
            if (n != 0) sb.appendCodePoint(n);
        });
        return sb.toString();
    }

    // ── Text helpers ──────────────────────────────────────────────────────────

    /** Strip SDIC formatting bytes (0x01–0x03), keep text and newlines. */
    static String stripFmt(String s) {
        var sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != FMT_END && c != FMT_BOLD && c != FMT_ITALIC)
                sb.append(c);
        }
        return sb.toString().strip();
    }

    /** Strip HTML tags from an XDXF definition, decode entities. */
    static String htmlToText(String html) {
        return html.replaceAll("<[^>]+>", " ")
                   .replace("&amp;",  "&")
                   .replace("&lt;",   "<")
                   .replace("&gt;",   ">")
                   .replace("&quot;", "\"")
                   .replace("&nbsp;", " ")
                   .replaceAll("\\s+", " ")
                   .strip();
    }

    static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    static String repr(String s) {
        return "\"" + s.replace("\"", "\\\"") + "\"";
    }

    // ── Binary helpers ────────────────────────────────────────────────────────

    static int readU32(byte[] b, int off) {
        return  (b[off]   & 0xFF)        |
               ((b[off+1] & 0xFF) <<  8) |
               ((b[off+2] & 0xFF) << 16) |
               ((b[off+3] & 0xFF) << 24);
    }

    static int readU16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off+1] & 0xFF) << 8);
    }

    static int indexOf(byte[] data, byte val, int from) {
        for (int i = from; i < data.length; i++)
            if (data[i] == val) return i;
        return -1;
    }

    // ── Config / CLI ──────────────────────────────────────────────────────────

    static Map<String,String> parseArgs(String[] args) throws IOException {
        var cli = new LinkedHashMap<String,String>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config",  "-c" -> cli.put("config",  args[++i]);
                case "--xdxf",    "-x" -> cli.put("xdxf",   args[++i]);
                case "--dic",     "-d" -> cli.put("dic",     args[++i]);
                case "--lang",    "-l" -> cli.put("lang",    args[++i]);
                case "--langdir", "-D" -> cli.put("langdir", args[++i]);
                case "--sample",  "-n" -> cli.put("sample",  args[++i]);
                default -> err("Warning: unknown argument '" + args[i] + "', ignoring.");
            }
        }
        var props = new Properties();
        String cp = cli.get("config");
        if (cp != null) {
            Path p = Path.of(cp);
            if (!Files.exists(p)) { err("Error: config not found: " + p); System.exit(1); }
            try (var r = new java.io.InputStreamReader(new FileInputStream(p.toFile()), StandardCharsets.UTF_8)) { props.load(r); }
        }
        String NS = "checkpbdic.";
        for (String key : List.of("xdxf","dic","lang","langdir","sample")) {
            if (!cli.containsKey(key)) {
                String v = props.getProperty(NS + key);
                if (v == null) v = props.getProperty(key);
                if (v != null) cli.put(key, v);
            }
        }
        return cli;
    }

    static String require(Map<String,String> config, String key, String desc) {
        String v = config.get(key);
        if (v == null) { err("Error: missing required argument " + desc); System.exit(1); }
        return v;
    }

    static void printUsage() {
        err("check-pbdic — validate a PocketBook .dic against its XDXF source\n");
        err("Usage:");
        err("  linux/check-pbdic -x <xdxf> -d <dic> -l <lang> [options]\n");
        err("Required:");
        err("  -x / --xdxf     Source XDXF file");
        err("  -d / --dic      .dic file to validate");
        err("  -l / --lang     Language code (e.g. pt, en)\n");
        err("Optional:");
        err("  -n / --sample   Entries to spot-check definitions (default: 20)");
        err("  -D / --langdir  Language files dir (default: windows/<lang>/)");
        err("  -c / --config   .properties config file (namespace: checkpbdic.*)\n");
        err("Exit code: 0 = all checks passed, 1 = one or more checks failed.");
    }
}
