import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import java.util.zip.GZIPInputStream;

/**
 * Converts a Kaikki JSONL dictionary dump to a tabfile (TSV) for use with
 * {@code dict-2-xdxf --format tab}.
 *
 * <br><b>Usage:</b>
 * <pre>
 *   ./kaikki-2-tab --in|-i &lt;input.jsonl&gt; --out|-o &lt;output.tsv&gt; --lang|-l &lt;code[,code...]&gt;
 *                 [--config|-c &lt;config.properties&gt;]
 * </pre>
 *
 * <br><b>Examples:</b>
 * <pre>
 *   ./kaikki-2-tab -i data/kaikki/pt-extract.jsonl -o data/out/kaikki-pt.tsv -l pt
 *   ./kaikki-2-tab -i data/kaikki/pt-extract.jsonl -o - -l pt   # write to stdout
 *   ./kaikki-2-tab -c kaikki.properties
 *   ./kaikki-2-tab -c kaikki.properties -l pt,en
 * </pre>
 *
 * <br><b>Stdout:</b> use {@code -o -} to write TSV to stdout for piping into {@code tab-2-xdxf}.
 * This can also be set in the config file.
 * All progress messages are written to stderr so they do not pollute the stream.
 *
 * <br><b>Config file</b> ({@code .properties} format — CLI args override):
 * <pre>
 *   kaikki2tab.in=data/kaikki/pt-extract.jsonl
 *   kaikki2tab.out=data/kaikki-pt.tsv
 *   kaikki2tab.lang=pt
 * </pre>
 *
 * <br><b>Input format:</b> Kaikki JSONL, plain or gzip-compressed.
 * Supported extensions: {@code .jsonl}, {@code .jsonl.gz}.
 * <br><b>Output format:</b> TSV — one entry per line: {@code word&lt;TAB&gt;definition}
 * where definition is HTML-formatted for use with PocketBook via {@code dict-2-xdxf}.
 *
 * <br><b>Language filtering:</b>
 * <ul>
 *   <li>{@code --lang} is mandatory (CLI or config); accepts one or more comma-separated ISO 639-1 codes</li>
 *   <li>Entries whose {@code lang_code} is not in the filter are skipped</li>
 * </ul>
 *
 * <br><b>Entry handling:</b>
 * <ul>
 *   <li>Form-of entries (conjugations, plurals) are included with a minimal one-line definition</li>
 *   <li>Regular entries include: part of speech, gender, plural forms (nouns only),
 *       etymology, numbered senses with domain labels and examples, and expressions</li>
 *   <li>Verb entries suppress the {@code forms} list to avoid showing conjugations</li>
 *   <li>Domain labels are taken from {@code raw_tags}, falling back to {@code topics}</li>
 *   <li>Compound expressions appear both inside the parent entry definition (under
 *       <i>Expressões</i>) and as standalone TSV entries for direct lookup</li>
 *   <li>Entries with no senses are skipped</li>
 * </ul>
 *
 * <br><b>Requirements:</b> Java 17+ JDK ({@code java} must be on PATH)
 */
public class ConvKaikki2Tab {

    static final String TOOL = "kaikki-2-tab";

    static final String UC_BULLET = "\u2022 ";

    static void err(String msg) { System.err.println(TOOL + ": " + msg); }
    static void errRaw(String msg) { System.err.println(msg); }
    
