import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Converts a tabfile (TSV) dictionary to XDXF for use with {@code xdxf-2-pbdic}.
 *
 * <br><b>Usage:</b>
 * <pre>
 *   ./tab-2-xdxf --in|-i         &lt;input.tsv|-&gt;
 *                --out|-o         &lt;output.xdxf|-&gt;
 *                --lang|-l        &lt;ISO 639-1 or 639-2&gt;
 *                --name|-n        &lt;dictionary name&gt;
 *                [--no-validate|-V]
 *                [--config|-c     &lt;config.properties&gt;]
 * </pre>
 *
 * <br><b>Examples:</b>
 * <pre>
 *   ./tab-2-xdxf -i data/out/dict-pt-pt.tsv -o data/out/dict-pt-pt.xdxf -l pt -n "Dicionário PT-PT"
 *   ./tab-2-xdxf -i - -o data/out/kaikki-pt.xdxf -l pt -n "Dicionário PT (Kaikki)"  # stdin → file
 *   ./tab-2-xdxf -i - -o - -l pt -n "Kaikki PT"                                     # stdin → stdout
 *   ./tab-2-xdxf -c dict.properties
 *   kaikki-2-tab -c dict.properties | tab-2-xdxf -c dict.properties | xdxf-2-pbdic -c dict.properties
 * </pre>
 *
 * <br><b>Stdin/stdout:</b> use {@code -i -} to read from stdin and {@code -o -} to write to stdout.
 * When writing to stdout, XML is validated via a temporary file before streaming. Use
 * {@code --no-validate} ({@code -V}) to skip validation entirely.
 * All progress messages are written to stderr so they do not contaminate the pipe.
 *
 * <br><b>Validation:</b> output XML is validated with Java's SAX parser after writing.
 * If validation fails, the tool reports the line and column number, deletes the output, and
 * exits with code 1. Use {@code --no-validate} to skip validation.
 *
 * <br><b>Config file</b> ({@code .properties} format, namespace {@code tab2xdxf.*} — CLI args override):
 * <pre>
 *   tab2xdxf.in=data/kaikki-pt.tsv
 *   tab2xdxf.out=-
 *   tab2xdxf.lang=pt
 *   tab2xdxf.name=Dicionário PT (Kaikki)
 *   tab2xdxf.novalidate=true
 * </pre>
 *
 * <br><b>Tabfile format:</b> one entry per line: {@code word&lt;TAB&gt;definition}.
 * Lines starting with {@code #} and blank lines are ignored.
 * Definitions may contain HTML — it is cleaned and made XML-safe before writing.
 *
 * <br><b>Processing pipeline:</b>
 * <ol>
 *   <li>Reads TSV input line by line</li>
 *   <li>Cleans HTML definitions:
 *     <ul>
 *       <li>Strips all tag attributes</li>
 *       <li>Whitelists safe tags: {@code p, b, i, em, strong, ol, ul, li,
 *           sub, sup, br, a, div, span, table, tr, td, th}</li>
 *       <li>Self-closes void elements: {@code <br/>}, {@code <hr/>}, {@code <img/>}</li>
 *       <li>Escapes bare {@code &}, {@code <}, {@code >} characters</li>
 *       <li>Replaces non-breaking spaces ({@code \u00a0}) with regular spaces</li>
 *     </ul>
 *   </li>
 *   <li>Formats definitions for readability on the e-reader</li>
 *   <li>Writes valid XDXF with one {@code <ar>} entry per line</li>
 *   <li>Validates output XML with Java's SAX parser (unless {@code --no-validate})</li>
 * </ol>
 *
 * <br><b>Requirements:</b> Java 17+ JDK ({@code java} must be on PATH)
 */
public class ConvTab2Xdxf {

    static final String TOOL = "tab-2-xdxf";

    static void err(String msg) { System.err.println(TOOL + ": " + msg); }
    static void errRaw(String msg) { System.err.println(msg); }

    // ISO 639-1 → ISO 639-2 mapping
    private static final Map<String, String> ISO1_TO_ISO2 = Map.ofEntries(
        Map.entry("pt", "POR"), Map.entry("en", "ENG"), Map.entry("es", "SPA"),
        Map.entry("fr", "FRA"), Map.entry("de", "DEU"), Map.entry("it", "ITA"),
        Map.entry("nl", "NLD"), Map.entry("ru", "RUS"), Map.entry("zh", "ZHO"),
        Map.entry("ja", "JPN"), Map.entry("ar", "ARA"), Map.entry("pl", "POL"),
        Map.entry("sv", "SWE"), Map.entry("da", "DAN"), Map.entry("fi", "FIN"),
        Map.entry("nb", "NOB"), Map.entry("cs", "CES"), Map.entry("hu", "HUN"),
        Map.entry("ro", "RON"), Map.entry("tr", "TUR")
    );

    private static final Set<String> ALLOWED_TAGS = Set.of(
        "p","b","i","em","strong","ol","ul","li",
        "sub","sup","br","a","div","span","table","tr","td","th"
    );
    private static final Set<String> VOID_TAGS = Set.of("br","hr","img");

    record Entry(String word, String definition) {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || Arrays.asList(args).contains("--help")) {
            printUsage(); System.exit(0);
        }

        var cli = parseArgs(args);

        var config = new Properties();
        String configPath = cli.get("config");
        if (configPath != null) {
            Path cp = Path.of(configPath);
            if (!Files.exists(cp)) {
                err(String.format("Error: config file not found: %s", cp));
                System.exit(1);
            }
            try (var r = new java.io.InputStreamReader(new FileInputStream(cp.toFile()), StandardCharsets.UTF_8)) { config.load(r); }
        }

        // -i - must be explicit on CLI; never read from config
        String input  = resolve(cli, config, "in");
        String output = resolve(cli, config, "out");
        String lang   = resolve(cli, config, "lang");
        String name   = resolve(cli, config, "name");

        var missing = new ArrayList<String>();
        if (input  == null) missing.add("--in/-i");
        if (output == null) missing.add("--out/-o");
        if (lang   == null) missing.add("--lang/-l");
        if (name   == null) missing.add("--name/-n");

        if (!missing.isEmpty()) {
            err(String.format("Error: missing mandatory parameters: %s", String.join(", ", missing)));
            printUsage();
            System.exit(1);
        }

        boolean fromStdin   = "-".equals(input);
        boolean toStdout    = "-".equals(output);
        boolean noValidate  = "true".equals(config.get("novalidate"));

        Path   outFile = toStdout ? null : Path.of(output);
        String lang639 = toIso6392(lang);

        if (!fromStdin && !Files.exists(Path.of(input))) {
            err(String.format("Error: file not found: %s", input));
            System.exit(1);
        }

String cp = configPath;
        err(TOOL);
        errRaw(String.format("  input   : %s", (fromStdin ? "<stdin>" : input)));
        errRaw(String.format("  output  : %s", (toStdout ? "<stdout>" : outFile)));
        errRaw(String.format("  lang    : %s", lang639));
        errRaw(String.format("  name    : %s", name));
        if (noValidate) errRaw("  validate: no");
        else if (toStdout) errRaw("  validate: yes (via temp file)");
        else errRaw("  validate: yes");
        if (cp != null) errRaw(String.format("  config  : %s", cp));
        errRaw("");

        var entries = fromStdin ? readTabfile(System.in) : readTabfile(new FileInputStream(input));
        if (toStdout && !noValidate) {
            // Write to temp file, validate, then stream to stdout
            Path tmp = Files.createTempFile("tab2xdxf-", ".xdxf");
            try {
                writeXdxf(entries, name, lang639, tmp);
                validateXml(tmp);  // exits on failure, deletes tmp
                try (var in = new java.io.FileInputStream(tmp.toFile())) {
                    in.transferTo(System.out);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } else if (toStdout) {
            writeXdxf(entries, name, lang639, System.out);
        } else {
            writeXdxf(entries, name, lang639, outFile);
            if (!noValidate) validateXml(outFile);
        }
    }

    // --- Tabfile reader ---

    static List<Entry> readTabfile(InputStream stream) throws Exception {
        var entries = new ArrayList<Entry>();
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                if (line.isBlank() || line.startsWith("#")) continue;
                int tab = line.indexOf('\t');
                if (tab == -1) {
                    err(String.format("Warning: line %d has no tab, skipping.", lineNum));
                    continue;
                }
                entries.add(new Entry(line.substring(0, tab).strip(), line.substring(tab + 1)));
            }
        }
        err(String.format("read %d entries.", entries.size()));
        return entries;
    }

    // --- XDXF writer ---

    static void writeXdxf(List<Entry> entries, String bookname, String lang, Path outFile) throws Exception {
        try (var out = new FileOutputStream(outFile.toFile())) {
            writeXdxf(entries, bookname, lang, out);
        }
        err(String.format("%d entries written to %s.", entries.size(), outFile));
    }

    static void writeXdxf(List<Entry> entries, String bookname, String lang, OutputStream stream) throws Exception {
        int count = 0;
        try (var writer = new BufferedWriter(new OutputStreamWriter(
                stream, StandardCharsets.UTF_8))) {

            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            writer.write("<xdxf lang_from=\"" + lang + "\" lang_to=\"" + lang + "\" format=\"visual\">\n");
            writer.write("<full_name>" + escapeXml(bookname) + "</full_name>\n");
            writer.write("<description>" + escapeXml(bookname) + "</description>\n");

            for (Entry entry : entries) {
                String def = cleanHtml(entry.definition());
                def = def.replace("\n", " ");
                // Auto-close unclosed <li> (HTML implicit closing behaviour)
                def = def.replace("<li><li>",   "<li></li><li>");
                def = def.replace("<li></ul>",  "<li></li></ul>");
                def = def.replace("<li></ol>",  "<li></li></ol>");
                def = def.replace("</li><li>", "</li>\n<li>");
                def = def.replace("<ol><li>",  "<ol>\n<li>");
                def = def.replace("<ul><li>",  "<ul>\n<li>");
                def = def.replace("</ol><li>", "</ol>\n<li>");
                def = def.replace("<li>", "<li>• ");
                def = insertNewlineBeforeP(def);

                writer.write("<ar><k>" + escapeXml(entry.word()) + "</k>" + def + "</ar>\n");
                count++;
            }
            writer.write("</xdxf>\n");
        }
    }

    // --- XML validation ---

    static void validateXml(Path outFile) throws Exception {
        try {
            var factory = javax.xml.parsers.SAXParserFactory.newInstance();
            var handler = new org.xml.sax.helpers.DefaultHandler() {
                @Override
                public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
                @Override
                public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            };
            factory.newSAXParser().parse(outFile.toFile(), handler);
            err("XML validation: OK");
        } catch (org.xml.sax.SAXParseException e) {
            err(String.format("XML validation FAILED at line %d, column %d: %s", e.getLineNumber(), e.getColumnNumber(), e.getMessage()));
            try { java.nio.file.Files.deleteIfExists(outFile); } catch (Exception ignored) {}
            err("Output file deleted.");
            System.exit(1);
        } catch (Exception e) {
            err(String.format("XML validation FAILED: %s", e.getMessage()));
            try { java.nio.file.Files.deleteIfExists(outFile); } catch (Exception ignored) {}
            err("Output file deleted.");
            System.exit(1);
        }
    }

    // --- HTML cleaner ---

    static String insertNewlineBeforeP(String s) {
        var sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int pIdx = s.indexOf("<p>", i);
            if (pIdx == -1) { sb.append(s, i, s.length()); break; }
            sb.append(s, i, pIdx);
            if (pIdx > 0 && sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n')
                sb.append('\n');
            sb.append("<p>");
            i = pIdx + 3;
        }
        return sb.toString();
    }

    static String cleanHtml(String html) {
        html = html.replace("&nbsp;",  " ")
                   .replace("&emsp;",  " ")
                   .replace("&ensp;",  " ")
                   .replace("&thinsp;"," ")
                   .replace("&mdash;", "—")
                   .replace("&ndash;", "–")
                   .replace("&laquo;", "«")
                   .replace("&raquo;", "»")
                   .replace("&ldquo;", "\u201C")
                   .replace("&rdquo;", "\u201D")
                   .replace("&lsquo;", "\u2018")
                   .replace("&rsquo;", "\u2019")
                   .replace("&hellip;","…")
                   .replace("&bull;",  "•")
                   .replace("&middot;","·")
                   .replace("&times;", "×")
                   .replace("&divide;","÷")
                   .replace("&deg;",   "°")
                   .replace("&acute;", "´")
                   .replace("&cedil;", "¸");
        var sb = new StringBuilder(html.length());
        int i = 0;
        int pCount = 0;
        while (i < html.length()) {
            char c = html.charAt(i);
            if (c == '<') {
                int end = html.indexOf('>', i);
                if (end == -1) { sb.append("&lt;"); i++; continue; }
                String inner    = html.substring(i + 1, end).trim();
                boolean closing = inner.startsWith("/");
                String tagName  = (closing ? inner.substring(1) : inner)
                    .split("[\\s/]")[0].toLowerCase();

                if (ALLOWED_TAGS.contains(tagName)) {
                    if (VOID_TAGS.contains(tagName)) {
                        sb.append("<").append(tagName).append("/>");
                    } else if (closing) {
                        sb.append("</").append(tagName).append(">");
                    } else {
                        if (tagName.equals("p")) {
                            if (pCount > 0) sb.append('\n');
                            pCount++;
                        }
                        sb.append("<").append(tagName).append(">");
                    }
                }
                i = end + 1;
            } else if (c == '&') {
                int semi    = html.indexOf(';', i);
                int nextTag = html.indexOf('<', i);
                if (semi != -1 && (nextTag == -1 || semi < nextTag) && semi - i < 12) {
                    String entity = html.substring(i, semi + 1);
                    if (entity.startsWith("&#") || entity.equals("&amp;") || entity.equals("&lt;")
                            || entity.equals("&gt;") || entity.equals("&quot;") || entity.equals("&apos;")) {
                        sb.append(entity);
                    } else {
                        sb.append("&amp;").append(entity, 1, entity.length());
                    }
                    i = semi + 1;
                } else {
                    sb.append("&amp;");
                    i++;
                }
            } else {
                int next = i + 1;
                while (next < html.length() && html.charAt(next) != '<' && html.charAt(next) != '&') next++;
                String text = html.substring(i, next)
                    .replace("\u00a0", " ")
                    .replace("\r", "");
                sb.append(escapeXmlText(text));
                i = next;
            }
        }
        return sb.toString();
    }

    // --- Helpers ---

    static String toIso6392(String lang) {
        if (lang == null) return "ENG";
        if (lang.length() == 2) {
            String mapped = ISO1_TO_ISO2.get(lang.toLowerCase());
            return mapped != null ? mapped : lang.toUpperCase();
        }
        return lang.toUpperCase();
    }

    static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String escapeXmlText(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static Map<String, String> parseArgs(String[] args) {
        var map = new LinkedHashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config", "-c" -> map.put("config", args[++i]);
                case "--in",    "-i"  -> map.put("in",     args[++i]);
                case "--out",   "-o"  -> map.put("out",    args[++i]);
                case "--lang",  "-l"  -> map.put("lang",   args[++i]);
                case "--name",        "-n" -> map.put("name",      args[++i]);
                case "--no-validate", "-V" -> map.put("novalidate", "true");
                default -> err(String.format("Warning: unknown argument '%s', ignoring.", args[i]));
            }
        }
        return map;
    }

    static String resolve(Map<String, String> cli, Properties config, String key) {
        String v = cli.get(key);
        if (v != null) return v;
        v = config.getProperty("tab2xdxf." + key);
        if (v != null) return v;
        return config.getProperty(key);
    }

    static void printUsage() {
        err("""
            Usage: tab-2-xdxf --in|-i  <input.tsv|->   --out|-o <output.xdxf|->
                               --lang|-l <ISO 639-1 or 639-2>
                               --name|-n <dictionary name>
                               [--no-validate|-V]
                               [--config|-c <config.properties>]

            --in|-i   Input tabfile or - for stdin.
            --out|-o  Output XDXF file or - for stdout. Stdout output validates via a temp file.
            --no-validate|-V  Skip XML validation.

            Examples:
              tab-2-xdxf -i data/out/kaikki-pt.tsv -o data/out/kaikki-pt.xdxf -l pt -n "Dicionário PT"
              tab-2-xdxf -i - -o data/out/kaikki-pt.xdxf -l pt -n "Dicionário PT"
              kaikki-2-tab -c dict.properties | tab-2-xdxf -c dict.properties | xdxf-2-pbdic -c dict.properties
              tab-2-xdxf -c dict.properties
            """);
    }
}
