import static shared.Constants.UC_BULLET;
import static shared.XmlHelper.encodeEntities;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import shared.ArgsHelper;
import shared.JsonParser;

/**
 * Converts a Kaikki JSONL dictionary dump to a tabfile (TSV) for use with
 * {@code dict-2-xdxf --format tab}.
 *
 * <br>
 * <b>Usage:</b>
 * 
 * <pre>
 *   ./kaikki-2-tab --in|-i &lt;input.jsonl&gt; --out|-o &lt;output.tsv&gt; --lang|-l &lt;code[,code...]&gt;
 *                 [--config|-c &lt;config.properties&gt;]
 * </pre>
 *
 * <br>
 * <b>Examples:</b>
 * 
 * <pre>
 *   ./kaikki-2-tab -i data/kaikki/pt-extract.jsonl -o data/out/kaikki-pt.tsv -l pt
 *   ./kaikki-2-tab -i data/kaikki/pt-extract.jsonl -o - -l pt   # write to stdout
 *   ./kaikki-2-tab -c kaikki.properties
 *   ./kaikki-2-tab -c kaikki.properties -l pt,en
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
 *   kaikki2tab.in=data/kaikki/pt-extract.jsonl
 *   kaikki2tab.out=data/kaikki-pt.tsv
 *   kaikki2tab.lang=pt
 * </pre>
 *
 * <br>
 * <b>Input format:</b> Kaikki JSONL, plain or gzip-compressed. Supported
 * extensions: {@code .jsonl}, {@code .jsonl.gz}. <br>
 * <b>Output format:</b> TSV — one entry per line:
 * {@code word&lt;TAB&gt;definition} where definition is HTML-formatted for use
 * with PocketBook via {@code dict-2-xdxf}.
 *
 * <br>
 * <b>Language filtering:</b>
 * <ul>
 * <li>{@code --lang} is mandatory (CLI or config); accepts one or more
 * comma-separated ISO 639-1 codes</li>
 * <ul>
 * <li>The first language is considered the 'From' language</li>
 * <li>The second language is considered the 'To' language. If not specified, it
 * will be the same as the 'From'</li>
 * </ul>
 * <li>Entries whose {@code lang_code} does not correspond to the 'From'
 * language are skipped</li>
 * </ul>
 *
 * <br>
 * <b>Entry handling:</b>
 * <ul>
 * <li>Form-of entries (conjugations, plurals) are included with a minimal
 * one-line definition</li>
 * <li>Regular entries include: part of speech, gender, plural forms (nouns
 * only), etymology, numbered senses with domain labels and examples, and
 * expressions</li>
 * <li>Verb entries suppress the {@code forms} list to avoid showing
 * conjugations</li>
 * <li>Domain labels are taken from {@code raw_tags}, falling back to
 * {@code topics}</li>
 * <li>Compound expressions appear both inside the parent entry definition
 * (under <i>Expressões</i>) and as standalone TSV entries for direct
 * lookup</li>
 * <li>Entries with no senses are skipped</li>
 * </ul>
 *
 * <br>
 * <b>Requirements:</b> Java 17+ JDK ({@code java} must be on PATH)
 */
public class ConvKaikki2Tab {

	static final String TOOL = "kaikki-2-tab";

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

	static String langFrom = null, langTo = null;

