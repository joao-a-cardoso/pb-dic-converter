package shared;

/**
 * Ends a tool with an exit code, optionally showing a message and the usage.
 * Thrown by the shared code and the tools instead of calling
 * {@code System.exit}; {@link ToolRunner} turns it into output and an exit code.
 *
 * <p>
 * Unchecked: these are errors of the caller (wrong options, config, files) or a
 * help request, which the program does not recover from.
 */
public class CliException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final int exitCode;
	private final boolean showUsage;

	/**
	 * @param message   text printed to stderr (the runner adds the tool name), or
	 *                  {@code null} for none
	 * @param exitCode  process exit code: 0 for help, 1 for errors
	 * @param showUsage print the usage after the message (stdout when the exit
	 *                  code is 0, else stderr)
	 */
	public CliException(String message, int exitCode, boolean showUsage) {
		super(message);
		this.exitCode = exitCode;
		this.showUsage = showUsage;
	}

	/** An error (exit code 1) without the usage. Formats only when args are given. */
	public static CliException error(String message, Object... args) {
		return new CliException(format(message, args), 1, false);
	}

	/** An error (exit code 1) followed by the usage. Formats only when args are given. */
	public static CliException usageError(String message, Object... args) {
		return new CliException(format(message, args), 1, true);
	}

	/** Help requested: no message, usage to stdout, exit code 0. */
	public static CliException help() {
		return new CliException(null, 0, true);
	}

	public int exitCode() {
		return exitCode;
	}

	public boolean showUsage() {
		return showUsage;
	}

	private static String format(String message, Object... args) {
		return args.length == 0 ? message : String.format(message, args);
	}
}
