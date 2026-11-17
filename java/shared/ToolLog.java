package shared;

import java.util.Objects;

/**
 * Messages of a tool to stderr, prefixed with the tool name ("tab-2-xdxf: …").
 * The only place of the prefix rule, used by the tools, {@link ArgsHelper} and
 * {@link ToolRunner}.
 *
 * <pre>
 * private static final ToolLog LOG = new ToolLog(TOOL);
 * LOG.err("read %d entries.", count);
 * </pre>
 *
 * Messages are formatted ({@link String#format}) only when arguments are given,
 * so a ready-made message may contain a literal '%'.
 */
public final class ToolLog {

	private final String tool;

	public ToolLog(String tool) {
		this.tool = Objects.requireNonNull(tool, "tool");
	}

	/** "tool: message" to stderr (see {@link #prefix}); formats only when args are given. */
	public void err(String msg, Object... args) {
		raw(prefix(tool, msg), args);
	}

	/** The message as is to stderr, without prefix; formats only when args are given. */
	public void raw(String msg, Object... args) {
		System.err.println(args.length == 0 ? msg : String.format(msg, args));
	}

	/**
	 * "tool: message", unless the message is blank or already starts with the tool
	 * name (then unchanged).
	 */
	public static String prefix(String tool, String message) {
		return message.isBlank() || message.startsWith(tool) ? message : tool + ": " + message;
	}
}
