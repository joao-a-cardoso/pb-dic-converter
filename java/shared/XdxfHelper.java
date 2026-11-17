package shared;

import static shared.TextHelper.lower;
import static shared.XmlHelper.cleanHtml;
import static shared.XmlHelper.encodeEntities;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

public final class XdxfHelper {

	private XdxfHelper() {
		// static members only: not instantiable (Effective Java, Item 4)
	}

	// -- Data Structures -----------------------

	public record XdxfHeader(String name, String description, String lang, String langTo, String format) {
		public XdxfHeader() {
			this(null, null, null, null, null);
		}

		public XdxfHeader withName(String val) {
			return new XdxfHeader(val, this.description, this.lang, this.langTo, format);
		}

		public XdxfHeader withDescription(String val) {
			return new XdxfHeader(this.name, val, this.lang, this.langTo, format);
		}

		public XdxfHeader withLang(String val) {
			return new XdxfHeader(this.name, this.description, val, this.langTo, format);
		}

		public XdxfHeader withLangTo(String val) {
			return new XdxfHeader(name, description, lang, val, format);
		}

		public XdxfHeader withFormat(String val) {
			return new XdxfHeader(name, description, lang, langTo, val);
		}

		public String langTo() {
			return (langTo == null || langTo.isBlank()) ? lang() : langTo;
		}

		public String description() {
			return (description == null || description.isBlank()) ? name() : description;
		}
	}

	public record XdxfEntry(String word, String definition) {
	}

	/**
	 * A headword with its definition already converted to bytes (e.g. SDIC).
	 *
	 * <p>
	 * Records compare array components by identity, so {@code equals},
	 * {@code hashCode} and {@code toString} are overridden to use the array
	 * <em>contents</em>. The array is shared, not copied (copying hundreds of
	 * thousands of definitions would be costly): callers must not modify it.
	 */
	public record ParsedEntry(String word, byte[] definition) {
		public int serialisedLength() {
			return 2 + word.getBytes(StandardCharsets.UTF_8).length + 1 + definition.length + 1;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof ParsedEntry other && word.equals(other.word)
					&& Arrays.equals(definition, other.definition);
		}

		@Override
		public int hashCode() {
			return 31 * word.hashCode() + Arrays.hashCode(definition);
		}

		@Override
		public String toString() {
			return "ParsedEntry[word=" + word + ", definition=" + definition.length + " bytes]";
		}
	}

	@FunctionalInterface
	public interface HtmlCompiler {
		byte[] compile(CharSequence cseq);
	}

	// -- XDXF Processing Methods --

	/**
	 * A new {@link XdxfWriter} on {@code stream}; writes the XDXF header at once.
	 * The caller opened the stream and closes it.
	 */
	public static XdxfWriter openXdxfWriter(XdxfHeader header, OutputStream stream, Consumer<String> logger)
			throws IOException {
		return new XdxfWriter(header, stream, logger);
	}

	/**
	 * Writes an XDXF entry by entry, so the entries need not be kept in memory:
	 * {@link XdxfHelper#openXdxfWriter} writes the header, {@link #add} one
	 * entry (cleaned with {@code XmlHelper.cleanHtml}), {@link #end} the closing
	 * tag. The caller opened the stream and closes it.
	 */
	public static final class XdxfWriter {
		private final BufferedWriter writer;
		private final Consumer<String> logger;
		private int count;