	// ── main ──────────────────────────────────────────────────────────────────
	public static void main(String[] args) throws Exception {
		if (args.length == 0 || Arrays.asList(args).contains("--help")) {
			err(usage());
			System.exit(0);
		}

		var cli = parseArgs(args);

		// Load config file if specified
		String configPath = cli.get("config");
		var argsHlp = new ArgsHelper(TOOL, "kaikki2tab", er -> err(er), () -> usage());

		Properties config = argsHlp.loadProperties(configPath, true);
		argsHlp.mergeConfig2Cli(List.of("in", "out", "lang", "embedded-defs"), cli, config);

		String input = argsHlp.require(cli, "in", "--in/-i");
		String output = argsHlp.require(cli, "out", "--out/-o");
		String langArg = argsHlp.require(cli, "lang", "--lang/-l");

		String ewArg = argsHlp.resolveUC(cli, "embedded-defs", "BOTH");
		if (!ewArg.equals("KEEP") && !ewArg.equals("SEPARATE") && !ewArg.equals("BOTH")) {
			err(String.format("Error: --embedded-defs must be KEEP, SEPARATE or BOTH, got: %s", ewArg));
			System.exit(1);
		}

		final boolean emitEmbedded = ewArg.equals("KEEP") || ewArg.equals("BOTH");
		final boolean emitSeparate = ewArg.equals("SEPARATE") || ewArg.equals("BOTH");

		boolean toStdout = "-".equals(output);

		{
			var arrLangs = langArg.toUpperCase().split(",");
			langFrom = arrLangs[0];
			langTo = arrLangs.length == 1 ? langFrom : arrLangs[1];
		}

		Path inFile = Path.of(input);

		if (!Files.exists(inFile)) {
			err(String.format("Error: file not found: %s", inFile));
			System.exit(1);
		}

		argsHlp.listProperties(cli);

		// Detect gzip compression by extension
		String fileName = inFile.getFileName().toString().toLowerCase();
		boolean isGzip = fileName.endsWith(".gz");

		int count = 0, filtered = 0, skipped = 0, duplicates = 0;
		// Key = "word\tdef" — only drop entries where both headword AND content are
		// identical
		var seenEntries = new HashSet<String>();
		var seenWords = new HashSet<String>();
		var affectedWords = new HashSet<String>();
		InputStream rawStream = new FileInputStream(inFile.toFile());
		InputStream inStream = isGzip ? new GZIPInputStream(rawStream) : rawStream;
		OutputStream outStream = toStdout ? System.out : new FileOutputStream(output);
		try (var reader = new BufferedReader(new InputStreamReader(inStream, StandardCharsets.UTF_8));
				var writer = new BufferedWriter(new OutputStreamWriter(outStream, StandardCharsets.UTF_8))) {

			Map<String, CharSequence> mapExprsToEmitSeparate = null;

			String line;
			while ((line = reader.readLine()) != null) {
				line = line.strip();
				if (strIsEmpty(line))
					continue;
				try {
					Map<String, ?> entry = new JsonParser(line).parseObject();

					// Filter by language
					if (!langFrom.equalsIgnoreCase(getStr(entry, "lang_code"))) {
						filtered++;
						continue;
					}

					String word = getStr(entry, "word");
					if (strIsEmpty(word)) {
						skipped++;
						continue;
					}

					// Main entry
					String def = buildDefinition(word, entry, emitEmbedded);
					if (!strIsEmpty(def)) {
						String lin = word + "\t" + sanitize(def);
						seenWords.add(word);
						if (seenEntries.add(lin)) { // did not exist in the set
							writer.write(lin + "\n");
							count++;
						} else {
							affectedWords.add(word);
							duplicates++;
						}
					} else {
						skipped++;
					}

					// Expressions as separate TSV entries
					if (emitSeparate) {
						if (mapExprsToEmitSeparate == null) {
							mapExprsToEmitSeparate = new LinkedHashMap<>();
						}
						mapExprsToEmitSeparate.putAll(prepareExpressionsToEmit(entry, word));
					} // end if (emitSeparate)

				} catch (Exception e) {
					err(String.format("Warning: skipping malformed line: %s", e.getMessage()));
					e.printStackTrace();
					skipped++;
				}
			} // while

			if (mapExprsToEmitSeparate != null) {
				for (var exp : mapExprsToEmitSeparate.entrySet()) {
					var exprWord = exp.getKey();
					var exprContent = exp.getValue();

					// don't want to emit an expression if its word as already been seen
					// expressions don't count has seen
					if (!seenWords.contains(exprWord)) {
						String lin = exprWord + "\t" + exprContent;
						if (seenEntries.add(lin)) {
							writer.write(lin + "\n");
							count++;
						} else {
							affectedWords.add(exprWord);
							duplicates++;
						}
					}
				}
			}
		} // main try

		err(String.format(
				"%d entries written, %d filtered (wrong language), %d exact-content duplicates dropped (%d headwords affected), %d skipped (blank/error).",
				count, filtered, duplicates, affectedWords.size(), skipped));
	}

	// --- Definition builder ---

