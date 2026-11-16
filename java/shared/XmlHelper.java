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

	/**
	 * Makes an HTML definition safe for XDXF (well-formed XML):
	 * <ul>
	 * <li>common HTML entities ({@code &nbsp;}, {@code &mdash;}, …) become
	 * characters;</li>
	 * <li>tags in {@link #ALLOWED_TAGS} are kept without attributes; void tags are
	 * self-closed ({@code <br/>});</li>
	 * <li>other tags ({@code <html>}, {@code <svg>}, …) are dropped, their text is
	 * kept;</li>
	 * <li>tags are balanced: a closing tag without a matching opening tag is
	 * dropped, tags left open are closed;</li>
	 * <li>{@code &} is kept only for XML entities ({@code &amp; &lt; &gt; &quot;
	 * &apos;} and numeric ones), otherwise escaped; a {@code <} that does not start
	 * a tag is escaped;</li>
	 * <li>whitespace right after an opening tag or before a closing tag is
	 * removed.</li>
	 * </ul>
	 * Well-formed input using only allowed tags without attributes is returned
	 * unchanged (apart from the entity and whitespace rules).
	 */
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

		var sb = new StringBuilder(html.length());
		var open = new java.util.ArrayDeque<String>(); // open allowed tags, innermost first
		int i = 0;
		while (i < html.length()) {
			char ch = html.charAt(i);
			if (ch == '<') {
				var m = TAG.matcher(html).region(i, html.length());
				if (!m.lookingAt()) {
					sb.append("&lt;"); // not a tag: literal '<'
					i++;
					continue;
				}
				i = m.end();
				if (m.group(2) == null) {
					continue; // comment, <!DOCTYPE>, <?…?>: dropped
				}
				boolean closing = !m.group(1).isEmpty();
				String tag = m.group(2).toLowerCase(java.util.Locale.ROOT);
				if (!ALLOWED_TAGS.contains(tag)) {
					continue; // unknown tag: dropped, its text is kept
				}
				if (VOID_TAGS.contains(tag)) {
					if (!closing) {
						sb.append('<').append(tag).append("/>");
					}
				} else if (!closing) {
					if (m.group(3).trim().endsWith("/")) {
						sb.append('<').append(tag).append("/>"); // <p/>: empty element
					} else {
						sb.append('<').append(tag).append('>');
						open.push(tag);
					}
				} else if (open.contains(tag)) {
					// close the inner tags still open, then this one
					String top;
					do {
						top = open.pop();
						sb.append("</").append(top).append('>');
					} while (!top.equals(tag));
				}
				// else: closing tag without opening tag: dropped
			} else if (ch == '&') {
				var m = XML_ENTITY.matcher(html).region(i, html.length());
				if (m.lookingAt()) {
					sb.append(m.group());
					i = m.end();
				} else {
					sb.append("&amp;");
					i++;
				}
			} else {
				sb.append(ch);
				i++;
			}
		}
		while (!open.isEmpty()) {
			sb.append("</").append(open.pop()).append('>');
		}

		return sb.toString()//
				.replaceAll("(<[a-zA-Z]+>)[\\s]+", "$1") //
				.replaceAll("[\\s]+(</[a-zA-Z]+>)", "$1");
	}

	/**
	 * A tag: group 1 = "/" for a closing tag, group 2 = name (null for a comment,
	 * declaration or processing instruction), group 3 = the rest (attributes).
	 */
	private static final java.util.regex.Pattern TAG = java.util.regex.Pattern
			.compile("<(?:!--.*?-->|[!?][^>]*>|(/?)([a-zA-Z][a-zA-Z0-9]*)([^<>]*)>)", java.util.regex.Pattern.DOTALL);

	/** Entities valid in XML without a DTD. */
	private static final java.util.regex.Pattern XML_ENTITY = java.util.regex.Pattern
			.compile("&(?:amp|lt|gt|quot|apos|#[0-9]+|#[xX][0-9a-fA-F]+);");

}