    static String mainLang = null;

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || Arrays.asList(args).contains("--help")) {
            printUsage(); System.exit(0);
        }

        var cli = parseArgs(args);

        // Load config file if specified
        var config = new Properties();
        String configPath = cli.get("config");
        if (configPath != null) {
            Path cp = Path.of(configPath);
            if (!Files.exists(cp)) {
                err(String.format("Error: config file not found: %s", cp));
                System.exit(1);
            }
            try (var reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(cp.toFile()), StandardCharsets.UTF_8))) {
                config.load(reader);
            }
        }

        String input   = resolve(cli, config, "in");
        String output  = resolve(cli, config, "out");
        String langArg = resolve(cli, config, "lang");

        if (input == null || output == null || langArg == null) {
            printUsage();
            System.exit(1);
        }
        
        String ewArg = resolve(cli, config, "embedded-defs");
        if (ewArg == null) ewArg = "BOTH";
        ewArg = ewArg.toUpperCase();
        if (!ewArg.equals("KEEP") && !ewArg.equals("SEPARATE") && !ewArg.equals("BOTH")) {
            err(String.format("Error: --embedded-defs must be KEEP, SEPARATE or BOTH, got: %s", ewArg));
            System.exit(1);
        }
        final boolean emitEmbedded = ewArg.equals("KEEP") || ewArg.equals("BOTH");
        final boolean emitSeparate = ewArg.equals("SEPARATE") || ewArg.equals("BOTH");

        boolean toStdout = "-".equals(output);

        Set<String> langFilter;
        {
            var arrLangs = langArg.split(",");
            mainLang = arrLangs[0]; 
            langFilter = new HashSet<>(Arrays.asList(arrLangs));
        }

        Path inFile = Path.of(input);

        if (!Files.exists(inFile)) {
            err(String.format("Error: file not found: %s", inFile));
            System.exit(1);
        }

        err(TOOL);
        errRaw(String.format("  input           : %s", inFile));
        errRaw(String.format("  output          : %s", (toStdout ? "<stdout>" : output)));
        errRaw(String.format("  lang            : %s (main: %s)", langFilter, mainLang));
        errRaw(String.format("  embedded-defs  : %s", ewArg));
        if (configPath != null) errRaw(String.format("  config          : %s", configPath));
        errRaw("");

        // Detect gzip compression by extension
        String fileName = inFile.getFileName().toString().toLowerCase();
        boolean isGzip  = fileName.endsWith(".gz");

        int count = 0, filtered = 0, skipped = 0, duplicates = 0;
        // Key = "word\tdef" — only drop entries where both headword AND content are identical
        var seenEntries = new HashSet<String>();
        var affectedWords = new HashSet<String>();
        InputStream rawStream = new FileInputStream(inFile.toFile());
        InputStream inStream  = isGzip ? new GZIPInputStream(rawStream) : rawStream;
        OutputStream outStream = toStdout ? System.out : new FileOutputStream(output);
        try (var reader = new BufferedReader(new InputStreamReader(inStream, StandardCharsets.UTF_8));
             var writer = new BufferedWriter(new OutputStreamWriter(outStream, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;
                try {
                    Map<String, ?> entry = new JsonParser(line).parseObject();

                    // Filter by language
                    if (!langFilter.contains(getStr(entry, "lang_code"))) { filtered++; continue; }

                    String word = getStr(entry, "word");
                    if (word == null || word.isBlank()) { skipped++; continue; }

                    // Main entry
                    String def = buildDefinition(word, entry, emitEmbedded);
                    if (def != null && !def.isBlank()) {
                        String sanitized = sanitize(def);
                        if (seenEntries.add(word + "\t" + sanitized)) {
                            writer.write(word + "\t" + sanitized + "\n");
                            count++;
                        } else {
                            duplicates++;
                            affectedWords.add(word);
                        }
                    } else {
                        skipped++;
                    }

                    // Expressions as separate TSV entries
                    if (emitSeparate) {
                        List<Map<String, ?>> expressions = getList(entry,"expressions",null);
                        for (var expr : expressions) {
                            String exprWord = getStr(expr, "word");
                            if (exprWord == null || exprWord.isBlank()) continue;
                            // Skip expression entries whose headword has fewer than 2 characters
                            // and contains no alphabetic character — these are punctuation/symbol
                            // artefacts from Kaikki (e.g. ":" extracted from an English expression).
                            if (exprWord.length() < 2 && !exprWord.codePoints().anyMatch(Character::isLetter)) continue;
                            List<?> exprSenses = getList(expr, "senses", null);
                            if (exprSenses.isEmpty()) continue;
                            var sb2 = new StringBuilder();
                            sb2.append("<ol>");
                            for (var s : exprSenses) {
                                var sense = (Map<String, Object>) s;
                                sb2.append("<li>");
                                sb2.append(buildDomainText(sense));

                                List<String> glosses = getList(sense, "glosses", null);
                                if (!glosses.isEmpty()) {
                                    String g2 = stripWiki(glosses.get(0));
                                    while (g2.startsWith(UC_BULLET)) g2 = g2.substring(2);
                                    sb2.append(escapeXml(g2));
                                }

                                sb2.append(buildExampleText(sense, exprWord));
                                sb2.append("</li>");
                            }
                            sb2.append("</ol>");
                            String exprSanitized = sanitize(sb2.toString());
                            if (seenEntries.add(exprWord + "\t" + exprSanitized)) {
                                writer.write(exprWord + "\t" + exprSanitized + "\n");
                                count++;
                            } else {
                                duplicates++;
                                affectedWords.add(exprWord);
                            }
                        }
                    } // end if (emitSeparate)

                } catch (Exception e) {
                    err(String.format("Warning: skipping malformed line: %s", e.getMessage()));
                    skipped++;
                }
            }
        }

        err(String.format("%d entries written, %d filtered (wrong language), %d exact-content duplicates dropped (%d headwords affected), %d skipped (blank/error).", count, filtered, duplicates, affectedWords.size(), skipped));
    }

    // --- Definition builder ---

    @SuppressWarnings("unchecked")
    static String buildDefinition(String word, Map<String, ?> entry, boolean emitEmbedded) {
        boolean keepExpressionsWithEmptyContent = true;
        List<String> tags  = getList(entry, "tags", null);
        List<Map<String, ?>> senses  = getList(entry, "senses", null);

        // Form-of entries: minimal one-line definition
        if (tags.contains("form-of") && !senses.isEmpty()) {
            Map<String, ?> sense = senses.get(0);
            List<String> glosses = getList(sense, "glosses", null);
            if (!glosses.isEmpty()) {
                String gloss = stripWiki(glosses.get(0));
                while (gloss.startsWith(UC_BULLET)) gloss = gloss.substring(2);
                String posTitle = getStr(entry, "pos_title");
                if (posTitle != null)
                    return "<p><i>" + escapeXml(posTitle) + "</i> — " + escapeXml(gloss) + "</p>";
                return "<p>" + escapeXml(gloss) + "</p>";
            }
            return null;
        }

        if (senses.isEmpty()) return null;

        var sb = new StringBuilder();

        // 1. Part of speech + gender
        String posTitle = getStr(entry, "pos_title");
        if (posTitle == null) posTitle = getStr(entry, "pos");
        String gender = tags.contains("masculine") ? "masculine"
                      : tags.contains("feminine")  ? "feminine"
                      : null;

        if (posTitle != null) {
            sb.append("<p>"+formatTitle(posTitle, true));
            if (gender != null) sb.append(" (").append(translateTerm(gender)).append(")");
            sb.append("</p>");
        }

        // 2. Plural forms (nouns only)
        if (!"verb".equals(getStr(entry, "pos"))) {
            List<Map<String,?>> forms = getList(entry, "forms", null);
            var plurals = new ArrayList<String>();
            for (var fm : forms) {
                List<?> ftags = getList(fm, "tags", null);
                if (ftags.contains("plural")) {
                    String form = getStr(fm, "form");
                    if (form != null) plurals.add(form);
                }
            }
            if (!plurals.isEmpty()) {
                sb.append("<p><i>" + translateTermCap("plural") + " :</i> ")
                  .append(escapeXml(String.join(", ", plurals))).append("</p>");
            }
        }

        // 3. Pronunciation
        @SuppressWarnings("unchecked")
        Map<String, List<String>> mapSounds = buildSoundsMap(entry);        
        if(!mapSounds.isEmpty()) {
            var ipas = new ArrayList<String>();
            boolean renderTag = mapSounds.size() > 1;
            for(var snd : mapSounds.entrySet()) {
              ipas.add(snd.getValue().get(0) + (renderTag ? "<i><small>("+snd.getKey()+")</small></i>":""));
            }
        
            if (!ipas.isEmpty()) {
                sb.append("<p><i>" + translateTermCap("sounds") + ": </i> ")
                  .append(escapeXml(String.join(", ", ipas))).append("</p>");
            }
        }

        // 4. Senses
        sb.append("<ol>");
        for (var s : senses) {
            var sense = (Map<String, Object>) s;
            sb.append("<li>");
            sb.append(buildDomainText(sense));

            // Glosses
            List<String> glosses = getList(sense, "glosses", null);
            if (!glosses.isEmpty()) {
                String gloss = stripWiki(glosses.get(0));
                while (gloss.startsWith(UC_BULLET)) gloss = gloss.substring(2);
                sb.append(escapeXml(gloss));
            }

            // Example
            sb.append(buildExampleText(sense, word));
            sb.append("</li>");
        }
        sb.append("</ol>");

        // 5. Etymology
        List<String> etymTexts = getList(entry, "etymology_texts", null);
        if (!etymTexts.isEmpty()) {
            String etym = etymTexts.get(0).strip();
            if (etym.startsWith(":")) etym = etym.substring(1).strip();
            sb.append("<p><i>" + translateTermCap("ethymology") + ": </i> ").append(escapeXml(etym)).append("</p>");
        }

        // 6. Expressions
        if (emitEmbedded) {
            List<Map<String,?>> expressions = getList(entry, "expressions", null);
            if (!expressions.isEmpty()) {
                sb.append("<p>"+formatTitle(translateTermCap("expressions"), false)+"</p><ul>");
                for (var expr : expressions) {
                    String exprWord = getStr(expr, "word");
                    if (exprWord == null || exprWord.isBlank()) continue;
                    List<Map<String, ?>> exprSenses = getList(expr, "senses", null);
                    
                    if (exprSenses.isEmpty() && !keepExpressionsWithEmptyContent) continue;
    
                    var sbContent = new StringBuilder();
                    if(!exprSenses.isEmpty()) {
                        Map<String, ?> exprSense = exprSenses.get(0);

                        sbContent.append(buildDomainText(exprSense));

                        List<String> exprGlosses = getList(exprSense, "glosses", null);
                        if(!exprGlosses.isEmpty()) {
                            // just the firsy gloss, should be enough
                            sbContent.append(stripWiki(exprGlosses.get(0)));
                        }
                    }

                    sb.append("<li><b>").append(escapeXml(exprWord)).append("</b>");
                    if(sbContent.length() > 0) {
                        sb.append(" - ").append(sbContent);
                    }
                    sb.append("</li>");
                }
                sb.append("</ul>");
            }
        } // end if (emitEmbedded)

        return sb.toString();
    }

    // --- Helpers ---
    
    static String formatTitle(String title, boolean large) {
        if(large) {
            title = "<large>" + title +"</large>";
        }
        title = escapeXml(title);
        title = "<b>" + title + ": </b>";
        return title;
    }

    /** Build text representing examples for a sense */
    static String buildExampleText(Map<String, ?> sense, String refWord) {
        List<Map<String,?>> examples =  getList(sense, "examples", null);
        if (examples.isEmpty()) {
            return "";
        }

        var sb = new StringBuilder();
        // just the first example
        Map<String, ?> ex = examples.get(0);
        String text = getStr(ex, "ref");
        if(text == null || text.isBlank()) {
            text = getStr(ex, "text");
        }
        if (text != null && !text.isBlank()) {
            String quote = stripWiki(text).replace("\n","//");
            if(refWord != null && !refWord.isBlank()) {
                quote = quote.replace(refWord, "<b>"+refWord+"</b>");
            }
            quote = "<i>\""+quote+"\"</i>";
            sb.append(" <small>(" + translateTerm("example") + ": ")
                .append(quote)
                .append(")</small>");
        }

        return escapeXml(sb.toString());
    }

    /** Build a sounds map */
    @SuppressWarnings("unchecked")
    static Map<String, List<String>> buildSoundsMap(Map<String, ?> sense) {
        var soundtagsToIgnore = Set.of("","X-SAMPA","SAMPA","IPA");
        List<?> sounds = getList(sense, "sounds", null);

        var ipas = new ArrayList<String>();

        // build a map of lists sounds by tag
        var mapSounds = new LinkedHashMap<String, List<String>>();        
        for (var s : sounds) {
            @SuppressWarnings("unchecked")
            var sound = (Map<String, Object>) s;
            @SuppressWarnings("unchecked")
            String ipa = getStr(sound, "ipa");
            if (ipa != null && !ipa.isBlank()) {
                // Use only the first tag for the sound
                List<String> stags = getList(sound, "tags", null);
                String stag = stags.isEmpty()? "" : (stags.get(0).trim());
                
                if(!stag.contains("?")) {
                   mapSounds.computeIfAbsent(stag, kk -> new ArrayList<>()).add(ipa);
                }
            }
        }
         
        // remove tags to ignore from the map, while there is more than one
        for(var tig: soundtagsToIgnore) {
            if(mapSounds.size() <= 1) break;
            mapSounds.remove(tig);
        }
        
        return mapSounds;
    }

    /** Returns domain label from topics, falling back to rawtags. */
    @SuppressWarnings("unchecked")
    static String buildDomainText(Map<String, ?> sense) {
        String domains;
        List<String> rawTags = getList(sense, "raw_tags", null);
        List<String> topics =  getList(sense, "topics", null);
        
        if (!topics.isEmpty()) {
            domains = topics.stream().map(el -> el.strip()).collect(Collectors.joining(","));
        } else if (!rawTags.isEmpty()) {
            domains = rawTags.stream().map(el -> el.strip()).collect(Collectors.joining(","));
        }
        else {
            return "";
        }
        
        String result = Arrays.stream(domains.split("[;,]")).distinct()
                              .map(el -> translateTermCap(el))
                              .collect(Collectors.joining(", "));
        return " <i>[" + result + "]</i> ";
    }
    
    /** Returns translated term, falling back to received one. Not escaped for xml.*/
    static String translateRaw(Object term) {
       String strTerm = term == null? null : term.toString().trim();
    
       if(strTerm == null || strTerm.isBlank()) return "";  
       
       String result = null;
       // TODO: read mapping from a parameter file, using language stored in global lang
       
       // by default return the received translateTerm
       return result != null? result : strTerm; 
    }

    /** Returns translated term, falling back to received one. Not escaped for xml.*/
    static String translateTerm(Object term) {
        String result = translateRaw(term);
        return escapeXml(result);
    }

    /** Returns translated term returning with capital first, falling back to received one. */
    static String translateTermCap(Object term) {
       String result = translateRaw(term);
       
       // by default return the received translateTerm
       result = result == null? result : result.substring(0,1).toUpperCase()+result.substring(1); 
       
       return escapeXml(result); 
    }


    /** Collapses internal newlines and tabs — TSV requires one line per entry. */
    static String sanitize(String s) {
        return s.replace("\n", " ").replace("\t", " ");
    }

    /** Strips wiki markup: [[target|display]] → display, [[target]] → target. */
    static String stripWiki(String wiki) {
        if(wiki == null) return "";

        String string = wiki.toString().stripLeading();
        string = string.replaceAll("\\[\\[(?:[^|\\]]*\\|)?([^\\]]+)\\]\\]", "$1");
        string = string.replace("[[", "").replace("]]", "");
        return escapeXml(string);
    }

    @SuppressWarnings("unchecked")
    static String getStr(Map<String, ?> map, String key) {
        Object entry = map.get(key);
        if(entry instanceof List<?>) {
           entry = ((List<?>)entry).isEmpty()? null: ((List<?>)entry).get(0).toString();
        }
        return entry instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    static <T> List<T> getList(Map<String,?> map, String key, T defltValue) {
        Object entry = map.get(key);

        if(entry == null) {
            return defltValue==null? List.of() : List.of(defltValue);
        } else if(entry instanceof List){
            return (List<T>) entry;
        } else {
            return List.of((T)entry);
        }
    }

    static String escapeXml(String string) {
        if(string == null) return null;
        return string.replace("&", "&amp;")
                     .replace("<", "&lt;")
                     .replace(">", "&gt;");
    }

    // --- Argument parser ---

    static Map<String, String> parseArgs(String[] args) {
        var map = new LinkedHashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config",          "-c" -> map.put("config",          args[++i]);
                case "--in",              "-i" -> map.put("in",              args[++i]);
                case "--out",             "-o" -> map.put("out",             args[++i]);
                case "--lang",            "-l" -> map.put("lang",            args[++i]);
                case "--embedded-defs" ,  "-e" -> map.put("embedded-defs",   args[++i]);
                default -> err(String.format("Warning: unknown argument '%s', ignoring.", args[i]));
            }
        }
        return map;
    }

    /** Returns CLI value if present, else namespaced config value, else plain config value, else null. */
    static String resolve(Map<String, String> cli, Properties config, String key) {
        String v = cli.get(key);
        if (v != null) return v;
        v = config.getProperty("kaikki2tab." + key);
        if (v != null) return v;
        return config.getProperty(key);
    }

    static void printUsage() {
        err("""
            Usage: kaikki-2-tab --in|-i <input.jsonl[.gz]> --out|-o <output.tsv|-> --lang|-l <code[,code...]>
                               [--embedded-defs|-e KEEP|SEPARATE|BOTH] [--config|-c <config.properties>]

            --in  accepts .jsonl or .jsonl.gz files.
            --out accepts a file path or - for stdout.
            --lang accepts one or more comma-separated ISO 639-1 language codes.
            --embedded-defs controls how expressions are emitted (default: BOTH):
                KEEP     — expressions appear only embedded inside the parent word's definition.
                SEPARATE — expressions are emitted only as their own standalone entries.
                BOTH     — both embedded and standalone (default).
            CLI arguments override values from the config file (namespace: kaikki2tab.*).

            Example:
              kaikki-2-tab -i data/kaikki/pt-extract.jsonl -o data/out/kaikki-pt.tsv -l pt
              kaikki-2-tab -i data/kaikki/pt-extract.jsonl -o - -l pt -e SEPARATE | tab-2-xdxf -i - ...
              kaikki-2-tab -c kaikki.properties
            """);
    }

    // =========================================================================
    // Minimal JSON parser
    // =========================================================================

    static class JsonParser {
        private final String s;
        private int pos;

        JsonParser(String s) { this.s = s; this.pos = 0; }

        Object parse() {
            skipWs();
            if (pos >= s.length()) throw new RuntimeException("Unexpected end of input");
            return switch (s.charAt(pos)) {
                case '{'     -> parseObject();
                case '['     -> parseArray();
                case '"'     -> parseString();
                case 't','f' -> parseBoolean();
                case 'n'     -> parseNull();
                default      -> parseNumber();
            };
        }

        Map<String, Object> parseObject() {
            expect('{');
            var map = new LinkedHashMap<String, Object>();
            skipWs();
            if (peek() == '}') { pos++; return map; }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs(); expect(':');
                Object val = parse();
                map.put(key, val);
                skipWs();
                char c = s.charAt(pos++);
                if (c == '}') break;
                if (c != ',') throw new RuntimeException("Expected ',' or '}' at " + pos);
            }
            return map;
        }

        List<Object> parseArray() {
            expect('[');
            var list = new ArrayList<Object>();
            skipWs();
            if (peek() == ']') { pos++; return list; }
            while (true) {
                list.add(parse());
                skipWs();
                char c = s.charAt(pos++);
                if (c == ']') break;
                if (c != ',') throw new RuntimeException("Expected ',' or ']' at " + pos);
            }
            return list;
        }

        String parseString() {
            expect('"');
            var sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"'  -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/'  -> sb.append('/');
                        case 'n'  -> sb.append('\n');
                        case 'r'  -> sb.append('\r');
                        case 't'  -> sb.append('\t');
                        case 'b'  -> sb.append('\b');
                        case 'f'  -> sb.append('\f');
                        case 'u'  -> {
                            String hex = s.substring(pos, pos + 4); pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new RuntimeException("Unterminated string");
        }

        Object parseNumber() {
            int start = pos;
            while (pos < s.length() && "0123456789.-+eE".indexOf(s.charAt(pos)) >= 0) pos++;
            return Double.parseDouble(s.substring(start, pos));
        }

        Boolean parseBoolean() {
            if (s.startsWith("true",  pos)) { pos += 4; return Boolean.TRUE; }
            if (s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw new RuntimeException("Invalid boolean at " + pos);
        }

        Object parseNull() {
            if (s.startsWith("null", pos)) { pos += 4; return null; }
            throw new RuntimeException("Invalid null at " + pos);
        }

        void skipWs() { while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++; }
        void expect(char c) { if (s.charAt(pos++) != c) throw new RuntimeException("Expected '" + c + "' at " + (pos-1)); }
        char peek() { return pos < s.length() ? s.charAt(pos) : 0; }
    }
}