	static String buildDefinition(String word, Map<String, ?> entry, boolean emitEmbedded) {

		boolean keepExpressionsWithEmptyContent = true;

		List<String> tags = getList(entry, "tags", null);
		List<Map<String, ?>> senses = getList(entry, "senses", null);

		// Form-of entries: minimal one-line definition (or not)
		if (tags.contains("form-of") && !listIsEmpty(senses)) {
			boolean shortFormOf = false;
			Map<String, ?> sense = senses.get(0); // TODO consider displyaing more
			String gloss = extractGloss(sense);
			if (strIsEmpty(gloss)) {
				String posTitle = getStr(entry, "pos_title");
				gloss = encodeEntities(false, gloss);
				if (posTitle != null) {
					posTitle = translateTermCap(posTitle);
					if (shortFormOf) {
						return "<p>" + formatTitle(false, posTitle) + " - " + gloss + "</p>";
					} else {
						return "<p>" + formatTitle(true, posTitle) + "</p><p>" + gloss + "</p>";
					}
				} else {
					return "<p>" + gloss + "</p>";
				}
			}
		}

		if (listIsEmpty(senses))
			return null;

		var sbDef = new StringBuilder();

		// 1. Part of speech + gender
		String posTitle = getStr(entry, "pos_title");
		if (posTitle == null) {
			posTitle = getStr(entry, "pos");
		}
		String gender = tags.contains("masculine") ? "masculine" : tags.contains("feminine") ? "feminine" : null;

		if (posTitle != null) {
			sbDef.append("<p>" + formatTitle(true, posTitle));
			if (gender != null)
				sbDef.append(" (").append(translateTerm(gender)).append(")");
			sbDef.append("</p>");
		}

		// 2. Plural forms (nouns only)
		var plurals = new ArrayList<String>();
		if (!"verb".equals(getStr(entry, "pos"))) {
			List<Map<String, ?>> forms = getList(entry, "forms", null);
			for (var fm : forms) {
				List<?> ftags = getList(fm, "tags", null);
				if (ftags.contains("plural")) {
					String form = getStr(fm, "form");
					if (form != null)
						plurals.add(form);
				}
			}
			if (!plurals.isEmpty()) {
				sbDef.append("<p><i>" + translateTermCap("plural") + " :</i> ")
						.append(encodeEntities(false, String.join(", ", plurals)))//
						.append("</p>");
			}
		}

		// 3. Pronunciation
		Map<String, List<String>> mapSounds = buildSoundsMap(entry);
		if (!mapSounds.isEmpty()) {
			var ipas = new ArrayList<String>();
			boolean renderTag = mapSounds.size() > 1;
			for (var snd : mapSounds.entrySet()) {
				ipas.add(snd.getValue().get(0) + (renderTag ? "<i><small>(" + snd.getKey() + ")</small></i>" : ""));
			}

			if (!ipas.isEmpty()) {
				sbDef.append("<p><i>" + translateTermCap("sounds") + ": </i> ")
						.append(encodeEntities(true, String.join(", ", ipas)))//
						.append("</p>");
			}
		}

		// 4. Senses
		sbDef.append("<ol>");
		for (var sense : senses) {

			sbDef.append("<li>");
			sbDef.append(buildDomainText(sense));

			// Glosses
			String gloss = extractGloss(sense);
			if (!strIsEmpty(gloss)) {
				sbDef.append(encodeEntities(false, gloss));
			}

			// Example
			sbDef.append(buildExampleText(sense, word, plurals));
			sbDef.append("</li>");
		}
		sbDef.append("</ol>");

		// 5. Etymology
		List<String> etymTexts = getList(entry, "etymology_texts", null);
		if (!etymTexts.isEmpty()) {
			String etym = etymTexts.get(0).strip();
			if (etym.startsWith(":"))
				etym = etym.substring(1).strip();
			sbDef.append("<p>")//
					.append("<i>" + translateTermCap("ethymology") + ": </i> ")//
					.append(encodeEntities(false, etym))//
					.append("</p>");
		}

		// 6. Expressions (embedded)
		if (emitEmbedded) {
			List<String> exprTexts = buildEmbeddedExpressionsText(entry, keepExpressionsWithEmptyContent);
			if (!listIsEmpty(exprTexts)) {
				sbDef.append("<p>" + formatTitle(false, translateTermCap("expressions")) + "</p>")//
						.append("<ul>");
				for (String et : exprTexts) {
					sbDef.append("<li>").append(et).append("</li>");
				}
				sbDef.append("</ul>");
			}
		} // end if (emitEmbedded)

		return sbDef.toString();
	}

