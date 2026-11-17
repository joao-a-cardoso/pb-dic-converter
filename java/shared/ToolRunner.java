package shared;

import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Runs a tool's main logic and is the only place that ends the program: it
 * turns a {@link CliException} into a message, the usage and an exit code, and
 * reports unexpected exceptions.
 *
 * <pre>
 * public static void main(String[] args) {
 * 	ToolRunner.run(TOOL, MyTool::usage, () -&gt; run(args));
 * }
 * </pre>
 */
public final class ToolRunner {

	private ToolRunner() {
	}

	/** A tool's main logic; may throw anything. */
	@FunctionalInterface
	public interface ToolBody {
		void run() throws Exception;
	}

	/** Runs {@code body} and exits the process with its exit code. */
	public static void run(String tool, Supplier<String> usage, ToolBody body) {
		System.exit(execute(tool, usage, body));
	}

	/**
	 * Runs {@code body} and returns the exit code instead of exiting (usable from
	 * tests):
	 * <ul>
	 * <li>normal end: 0;</li>
	 * <li>{@link CliException}: its message to stderr (prefixed with the tool
	 * name, unless it already starts with it), then the usage if requested —
	 * stdout for exit code 0 (help), stderr otherwise; returns its exit code;</li>
	 * <li>a file system error (a read-only or missing file or folder…): a user
	 * error, not a bug — "tool: Error: &lt;file&gt;: &lt;reason&gt;" to stderr, no
	 * stack trace; returns 1;</li>
	 * <li>any other exception: unexpected — "tool: Error: …" and the stack trace
	 * to stderr; returns 1.</li>
	 * </ul>
	 */
	public static int execute(String tool, Supplier<String> usage, ToolBody body) {
		Objects.requireNonNull(tool, "tool");
		Objects.requireNonNull(body, "body");
		try {
			body.run();
			return 0;
		} catch (CliException e) {
			if (e.getMessage() != null) {
				System.err.println(ToolLog.prefix(tool, e.getMessage()));
			}
			if (e.showUsage()) {
				ArgsHelper.displayUsage(usage, e.exitCode() == 0 ? System.out : System.err);
			}
			return e.exitCode();
		} catch (FileSystemException e) {
			System.err.println(ToolLog.prefix(tool, "Error: " + describe(e)));
			return 1;
		} catch (Exception e) {
			System.err.println(ToolLog.prefix(tool, "Error: " + e));
			e.printStackTrace();
			return 1;
		}
	}

	/** "&lt;file&gt;: &lt;reason&gt;", e.g. "out.dic: permission denied". */
	static String describe(FileSystemException e) {
		String reason = e.getReason();
		if (reason == null) {
			reason = switch (e) {
			case AccessDeniedException _ -> "permission denied";
			case NoSuchFileException _ -> "not found";
			default -> e.getClass().getSimpleName();
			};
		}
		String file = e.getFile() == null ? "" : ArgsHelper.displayPath(e.getFile()) + ": ";
		String other = e.getOtherFile() == null ? "" : " (" + ArgsHelper.displayPath(e.getOtherFile()) + ")";
		return file + reason + other;
	}
}
