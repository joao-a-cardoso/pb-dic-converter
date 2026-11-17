package shared;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser for one JSON object per text (e.g. one line of a JSONL
 * file). Objects become {@code Map<String, Object>} (in order), arrays
 * {@code List<Object>}, numbers {@code Double}, booleans {@code Boolean}, null
 * {@code null}.
 *
 * <p>
 * Invalid input — including truncated text and characters after the object —
 * is reported with a {@link JsonException} that carries the position, so
 * callers can tell bad input from bugs.
 */
public class JsonParser {
	private final String s;
	private int pos;

	public JsonParser(String s) {
		this.s = s;
		this.pos = 0;
	}

	/**
	 * Parses the whole text as one JSON object; only whitespace may follow it.
	 *
	 * @throws JsonException if the text is not a valid JSON object
	 */
	public Map<String, Object> parseObject() {
		skipWs();
		Map<String, Object> map = readObject();
		skipWs();
		if (pos < s.length()) {
			throw new JsonException("unexpected characters after the object", pos);
		}
		return map;
	}

	private Object readValue() {
		skipWs();
		return switch (peekRequired()) {
		case '{' -> readObject();
		case '[' -> readArray();
		case '"' -> readString();
		case 't', 'f' -> readBoolean();
		case 'n' -> readNull();
		default -> readNumber();
		};
	}

	private Map<String, Object> readObject() {
		expect('{');
		var map = new LinkedHashMap<String, Object>();
		skipWs();
		if (peekRequired() == '}') {
			pos++;
			return map;
		}
		while (true) {
			skipWs();
			String key = readString();
			skipWs();
			expect(':');
			map.put(key, readValue());
			skipWs();
			char c = next();
			if (c == '}') {
				return map;
			}
			if (c != ',') {
				throw new JsonException("expected ',' or '}' but found '" + c + "'", pos - 1);
			}
		}
	}

	private List<Object> readArray() {
		expect('[');
		var list = new ArrayList<Object>();
		skipWs();
		if (peekRequired() == ']') {
			pos++;
			return list;
		}
		while (true) {
			list.add(readValue());
			skipWs();
			char c = next();
			if (c == ']') {
				return list;
			}
			if (c != ',') {
				throw new JsonException("expected ',' or ']' but found '" + c + "'", pos - 1);
			}
		}
	}

	private String readString() {
		expect('"');
		var sb = new StringBuilder();
		while (true) {
			char c = next();
			if (c == '"') {
				return sb.toString();
			}
			if (c != '\\') {
				sb.append(c);
				continue;
			}
			int escapeAt = pos - 1;
			char e = next();
			switch (e) {
			case '"' -> sb.append('"');
			case '\\' -> sb.append('\\');
			case '/' -> sb.append('/');
			case 'n' -> sb.append('\n');
			case 'r' -> sb.append('\r');
			case 't' -> sb.append('\t');
			case 'b' -> sb.append('\b');
			case 'f' -> sb.append('\f');
			case 'u' -> sb.append(readUnicodeEscape(escapeAt));
			default -> throw new JsonException("invalid escape '\\" + e + "'", escapeAt);
			}
		}
	}

	/** The 4 hex digits after "\\u" (surrogate pairs come as two escapes). */
	private char readUnicodeEscape(int escapeAt) {
		if (pos + 4 > s.length()) {
			throw new JsonException("unexpected end of input in \\u escape", escapeAt);
		}
		int value = 0;
		for (int i = 0; i < 4; i++) {
			int digit = Character.digit(s.charAt(pos++), 16);
			if (digit < 0) {
				throw new JsonException("invalid \\u escape", escapeAt);
			}
			value = value * 16 + digit;
		}
		return (char) value;
	}

	private Double readNumber() {
		int start = pos;
		while (pos < s.length() && "0123456789.-+eE".indexOf(s.charAt(pos)) >= 0) {
			pos++;
		}
		if (pos == start) {
			throw new JsonException("unexpected character '" + s.charAt(pos) + "'", pos);
		}
		String number = s.substring(start, pos);
		try {
			return Double.parseDouble(number);
		} catch (NumberFormatException e) {
			throw new JsonException("invalid number '" + number + "'", start);
		}
	}

	private Boolean readBoolean() {
		if (s.startsWith("true", pos)) {
			pos += 4;
			return Boolean.TRUE;
		}
		if (s.startsWith("false", pos)) {
			pos += 5;
			return Boolean.FALSE;
		}
		throw new JsonException("invalid literal, expected true or false", pos);
	}

	private Object readNull() {
		if (s.startsWith("null", pos)) {
			pos += 4;
			return null;
		}
		throw new JsonException("invalid literal, expected null", pos);
	}

	private void skipWs() {
		while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
			pos++;
		}
	}

	/** The next character, consumed; fails at the end of the input. */
	private char next() {
		if (pos >= s.length()) {
			throw new JsonException("unexpected end of input", pos);
		}
		return s.charAt(pos++);
	}

	/** The next character, not consumed; fails at the end of the input. */
	private char peekRequired() {
		if (pos >= s.length()) {
			throw new JsonException("unexpected end of input", pos);
		}
		return s.charAt(pos);
	}

	private void expect(char expected) {
		char c = next();
		if (c != expected) {
			throw new JsonException("expected '" + expected + "' but found '" + c + "'", pos - 1);
		}
	}
}