	// --- Helpers ---

	/** Prepare a list with expressions to be embedded in a definition */
	private static List<String> buildEmbeddedExpressionsText(Map<String, ?> entry,
			boolean keepExpressionsWithEmptyContent) {

		var exprTexts = new ArrayList<String>();

		List<Map<String, ?>> expressions = getList(entry, "expressions", null);
		if (!expressions.isEmpty()) {
			for (var expr : expressions) {
				var sb = new StringBuilder();
				String exprWord = getStr(expr, "word");
				if (strIsEmpty(exprWord))
					continue;

				List<Map<String, ?>> exprSenses = getList(expr, "senses", null);

				if (listIsEmpty(exprSenses) && !keepExpressionsWithEmptyContent)
					continue;

				sb.append("<i>\"<u>")//
						.append(encodeEntities(false, exprWord))//
						.append("</u>\"</i>");
				if (!listIsEmpty(exprSenses)) {
					var sbEmbed = new StringBuilder();
					Map<String, ?> exprSense = exprSenses.get(0); // just the first sense
					sbEmbed.append(buildDomainText(exprSense));

					String exprGloss = extractGloss(exprSense);
					if (!strIsEmpty(exprGloss)) {
						sbEmbed.append(stripWiki(true, exprGloss));
					}
					if (sbEmbed.length() > 0) {
						sb.append(" - ").append(sbEmbed);
					}
				}
				exprTexts.add(sb.toString());
			}
		}

		return exprTexts;
	}

	/** Prepare a map with expressions to emit, related to a given ref. word */
	static Map<String, CharSequence> prepareExpressionsToEmit(Map<String, ?> entry, String refWord) {
		Map<String, CharSequence> mapResult = new LinkedHashMap<>();
		List<Map<String, ?>> expressions = getList(entry, "expressions", null);
		for (var expr : expressions) {
			String exprWord = getStr(expr, "word");
			if (strIsEmpty(exprWord))
				continue;
			// Skip expression entries whose headword has fewer than 2 characters
			// and contains no alphabetic character — these are punctuation/symbol
			// artifacts from Kaikki (e.g. ":" extracted from an English expression).
			if (exprWord.length() < 2 && !exprWord.codePoints().anyMatch(Character::isLetter))
				continue;
			List<Map<String, ?>> exprSenses = getList(expr, "senses", null);
			if (listIsEmpty(exprSenses))
				continue;

			var sb = new StringBuilder();
			sb.append("<p>").append(formatTitle(true, translateTermCap("expression")));
			if (!strIsEmpty(refWord)) {
				sb.append(" (").append(refWord).append(")");
			}
			sb.append("</p>");
			sb.append("<ol>");
			for (var sense : exprSenses) {
				sb.append("<li>");
				sb.append(buildDomainText(sense));

				String gloss = extractGloss(sense);
				if (!strIsEmpty(gloss)) {
					sb.append(encodeEntities(false, gloss));
				}

				sb.append(buildExampleText(sense, exprWord, null));
				sb.append("</li>");
			}
			sb.append("</ol>");
			mapResult.put(exprWord, sanitize(sb));
		}
		return mapResult;
	}

