import static shared.Constants.UC_BULLET;
import static shared.TextHelper.lower;
import static shared.XmlHelper.encodeEntities;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
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

import shared.ArgsHelper;
import shared.TermTranslator;
import shared.IoHelper;
import shared.CliException;
import shared.ToolLog;
import shared.ToolRunner;
import shared.JsonException;
import shared.JsonParser;

/**
 * kaikki-2-tab — convert a Kaikki (Wiktionary) JSONL dump to TSV
 * ({@code word<TAB>definition}, definitions in HTML), the input of
 * {@code tab-2-xdxf}.
 *
 * <p>
 * For usage, options and examples run {@code linux/kaikki-2-tab --help}.
 *
 * <p>
 * <b>Entry handling:</b>
 * <ul>
 * <li>Form-of entries (conjugations, plurals) get a short definition</li>
 * <li>Regular entries include: part of speech, gender, plural forms (nouns
 * only), etymology, numbered senses with domain labels and examples, and
 * expressions</li>
 * <li>Verb entries suppress the {@code forms} list to avoid showing
 * conjugations</li>
 * <li>Domain labels are taken from {@code topics}, falling back to
 * {@code raw_tags}</li>
 * <li>Expressions are emitted inside the parent definition, as standalone
 * entries, or both, according to {@code --embedded-defs}</li>
 * <li>Entries with no senses are skipped</li>
 * </ul>
 */
public class ConvKaikki2Tab {

	static final String TOOL = "kaikki-2-tab";
	private static final ToolLog LOG = new ToolLog(TOOL);

	/** How expressions are emitted ({@code -e / --embedded-defs}). */
	enum EmbeddedDefs {

		//       in the parent's definition  as their own entries
		KEEP    (true,                       false),
		SEPARATE(false,                      true),
		BOTH    (true,                       true);

		final boolean embedded;
		final boolean separate;

		EmbeddedDefs(boolean embedded, boolean separate) {
			this.embedded = embedded;
			this.separate = separate;
		}

		/** The mode named {@code value}, ignoring case; otherwise a usage error. */
		static EmbeddedDefs parse(String value) {
			for (EmbeddedDefs mode : values()) {
				if (mode.name().equalsIgnoreCase(value)) {
					return mode;
				}
			}
			throw CliException.error("Error: --embedded-defs must be KEEP, SEPARATE or BOTH, got: %s", value);
		}
	}

	// ── main ──────────────────────────────────────────────────────────────────
	public static void main(String[] args) {
		ToolRunner.run(TOOL, ConvKaikki2Tab::usage, () -> run(args));
	}