		/** Only through {@link XdxfHelper#openXdxfWriter}. */
		private XdxfWriter(XdxfHeader header, OutputStream stream, Consumer<String> logger) throws IOException {
			this.logger = Objects.requireNonNull(logger, "logger");
			header = header.withFormat("visual"); // Constant, for now
			writer = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8));
			writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
			writer.write("<xdxf lang_from=\"" + header.lang() + "\" lang_to=\"" + header.langTo() + "\" format=\""
					+ header.format() + "\">\n");
			writer.write("<full_name>" + encodeEntities(false, header.name()) + "</full_name>\n");
			writer.write("<description>" + encodeEntities(false, header.description()) + "</description>\n");
		}

		public void add(XdxfEntry entry) throws IOException {
			String def = cleanHtml(entry.definition());
			def = def.replace("\n", " ");
			def = autoCloseTags(def);

			writer.write("<ar><k>" + encodeEntities(false, entry.word()) + "</k>" + def + "</ar>\n");
			count++;
		}

		/** Writes the closing tag and flushes (doesn't close: the stream belongs to the caller). */
		public void end() throws IOException {
			writer.write("</xdxf>\n");
			writer.flush();
			logger.accept(String.format("%d entries written to %s.", count, "output"));
		}
	}

	/**
	 * Checks that {@code xml} is well-formed XML; logs "XML validation: OK" or
	 * where it failed. The caller opened the stream and closes it.
	 *
	 * @return whether it is valid
	 */
	public static boolean validateXdxf(InputStream xml, Consumer<String> logger) {

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

			saxParser.parse(xml, handler);
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
		return success;
	}

	/**
	 * Parses Xdxf data from the given {@link InputStream} into a lost of
	 * {@link ParsedEntry}
	 */
	public static int parseXdxf(InputStream stream, HtmlCompiler compilerHtml2bytes,
			Consumer<XdxfHeader> consumerHeader, Consumer<ParsedEntry> consumerEntries) throws Exception {

		enum PState {
			AR, K, HDR
		}

		var factory = SAXParserFactory.newInstance();
		factory.setValidating(false);
		factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

		var atmHeader = new AtomicReference<XdxfHeader>(new XdxfHeader());
		var atmCountEntries = new AtomicInteger(0);
		var headwords = new ArrayList<String>();
		var bufHdr = new StringBuilder();
		var bufDef = new StringBuilder();
		var bufKey = new StringBuilder();
		var states = new HashSet<PState>();

		SAXParser saxParser = factory.newSAXParser();
		saxParser.setProperty("http://www.oracle.com/xml/jaxp/properties/maxGeneralEntitySizeLimit", 100_000_000);
		saxParser.setProperty("http://www.oracle.com/xml/jaxp/properties/totalEntitySizeLimit", 100_000_000);

		saxParser.parse(stream, new DefaultHandler() {
			@Override
			public void startElement(String u, String l, String qName, Attributes a) {
				switch (lower(qName)) {
				case "xdxf" -> {
					var wrk = atmHeader.get();
					wrk = wrk.withLang(a.getValue("lang_from"));
					wrk = wrk.withLangTo(a.getValue("lang_to"));
					wrk = wrk.withFormat(a.getValue("format"));
					atmHeader.set(wrk);
				}
				case "full_name" -> {
					states.add(PState.HDR);
					bufHdr.setLength(0);
				}
				case "description" -> {
					states.add(PState.HDR);
					bufHdr.setLength(0);
				}
				case "ar" -> {
					states.add(PState.AR);
					headwords.clear();
					bufDef.setLength(0);
				}
				case "k" -> {
					states.add(PState.K);
					bufKey.setLength(0);
				}
				default -> {
					if (states.contains(PState.AR) && !states.contains(PState.K))
						bufDef.append('<').append(lower(qName)).append('>');
				}
				}
			}

			@Override
			public void endElement(String u, String l, String qName) {
				switch (lower(qName)) {
				case "full_name" -> {
					atmHeader.set(atmHeader.get().withName(bufHdr.toString()));
					states.remove(PState.HDR);
				}
				case "description" -> {
					atmHeader.set(atmHeader.get().withDescription(bufHdr.toString()));
					states.remove(PState.HDR);
				}
				case "ar" -> {
					if (!headwords.isEmpty() && bufDef.length() > 0) {
						byte[] def = compilerHtml2bytes.compile(bufDef.toString().trim());
						for (String hw : headwords)
							if (!hw.isBlank()) {
								// give the entry to the caller for it to process
								consumerEntries.accept(new ParsedEntry(hw.trim(), def));
								atmCountEntries.incrementAndGet();
							}
					}
					states.remove(PState.AR); 
				}
				case "k" -> {
					headwords.add(bufKey.toString());
					states.remove(PState.K); 
				}
				default -> {
					if (states.contains(PState.AR) && !states.contains(PState.K)) {
						bufDef.append("</").append(lower(qName)).append('>');
					}
				}
				}
			}

			@Override
			public void characters(char[] ch, int start, int length) {
				if (states.contains(PState.HDR)) { 
					bufHdr.append(ch, start, length);
				} else if (states.contains(PState.K)) {
					bufKey.append(ch, start, length);
				} else if (states.contains(PState.AR)) {
					String t = new String(ch, start, length);
					bufDef.append(t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
				}
			}

			@Override
			public InputSource resolveEntity(String pub, String sys) {
				return new InputSource(new StringReader(""));
			}
		});

		if (consumerHeader != null) {
			consumerHeader.accept(atmHeader.get());
		}

		return atmCountEntries.intValue();
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