	/** Extract the first gloss from the given sense */
	private static String extractGloss(Map<String, ?> sense) {

		if (sense == null)
			return null;
		List<String> glosses = getList(sense, "glosses", null);

		if (!listIsEmpty(glosses)) {
			// skip glosses that has fewer than 2 characters and contains no alphabetic
			// character
			// and use the first suitable gloss
			glosses = glosses.stream()//
					.filter(gl -> !strIsEmpty(gl))
					.filter(gl -> gl.length() >= 2 && gl.codePoints().anyMatch(Character::isLetter))//
					.map(String::strip) //
					.toList();

			String composeGloss;
			if (glosses.size() <= 1) {
				composeGloss = glosses.isEmpty() ? "" : glosses.get(0);
			} else {
				var sbGloss = new StringBuilder();
				for (var gl : glosses) {
					if (sbGloss.isEmpty()) {
						sbGloss.append(gl);
						continue;
					}

					boolean currStartsAlnum = Character.isLetterOrDigit(gl.codePointAt(0));
					boolean prevEndsAlnum = Character.isLetterOrDigit(sbGloss.codePointAt(sbGloss.length() - 1));

					if (prevEndsAlnum && currStartsAlnum) {
						sbGloss.append(", ").append(gl);
					} else if (!prevEndsAlnum && currStartsAlnum) {
						sbGloss.append(" ").append(gl);
					} else if (!prevEndsAlnum && !currStartsAlnum) {
						sbGloss.append(" ").append(gl);
					} else {
						sbGloss.append(gl);
					}
				}
				composeGloss = sbGloss.toString();
			}

			if (!strIsEmpty(composeGloss)) {
				composeGloss = stripWiki(true, composeGloss);
				composeGloss = stripChar(composeGloss, UC_BULLET, '/', '=');
			}

			return composeGloss;
		} else

		{
			return null;
		}
	}

	static String formatTitle(boolean big, String title) {

		title = encodeEntities(false, title);
		if (big) {
			title = "<big>" + title + "</big>";
		}
		title = "<b>" + title + ": </b>";
		return title;
	}

	/** Build text representing examples for the given sense */
	static String buildExampleText(Map<String, ?> sense, String refWord, List<String> refPlurals) {

		if (strIsEmpty(refWord)) {
			return "";
		}

		List<Map<String, ?>> examples = getList(sense, "examples", null);
		if (listIsEmpty(examples)) {
			return "";
		}

		String openHilite = "<b>", closeHilite = "</b>";

		Comparator<List<Double>> offsetComparator = (e1, e2) -> {
			int level1 = e1.get(0).compareTo(e2.get(0));
			return level1 != 0 ? level1 : e1.get(1).compareTo(e2.get(1));
		};

		Pattern pattRefword4Check, pattRefword4Hilite;
		{
			// Selected quotes must contain the reference word exactly (case-insentive) or
			// as part of word use look behind (must be preceded by non-alphanum or begin
			// line)
			// Highlight preferably occurrences that are delimited with non-alphanum (left
			// and right)
			String lookbehindNonAlpanum = "(?:(?<=[^\\p{Alnum}])|^)";
			String lookaheadNonAlphanum = "(?:(?=[^\\p{Alnum}])|$)";
			pattRefword4Check = Pattern.compile(lookbehindNonAlpanum + refWord,
					Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.UNICODE_CASE);
			pattRefword4Hilite = Pattern.compile(lookbehindNonAlpanum + refWord + lookaheadNonAlphanum,
					Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.UNICODE_CASE);
		}

		var candidateQuotes = new LinkedHashMap<String, List<List<Double>>>();
		for (var ex : examples) {
			String text = getStr(ex, "text");
			List<List<Double>> offsets = getList(ex, "bold_text_offsets", null);
			candidateQuotes.put(text, offsets);
		}

		// Uses candidates that do match the pattern or defined offsets (filtering
		// errors in date)
		Entry<String, List<List<Double>>> quote = candidateQuotes.entrySet().stream()//
				.filter(en -> !strIsEmpty(en.getKey()))//
				.filter(en -> pattRefword4Check.matcher(en.getKey()).find() || !en.getValue().isEmpty()) //
				.findFirst().orElse(null);

		if (quote == null)
			return "";

		String quoteText = quote.getKey();

		// the quotation defines offsets for highlight, filter out invalid ones
		// and sort them
		List<List<Double>> hiliteOffsets = quote.getValue().stream() //
				.filter(of -> of.size() == 2)//
				.filter(of -> of.get(0).intValue() < of.get(1).intValue()) //
				.filter(of -> of.get(1).intValue() <= quote.getKey().length()) //

				.sorted(offsetComparator)//
				.toList();

		if (hiliteOffsets.isEmpty() || (hiliteOffsets.size() > 1 && refWord.length() <= 2)) {
			// there are no offsets, or they seem to be too many, we do the highlighting
			// ourselves
			// we use regular expressions
			var orig = quoteText;
			Function<MatchResult, String> replacer = fw -> openHilite + fw.group() + closeHilite;
			quoteText = pattRefword4Hilite.matcher(quoteText).replaceAll(replacer);
			if (orig.equals(quoteText)) {
				// previous operation has no changes, be more aggressive
				quoteText = pattRefword4Check.matcher(quoteText).replaceAll(replacer);
			}
		} else

		{
			// insert the __highling__ pairs
			// insert must me made from end to start to keep simple to manage indexes

			var wrk = new StringBuilder(quoteText);
			List<Double> prev = null;
			for (int i = hiliteOffsets.size() - 1; i >= 0; --i) {
				List<Double> curr = hiliteOffsets.get(i);
				if (prev != null && prev.get(0).equals(curr.get(0))) {
					continue; // overlapping
				}
				int beg = curr.get(0).intValue(), end = curr.get(1).intValue();
				wrk.insert(end, closeHilite).insert(beg, openHilite);
				prev = curr;
			}
			quoteText = stripWiki(true, wrk.toString()).replace("\n", " / ");
		}

		var sb = new StringBuilder();
		if (!(quoteText.startsWith("\"") && quoteText.endsWith("\""))) {
			// add enclosing quotes if don't already exist
			quoteText = "\"" + quoteText + "\"";
		}
		sb.append(" <small><i>(")//
				.append(translateTerm("example") + ": ")//
				.append(quoteText)//
				.append(")</i></small>");

		return sb.toString();
	}

