package shared;

import static shared.XmlHelper.cleanHtml;
import static shared.XmlHelper.encodeEntities;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

public class XdxfHelper {

	// --- XDXF writer ---

	public static void writeXdxf(List<XdxfEntry> entries, String bookname, String lang, Path outFile,
			Consumer<String> logger) throws Exception {
		try (var out = new FileOutputStream(outFile.toFile())) {
			writeXdxf(entries, bookname, lang, out);
		}
		logger.accept(String.format("%d entries written to %s.", entries.size(), outFile));
	}

	public static void writeXdxf(List<XdxfEntry> entries, String bookname, String lang, OutputStream stream)
			throws Exception {
		int count = 0;
		try (var writer = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {

			writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
			writer.write("<xdxf lang_from=\"" + lang + "\" lang_to=\"" + lang + "\" format=\"visual\">\n");
			writer.write("<full_name>" + encodeEntities(false, bookname) + "</full_name>\n");
			writer.write("<description>" + encodeEntities(false, bookname) + "</description>\n");

			for (XdxfEntry entry : entries) {
				String def = cleanHtml(entry.definition());
				def = def.replace("\n", " ");
				def = autoCloseTags(def);

				writer.write("<ar><k>" + encodeEntities(false, entry.word()) + "</k>" + def + "</ar>\n");
				count++;
			}
			writer.write("</xdxf>\n");
		}
	}

	public record XdxfEntry(String word, String definition) {
	}

	public record ParsedEntry(String word, byte[] definition) {
		public int serialisedLength() {
			return 2 + word.getBytes(StandardCharsets.UTF_8).length + 1 + definition.length + 1;
		}
	}

	/** Validate Xdxf content */
	public static boolean validateXdxf(Path outFile, boolean deleteFileExitOnError, Consumer<String> logger)
			throws Exception {

		boolean success;
		try {
			var factory = javax.xml.parsers.SAXParserFactory.newInstance();
			var handler = new org.xml.sax.helpers.DefaultHandler() {
				@Override
				public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
					throw e;
				}

				@Override
				public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
					throw e;
				}
			};

			SAXParser saxParser = factory.newSAXParser();
			saxParser.setProperty("http://www.oracle.com/xml/jaxp/properties/maxGeneralEntitySizeLimit", 100_000_000);
			saxParser.setProperty("http://www.oracle.com/xml/jaxp/properties/totalEntitySizeLimit", 100_000_000);

			saxParser.parse(outFile.toFile(), handler);
			logger.accept("XML validation: OK");
			success = true;
		} catch (org.xml.sax.SAXParseException e) {
			success = false;
			logger.accept(String.format("XML validation FAILED at line %d, column %d: %s", e.getLineNumber(),
					e.getColumnNumber(), e.getMessage()));
		} catch (Exception e) {
			success = false;
			logger.accept(String.format("XML validation FAILED: %s", e.getMessage()));
		}

		if (!success && deleteFileExitOnError) {
			try {
				java.nio.file.Files.deleteIfExists(outFile);
			} catch (Exception ignored) {
				logger.accept("Output file delete failed: " + ignored.getMessage());
			}
			logger.accept("Output file deleted.");
			System.exit(1);
		}

		return success;
	}

	/**
	 * Parses Xdxf data from the given {@link InputStream} into a lost of
	 * {@link ParsedEntry}
	 */
	public static List<ParsedEntry> parseXdxf(Function<CharSequence, byte[]> funcHtml2bytes, InputStream stream)
			throws Exception {

		var factory = SAXParserFactory.newInstance();
		factory.setValidating(false);
		factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

		var entries = new ArrayList<ParsedEntry>();
		var headwords = new ArrayList<String>();
		var defRaw = new StringBuilder();
		var kBuf = new StringBuilder();
		var state = new boolean[2]; // [0]=inAr, [1]=inK

		SAXParser saxParser = factory.newSAXParser();
		saxParser.setProperty("http://www.oracle.com/xml/jaxp/properties/maxGeneralEntitySizeLimit", 100_000_000);
		saxParser.setProperty("http://www.oracle.com/xml/jaxp/properties/totalEntitySizeLimit", 100_000_000);

		saxParser.parse(stream, new DefaultHandler() {
			@Override
			public void startElement(String u, String l, String qName, Attributes a) {
				switch (qName.toLowerCase()) {
				case "ar" -> {
					state[0] = true;
					headwords.clear();
					defRaw.setLength(0);
				}
				case "k" -> {
					state[1] = true;
					kBuf.setLength(0);
				}
				default -> {
					if (state[0] && !state[1])
						defRaw.append('<').append(qName.toLowerCase()).append('>');
				}
				}
			}

			@Override
			public void endElement(String u, String l, String qName) {
				switch (qName.toLowerCase()) {
				case "ar" -> {
					if (!headwords.isEmpty() && defRaw.length() > 0) {
						byte[] def = funcHtml2bytes.apply(defRaw.toString().trim());
						for (String w : headwords)
							if (!w.isBlank())
								entries.add(new ParsedEntry(w.trim(), def));
					}
					state[0] = false;
				}
				case "k" -> {
					headwords.add(kBuf.toString());
					state[1] = false;
				}
				default -> {
					if (state[0] && !state[1])
						defRaw.append("</").append(qName.toLowerCase()).append('>');
				}
				}
			}

			@Override
			public void characters(char[] ch, int start, int length) {
				if (state[1])
					kBuf.append(ch, start, length);
				else if (state[0]) {
					String t = new String(ch, start, length);
					defRaw.append(t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
				}
			}

			@Override
			public InputSource resolveEntity(String pub, String sys) {
				return new InputSource(new StringReader(""));
			}
		});
		return entries;
	}

	static String autoCloseTags(String def) {

		def = def // Auto-close unclosed <li> (HTML implicit closing behaviour)
				.replace("<li><li>", "<li></li><li>") //
				.replace("<li></ul>", "<li></li></ul>") //
				.replace("<li></ol>", "<li></li></ol>");
		def = def // Place newlines between entries of lists
				.replace("</li><li>", "</li>\n<li>") //
				.replace("<ol><li>", "<ol>\n<li>") //
				.replace("<ul><li>", "<ul>\n<li>") //
				.replace("</ol><li>", "</ol>\n<li>");

		var sb = new StringBuilder(def.length());
		int i = 0;
		while (i < def.length()) {
			int pIdx = def.indexOf("<p>", i);
			if (pIdx == -1) {
				sb.append(def, i, def.length());
				break;
			}
			sb.append(def, i, pIdx);
			if (pIdx > 0 && sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n')
				sb.append('\n');
			sb.append("<p>");
			i = pIdx + 3;
		}
		def = sb.toString();
		return def;
	}
}