	/** The tool's logic; ends with a {@link CliException} instead of {@code System.exit}. */
	static void run(String[] args) throws Exception {
		if (args.length == 0) { // -h / --help is handled by ArgsHelper.parse
			throw new CliException(null, 1, true);
		}

		var cli = parseArgs(args);

		// Load config file if specified
		var argsHlp = new ArgsHelper(TOOL, "kaikki2tab", er -> LOG.err(er), () -> usage());

		Properties config = argsHlp.loadConfig(cli);
		argsHlp.mergeConfig2Cli(List.of("in", "out", "lang", "embedded-defs", "terms-file"), cli, config);

		String input = argsHlp.require(cli, "in", "--in/-i");
		String output = argsHlp.require(cli, "out", "--out/-o");
		String lang = argsHlp.requireLang(cli, "lang", "--lang/-l");
		boolean termFileGiven = cli.containsKey("terms-file");
		Path termFile = Path.of(argsHlp.resolve(cli, "terms-file", defaultTermFile(lang).toString()));

		EmbeddedDefs embeddedDefs = argsHlp.resolveParsed(EmbeddedDefs::parse, cli, "embedded-defs", EmbeddedDefs.BOTH);
		final boolean emitEmbedded = embeddedDefs.embedded;
		final boolean emitSeparate = embeddedDefs.separate;

		boolean toStdout = "-".equals(output);

		Path inFile = Path.of(input);

		if (!Files.exists(inFile)) {
			throw CliException.error("Error: file not found: %s", ArgsHelper.displayPath(input));
		}

		argsHlp.listProperties(cli);
		TermTranslator terms = loadTermTranslations(termFile, termFileGiven);

		int count = 0, filtered = 0, skipped = 0, duplicates = 0;
		List<String> lstFilteredLang = new ArrayList<>();
		// Lines "word\tdef" — only drop entries where both headword AND content are
		// identical
		var seenEntries = new SeenLines();
		var seenWords = new HashSet<String>();
		var affectedWords = new HashSet<String>();
		// .gz input is decompressed by IoHelper; "-" output is stdout
		// the output (file or stdout) is written only at the end, when everything
		// succeeded: on an error an old file is kept, a pipe gets nothing
		try (InputStream inStream = IoHelper.openInput(input);
				var out = IoHelper.openSafeOutput(output);
				var reader = new BufferedReader(new InputStreamReader(inStream, StandardCharsets.UTF_8));
				var writer = new BufferedWriter(new OutputStreamWriter(out.stream(), StandardCharsets.UTF_8))) {

			Map<String, CharSequence> mapExprsToEmitSeparate = null;

			String line;
			int lineNo = 0;
			while ((line = reader.readLine()) != null) {
				lineNo++;
				line = line.strip();
				if (strIsEmpty(line))
					continue;
				try {
					Map<String, ?> entry = new JsonParser(line).parseObject();

					// Filter by language
					String wordLang = getStr(entry, "lang_code");
					if (!lang.equalsIgnoreCase(wordLang)) {
						filtered++;
						if (!lstFilteredLang.contains(wordLang)) {
							lstFilteredLang.add(wordLang == null ? "<null>" : lower(wordLang));
						}
						continue;
					}

					String word = getStr(entry, "word");
					if (strIsEmpty(word)) {
						skipped++;
						continue;
					}

					// Main entry
					String def = buildDefinition(terms, word, entry, emitEmbedded);
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
						mapExprsToEmitSeparate.putAll(prepareExpressionsToEmit(terms, entry, word));
					} // end if (emitSeparate)

				} catch (JsonException e) {
					// bad input: skip the line; anything else is a bug and stops the run (ToolRunner)
					LOG.err(String.format("Warning: skipping malformed line %d: %s", lineNo, e.getMessage()));
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
			writer.flush();
			out.commit();
		} // main try

		LOG.err(String.format(
				"%d entries written, %d filtered (wrong languages: %s), %d exact-content duplicates dropped (%d headwords affected), %d skipped (blank/error).",
				count, filtered, lstFilteredLang.size(), duplicates, affectedWords.size(), skipped));
	}

	// --- Definition builder ---

	static String buildDefinition(TermTranslator terms, String word, Map<String, ?> entry, boolean emitEmbedded) {

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
					posTitle = translateTermCap(terms, posTitle);
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
		posTitle = properNounTitle(terms, entry, posTitle, senses);
		String gender = tags.contains("masculine") ? "masculine" : tags.contains("feminine") ? "feminine" : null;

		if (posTitle != null) {
			sbDef.append("<p>" + formatTitle(true, posTitle));
			if (gender != null)
				sbDef.append(" (").append(translateTerm(terms, gender)).append(")");
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
				sbDef.append("<p>" + formatLabel(terms, "plural"))
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
				// the tag "IPA" is not a region (every transcription is IPA): no label for it
				boolean label = renderTag && !snd.getKey().isEmpty() && !snd.getKey().equalsIgnoreCase("IPA");
				ipas.add(snd.getValue().get(0) + (label ? "<i><small>(" + snd.getKey() + ")</small></i>" : ""));
			}

			if (!ipas.isEmpty()) {
				sbDef.append("<p>" + formatLabel(terms, "sounds"))
						.append(encodeEntities(true, String.join(", ", ipas)))//
						.append("</p>");
			}
		}

		// 4. Senses
		sbDef.append("<ol>");
		for (var sense : senses) {

			sbDef.append("<li>");
			sbDef.append(buildDomainText(terms, sense));

			// Glosses
			String gloss = extractGloss(sense);
			if (!strIsEmpty(gloss)) {
				sbDef.append(encodeEntities(false, gloss));
			}

			// Example
			sbDef.append(buildExampleText(terms, sense, word, plurals));
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
					.append(formatLabel(terms, "etymology"))//
					.append(encodeEntities(false, etym))//
					.append("</p>");
		}

		// 6. Expressions (embedded)
		if (emitEmbedded) {
			List<String> exprTexts = buildEmbeddedExpressionsText(terms, entry, keepExpressionsWithEmptyContent);
			if (!listIsEmpty(exprTexts)) {
				sbDef.append("<p>" + formatTitle(false, translateTermCap(terms, "expressions")) + "</p>")//
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
	private static List<String> buildEmbeddedExpressionsText(TermTranslator terms, Map<String, ?> entry,
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
					sbEmbed.append(buildDomainText(terms, exprSense));

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
	static Map<String, CharSequence> prepareExpressionsToEmit(TermTranslator terms, Map<String, ?> entry, String refWord) {
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
			sb.append("<p>").append(formatTitle(true, translateTermCap(terms, "expression")));
			if (!strIsEmpty(refWord)) {
				sb.append(" (").append(refWord).append(")");
			}
			sb.append("</p>");
			sb.append("<ol>");
			for (var sense : exprSenses) {
				sb.append("<li>");
				sb.append(buildDomainText(terms, sense));

				String gloss = extractGloss(sense);
				if (!strIsEmpty(gloss)) {
					sb.append(encodeEntities(false, gloss));
				}

				sb.append(buildExampleText(terms, sense, exprWord, null));
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

	/** Translation key of the part-of-speech title of proper nouns. */
	static final String PROPER_NOUN_KEY = "proper-noun";

	/**
	 * Part-of-speech title of proper nouns: an entry with pos "name", or a "noun"
	 * whose senses all have the topic "anthroponym" (the source mostly classifies
	 * names as plain nouns), is titled with the translation of "proper-noun". Without
	 * such a translation the source's own title is kept.
	 */
	static String properNounTitle(TermTranslator terms, Map<String, ?> entry, String posTitle, List<Map<String, ?>> senses) {
		String pos = getStr(entry, "pos");
		boolean properNoun = "name".equals(pos) || ("noun".equals(pos) && !listIsEmpty(senses)
				&& senses.stream().allMatch(sense -> getList(sense, "topics", null).stream()
						.anyMatch(t -> "anthroponym".equalsIgnoreCase(String.valueOf(t)))));
		if (!properNoun || !terms.has(PROPER_NOUN_KEY)) {
			return posTitle;
		}
		return terms.translateCap(PROPER_NOUN_KEY);
	}

	/** Inline label: "<i>Label:</i> " — translated, capitalised, colon inside the italics. */
	static String formatLabel(TermTranslator terms, String term) {
		return "<i>" + translateTermCap(terms, term) + ":</i> ";
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
	static String buildExampleText(TermTranslator terms, Map<String, ?> sense, String refWord, List<String> refPlurals) {

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
				.append(translateTerm(terms, "example") + ": ")//
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
	static String buildDomainText(TermTranslator terms, Map<String, ?> sense) {

		var domains = listDomains(sense);

		if (domains.isEmpty())
			return "";

		String result = domains.stream()//
				.map(el -> translateTermCap(terms, el))//
				.collect(Collectors.joining(", "));
		return " <i>[" + result + "]</i> ";
	}


	/**
	 * Default translation file: {@code term-translations_<lang>.properties} in the
	 * folder of this tool's source file (java/), or the current folder when the
	 * source location is unknown.
	 */
	static Path defaultTermFile(String lang) {
		String src = System.getProperty("jdk.launcher.sourcefile");
		Path dir = src != null ? Path.of(src).toAbsolutePath().getParent() : Path.of("");
		return dir.resolve("term-translations_" + lang + ".properties").normalize();
	}

	/**
	 * Loads the label translations and logs the file used. A file given with
	 * --terms-file must exist; a missing default file only means that labels
	 * are not translated.
	 */
	static TermTranslator loadTermTranslations(Path file, boolean given) throws Exception {
		if (!Files.exists(file)) {
			if (given) {
				throw CliException.error("Error: term translation file not found: %s", ArgsHelper.displayPath(file.toString()));
			}
			LOG.err("Term translations: none (%s not found) — labels are not translated", ArgsHelper.displayPath(file.toString()));
			return TermTranslator.none();
		}
		TermTranslator terms = TermTranslator.load(file);
		LOG.err("Term translations: %s (%d terms)", ArgsHelper.displayPath(file.toString()), terms.size());
		return terms;
	}

	/**
	 * Returns translated term, falling back to received one. Not escaped for xml.
	 */
	static String translateTerm(TermTranslator terms, Object term) {
		return encodeEntities(false, terms.translate(term));
	}

	/**
	 * Returns translated term returning with capital first, falling back to
	 * received one.
	 */
	static String translateTermCap(TermTranslator terms, Object term) {
		return encodeEntities(false, terms.translateCap(term));
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

	/**
	 * The set of lines written so far, for dropping exact duplicates, without
	 * keeping the lines themselves: only a 128-bit hash of each (SHA-256,
	 * truncated). Two different lines getting the same hash is practically
	 * impossible (about 10^-27 for a million lines).
	 */
	static final class SeenLines {
		private record Hash(long high, long low) {
		}

		private final Set<Hash> seen = new HashSet<>();
		private final MessageDigest sha256;

		SeenLines() {
			try {
				sha256 = MessageDigest.getInstance("SHA-256");
			} catch (NoSuchAlgorithmException e) {
				throw new IllegalStateException("SHA-256 is required in every Java platform", e);
			}
		}

		/** Adds the line; {@code true} if it was not seen before (like {@link Set#add}). */
		boolean add(String line) {
			var digest = ByteBuffer.wrap(sha256.digest(line.getBytes(StandardCharsets.UTF_8)));
			return seen.add(new Hash(digest.getLong(), digest.getLong()));
		}
	}

	// --- Argument parser ---

	/**
	 * Maps a command line word to its option key, or {@code null} when it is not
	 * an option of this tool. Used by {@link ArgsHelper#parse} both to read options
	 * and to detect an option given where a value was expected.
	 */
	static String optionKey(String arg) {
		return switch (arg) {
		case "--config", "-c"        -> "config";
		case "--def"                 -> ArgsHelper.DEF_OPTION;
		case "--in", "-i"            -> "in";
		case "--out", "-o"           -> "out";
		case "--lang", "-l"          -> "lang";
		case "--embedded-defs", "-e" -> "embedded-defs";
		case "--terms-file"          -> "terms-file";
		default -> null;
		};
	}

	static Map<String, String> parseArgs(String[] args) {
		return ArgsHelper.parse(args, ConvKaikki2Tab::optionKey, er -> LOG.err(er), () -> usage());
	}

	static String usage() {
		return TOOL + ": " + """
				Convert a Kaikki (Wiktionary) JSONL dump to TSV (word<TAB>definition).

				Usage:
				  linux/kaikki-2-tab -i <jsonl[.gz]> -o <tsv> -l <lang> [options]

				Required:
				  -i / --in             Kaikki .jsonl or .jsonl.gz file
				  -o / --out            Output TSV file, or - for stdout
				  -l / --lang           Language of the headwords to keep, as one ISO 639-1
				                        code (e.g. pt, en); entries in other languages are skipped

				Optional:
				  -e / --embedded-defs  How expressions are emitted (default: BOTH):
				                          KEEP     — only embedded in the parent word's definition
				                          SEPARATE — only as their own standalone entries
				                          BOTH     — both embedded and standalone
				  --terms-file          .properties file translating the labels (english-term=translation)
				                        (default: term-translations_<lang>.properties next to this
				                        tool's source, in java/); the file used is logged
				  -c / --config         .properties config file (namespace: kaikki2tab.*);
				                        command-line options override it
				  --def <name>=<value>  Set or override a config variable (def); repeatable
				  -h / --help           Show this help

				Examples:
				  linux/kaikki-2-tab -i data/kaikki/kaikki.org-all-to-Portuguese.jsonl.gz -o data/out/kaikki-pt.tsv -l pt
				  linux/kaikki-2-tab -i data/kaikki/kaikki.org-all-to-Portuguese.jsonl.gz -o - -l pt -e SEPARATE | linux/tab-2-xdxf -i - ...
				  linux/kaikki-2-tab -c confs/kaikki2xdxf-pt-pt.config
				""";
	}

}