	/** Build a sounds (pronunciation) map for the given sense */
	@SuppressWarnings("unchecked")
	static Map<String, List<String>> buildSoundsMap(Map<String, ?> sense) {
		var soundtagsToIgnore = Set.of("", "X-SAMPA", "SAMPA"); // Removed "IPA"
		List<?> sounds = getList(sense, "sounds", null);

		// build a map of lists sounds by tag
		var mapSounds = new LinkedHashMap<String, List<String>>();
		for (var s : sounds) {
			var sound = (Map<String, Object>) s;
			String ipa = getStr(sound, "ipa");
			if (!strIsEmpty(ipa)) {
				// Use only the first tag for the sound
				List<String> stags = getList(sound, "tags", null);
				String stag = stags.isEmpty() ? "" : (stags.get(0).trim());

				if (!stag.contains("?")) {
					mapSounds.computeIfAbsent(stag, kk -> new ArrayList<>()).add(ipa);
				}
			}
		}

		// remove tags to ignore from the map, while there is more than one
		for (var tig : soundtagsToIgnore) {
			if (mapSounds.size() <= 1)
				break;
			mapSounds.remove(tig);
		}

		return mapSounds;
	}

	/** List the domains for the given sense (topics + raw_tags) */
	static List<String> listDomains(Map<String, ?> sense) {

		var domains = new ArrayList<String>();

		List<String> topics = getList(sense, "topics", null);
		List<String> rawTags = getList(sense, "raw_tags", null);

		Stream<String> stream = !topics.isEmpty() ? topics.stream() : rawTags.stream();

		stream.filter(el -> !strIsEmpty(el)) //
				.distinct() //
				.map(str -> str.split("[;,]"))//
				.forEach(arr -> domains.addAll(List.of(arr)));

		return domains.stream().filter(el -> !strIsEmpty(el)).distinct().toList();
	}

	/** Returns domain label from topics, falling back to rawtags. */
	static String buildDomainText(Map<String, ?> sense) {

		var domains = listDomains(sense);

		if (domains.isEmpty())
			return "";

		String result = domains.stream()//
				.map(el -> translateTermCap(el))//
				.collect(Collectors.joining(", "));
		return " <i>[" + result + "]</i> ";
	}

	/**
	 * Returns translated term to the given language, falling back to received one.
	 * Not escaped for xml.
	 */
	static String translateRaw(Object term) {
		String strTerm = term == null ? null : term.toString().trim();

		if (strIsEmpty(strTerm))
			return "";

		String result = null;
		// TODO: read mapping from a parameter file, using language stored in global
		// langTo

		// by default return the received translateTerm
		return result != null ? result : strTerm;
	}

	/**
	 * Returns translated term, falling back to received one. Not escaped for xml.
	 */
	static String translateTerm(Object term) {
		String result = translateRaw(term);
		return encodeEntities(false, result);
	}

