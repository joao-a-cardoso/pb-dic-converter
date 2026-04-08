package shared;

import java.util.Set;

public class XmlHelper {

	static final Set<String> ALLOWED_TAGS = Set.of("p", "b", "i", "u", "em", "strong", "big", "small", "ol", "ul", "li",
			"sub", "sup", "br", "a", "div", "span", "table", "tr", "td", "th");
	static final Set<String> VOID_TAGS = Set.of("br", "hr", "img");

	/**
	 * Escapes XML special characters in the given string.
	 *
	 * When {@code keepTags} is false, all {@code &}, {@code <}, {@code >} and
	 * {@code "} are escaped unconditionally — safe for plain text content.
	 *
	 * When {@code keepTags} is true, tags in {@link #ALLOWED_TAGS} are preserved as
	 * real markup (attributes stripped), void tags in {@link #VOID_TAGS} are
	 * self-closed, and unrecognised tags are escaped as {@code &lt;}. Bare
	 * {@code &} characters are always escaped to {@code &amp;}.
	 */
	public static String encodeEntities(boolean keepHtml, String text) {
		if (text == null)
			return "";

		return encodeEntitiesImpl(text, keepHtml, false);
	}

	/**
	 * Escapes XML special characters in the given string.
	 *
	 * When {@code keepTags} is false, all {@code &}, {@code <}, {@code >} and
	 * {@code "} are escaped unconditionally — safe for plain text content.
	 *
	 * When {@code keepTags} is true, tags in {@link #ALLOWED_TAGS} are preserved as
	 * real markup (attributes stripped), void tags in {@link #VOID_TAGS} are
	 * self-closed, and unrecognised tags are escaped as {@code &lt;}. Bare
	 * {@code &} characters are always escaped to {@code &amp;}.
	 *
	 * {@code "} characters are not escaped. Useful when the content will be placed
	 * in element body rather than an attribute value.
	 */
	public static String encodeEntitiesText(boolean keepHtml, String text) {
		return encodeEntitiesImpl(text, keepHtml, true);
	}

	/**
	 * Base implementation for {@link #encodeEntities(boolean, String)} and
	 * {@link #encodeEntitiesText(boolean, String)}
	 */
	private static String encodeEntitiesImpl(String text, boolean keepHtml, boolean keepQuotes) {
		// Fast path — no tag awareness needed
		if (!keepHtml) {
			String result = text.replace("&", "&amp;")//
					.replace("<", "&lt;")//
					.replace(">", "&gt;");
			result = keepQuotes ? result : result.replace("\"", "&quot;");
			return result;
		}

		var sb = new StringBuilder(text.length());
		int i = 0;
		while (i < text.length()) {
			char ch = text.charAt(i);

			if (ch == '<') {
				// Look for the closing '>' of this tag
				int end = text.indexOf('>', i + 1);
				int nxt = text.indexOf('<', i + 1); // next "<"
				if (end == -1 || (nxt != -1 && nxt < end)) {
					// found a '<' before next '>'
					// Unclosed '<' — treat as literal text
					sb.append("&lt;");
					i++;
					continue;
				}

				String inner = text.substring(i + 1, end).trim();
				boolean closing = inner.startsWith("/");

				// Extract tag name, ignoring attributes and self-close slash
				String tag = (closing ? inner.substring(1) : inner).split("[\\s/]")[0].toLowerCase();

				if (ALLOWED_TAGS.contains(tag)) {
					if (VOID_TAGS.contains(tag))
						// Void elements are always self-closed (e.g. <br/>)
						sb.append("<").append(tag).append("/>");
					else if (closing)
						sb.append("</").append(tag).append(">");
					else
						// Opening tag — attributes are stripped for safety
						sb.append("<").append(tag).append(">");
				} else {
					// Unrecognised tag — escape the '<' and reprocess from next char
					sb.append("&lt;");
					i++;
					continue;
				}
				i = end + 1;

			} else if (ch == '&') {
				// Always escape bare ampersands to avoid malformed entities
				sb.append("&amp;");
				i++;

			} else if (ch == '"' && !keepQuotes) {
				sb.append("&quot;");
				i++;

			} else {
				sb.append(ch);
				i++;
			}
		}
		return sb.toString();
	}

	public static String decodeEntities(String s) {
		return s.replace("&amp;", "&")//
				.replace("&lt;", "<")//
				.replace("&gt;", ">")//
				.replace("&quot;", "\"")//
				.replace("&apos;", "'")//
				.replace("&nbsp;", " ");
	}

	public static String cleanHtml(String html) {

		html = html//
				.replace("&nbsp;", " ")//
				.replace("&emsp;", " ")//
				.replace("&ensp;", " ")//
				.replace("&thinsp;", " ")//
				.replace("&mdash;", "—")//
				.replace("&ndash;", "–")//
				.replace("&laquo;", "«")//
				.replace("&raquo;", "»")//
				.replace("&ldquo;", "\u201C")//
				.replace("&rdquo;", "\u201D")//
				.replace("&lsquo;", "\u2018")//
				.replace("&rsquo;", "\u2019")//
				.replace("&hellip;", "…")//
				.replace("&bull;", "•")//
				.replace("&middot;", "·")//
				.replace("&times;", "×")//
				.replace("&divide;", "÷")//
				.replace("&deg;", "°")//
				.replace("&acute;", "´")//
				.replace("&cedil;", "¸");

		html = html//
				.replaceAll("(<[a-zA-Z]+>)[\\s]+", "$1") //
				.replaceAll("[\\s]+(</[a-zA-Z]+>)", "$1");

		return html;
		/*-
		var sb = new StringBuilder(html.length());
		int i = 0;
		int pCount = 0;
		while (i < html.length()) {
			char c = html.charAt(i);
			if (c == '<') {
				int end = html.indexOf('>', i);
				if (end == -1) {
					sb.append("&lt;");
					i++;
					continue;
				}
		
				String inner = html.substring(i + 1, end).trim();
				boolean closing = inner.startsWith("/");
				String tagName = (closing ? inner.substring(1) : inner).split("[\\s/]")[0].toLowerCase();
		
				if (ALLOWED_TAGS.contains(tagName)) {
					if (VOID_TAGS.contains(tagName)) {
						sb.append("<").append(tagName).append("/>");
					} else if (closing) {
						sb.append("</").append(tagName).append(">");
					} else {
						if (tagName.equals("p")) {
							if (pCount > 0)
								sb.append('\n');
							pCount++;
						}
						sb.append("<").append(tagName).append(">");
					}
				}
		
				i = end + 1;
			} else if (c == '&') {
				int semi = html.indexOf(';', i);
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
				while (next < html.length() && html.charAt(next) != '<' && html.charAt(next) != '&')
					next++;
				String text = html.substring(i, next).replace("\u00a0", " ").replace("\r", "");
				sb.append(escapeXmlText(text, false));
				i = next;
			}
		}
		return sb.toString();
		*/
	}

}
