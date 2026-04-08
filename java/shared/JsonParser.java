package shared;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser
 */

public class JsonParser {
	private final String s;
	private int pos;

	public JsonParser(String s) {
		this.s = s;
		this.pos = 0;
	}

	Object parse() {
		skipWs();
		if (pos >= s.length())
			throw new RuntimeException("Unexpected end of input");
		return switch (s.charAt(pos)) {
		case '{' -> parseObject();
		case '[' -> parseArray();
		case '"' -> parseString();
		case 't', 'f' -> parseBoolean();
		case 'n' -> parseNull();
		default -> parseNumber();
		};
	}

	public Map<String, Object> parseObject() {
		expect('{');
		var map = new LinkedHashMap<String, Object>();
		skipWs();
		if (peek() == '}') {
			pos++;
			return map;
		}
		while (true) {
			skipWs();
			String key = parseString();
			skipWs();
			expect(':');
			Object val = parse();
			map.put(key, val);
			skipWs();
			char c = s.charAt(pos++);
			if (c == '}')
				break;
			if (c != ',')
				throw new RuntimeException("Expected ',' or '}' at " + pos);
		}
		return map;
	}

	List<Object> parseArray() {
		expect('[');
		var list = new ArrayList<Object>();
		skipWs();
		if (peek() == ']') {
			pos++;
			return list;
		}
		while (true) {
			list.add(parse());
			skipWs();
			char c = s.charAt(pos++);
			if (c == ']')
				break;
			if (c != ',')
				throw new RuntimeException("Expected ',' or ']' at " + pos);
		}
		return list;
	}

	String parseString() {
		expect('"');
		var sb = new StringBuilder();
		while (pos < s.length()) {
			char c = s.charAt(pos++);
			if (c == '"')
				return sb.toString();
			if (c == '\\') {
				char e = s.charAt(pos++);
				switch (e) {
				case '"' -> sb.append('"');
				case '\\' -> sb.append('\\');
				case '/' -> sb.append('/');
				case 'n' -> sb.append('\n');
				case 'r' -> sb.append('\r');
				case 't' -> sb.append('\t');
				case 'b' -> sb.append('\b');
				case 'f' -> sb.append('\f');
				case 'u' -> {
					String hex = s.substring(pos, pos + 4);
					pos += 4;
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
		while (pos < s.length() && "0123456789.-+eE".indexOf(s.charAt(pos)) >= 0)
			pos++;
		return Double.parseDouble(s.substring(start, pos));
	}

	Boolean parseBoolean() {
		if (s.startsWith("true", pos)) {
			pos += 4;
			return Boolean.TRUE;
		}
		if (s.startsWith("false", pos)) {
			pos += 5;
			return Boolean.FALSE;
		}
		throw new RuntimeException("Invalid boolean at " + pos);
	}

	Object parseNull() {
		if (s.startsWith("null", pos)) {
			pos += 4;
			return null;
		}
		throw new RuntimeException("Invalid null at " + pos);
	}

	void skipWs() {
		while (pos < s.length() && Character.isWhitespace(s.charAt(pos)))
			pos++;
	}

	void expect(char c) {
		if (s.charAt(pos++) != c)
			throw new RuntimeException("Expected '" + c + "' at " + (pos - 1));
	}

	char peek() {
		return pos < s.length() ? s.charAt(pos) : 0;
	}
}