	/**
	 * Returns translated term returning with capital first, falling back to
	 * received one.
	 */
	static String translateTermCap(Object term) {
		String result = translateRaw(term);

		// by default return the received translateTerm
		result = result == null ? result : result.substring(0, 1).toUpperCase() + result.substring(1);

		return encodeEntities(false, result);
	}

	/** Collapses internal newlines and tabs — TSV requires one line per entry. */
	static String sanitize(Object content) {
		if (content == null)
			return "";
		return content.toString()//
				.replace("\n", " ")//
				.replace("\t", " ");
	}

	/** Strips wiki markup: [[target|display]] → display, [[target]] → target. */
	static String stripWiki(boolean applyEscapes, String wiki) {
		if (wiki == null)
			return "";

		String string = wiki.toString().stripLeading();
		string = string.replaceAll("\\[\\[(?:[^|\\]]*\\|)?([^\\]]+)\\]\\]", "$1");
		string = string.replace("[[", "").replace("]]", "");
		return applyEscapes ? encodeEntities(true, string) : string;
	}

	static String getStr(Map<String, ?> map, String key) {
		Object entry = map.get(key);
		if (entry instanceof List<?>) {
			entry = ((List<?>) entry).isEmpty() ? null : ((List<?>) entry).get(0).toString();
		}
		return entry instanceof String s ? s : null;
	}

	@SuppressWarnings("unchecked")
	static <T> List<T> getList(Map<String, ?> map, String key, T defltValue) {
		Object entry = map.get(key);

		if (entry == null) {
			return defltValue == null ? List.of() : List.of(defltValue);
		} else if (entry instanceof List) {
			return (List<T>) entry;
		} else {
			return List.of((T) entry);
		}
	}

	static String stripChar(String text, char... characters) {
		if (text == null)
			return null;

		for (String orig = text; !orig.equals(text);) {
			for (char ch : characters) {
				text = text.charAt(0) == ch ? text.substring(1).stripLeading() : text;
			}
		}
		return text;
	}

	static boolean strIsEmpty(String text) {
		return text == null || text.isBlank();
	}

	static boolean listIsEmpty(Collection<?> coll) {
		return coll == null || coll.isEmpty();
	}

	// --- Argument parser ---

	static Map<String, String> parseArgs(String[] args) {
		var map = new LinkedHashMap<String, String>();
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
			case "--config", "-c" -> map.put("config", args[++i]);
			case "--in", "-i" -> map.put("in", args[++i]);
			case "--out", "-o" -> map.put("out", args[++i]);
			case "--lang", "-l" -> map.put("lang", args[++i]);
			case "--embedded-defs", "-e" -> map.put("embedded-defs", args[++i]);
			default -> err(String.format("Warning: unknown argument '%s', ignoring.", args[i]));
			}
		}
		return map;
	}

	static String usage() {
		return """
				Convert dictionary data from Kaikki sources to TSV

				Usage: kaikki-2-tab --in|-i <input.jsonl[.gz]> --out|-o <output.tsv|-> --lang|-l <code[,code...]>
				                   [--embedded-defs|-e KEEP|SEPARATE|BOTH] [--config|-c <config.properties>]

				--in  accepts .jsonl or .jsonl.gz files.
				--out accepts a file path or - for stdout.
				--lang accepts one or more comma-separated ISO 639-1 language codes."
				       First language shall be the 'From', the second shall be the 'To'.
				       By default 'To' shall be same 'From'.
				--embedded-defs controls how expressions are emitted (default: BOTH):
				    KEEP     — expressions appear only embedded inside the parent word's definition.
				    SEPARATE — expressions are emitted only as their own standalone entries.
				    BOTH     — both embedded and standalone (default).
				CLI arguments override values from the config file (namespace: kaikki2tab.*).

				Example:
				  kaikki-2-tab -i data/kaikki/pt-extract.jsonl -o data/out/kaikki-pt.tsv -l pt
				  kaikki-2-tab -i data/kaikki/pt-extract.jsonl -o - -l pt -e SEPARATE | tab-2-xdxf -i - ...
				  kaikki-2-tab -c kaikki.properties
				""";
	}

}
