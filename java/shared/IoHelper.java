package shared;

import static shared.TextHelper.lower;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/**
 * Opens the tools' input and output, so that they can always be declared in a
 * try-with-resources header:
 *
 * <pre>
 * try (InputStream in = IoHelper.openInput(input); OutputStream out = IoHelper.openOutput(output)) { … }
 * </pre>
 *
 * "-" stands for stdin / stdout. Closing what these methods return never closes
 * {@code System.in} or {@code System.out}: whoever opens a resource closes it,
 * and the standard streams belong to the process.
 */
public final class IoHelper {

	private IoHelper() {
	}

	/**
	 * Input for {@code path}: "-" is stdin (closing it does nothing), a name
	 * ending in ".gz" is decompressed, anything else is read as is.
	 */
	public static InputStream openInput(String path) throws IOException {
		if ("-".equals(path)) {
			return new FilterInputStream(System.in) {
				@Override
				public void close() {
					// stdin belongs to the process
				}
			};
		}
		InputStream raw = Files.newInputStream(Path.of(path));
		if (!lower(path).endsWith(".gz")) {
			return raw;
		}
		try {
			return new GZIPInputStream(raw);
		} catch (IOException | RuntimeException e) {
			// e.g. not a gzip file: the file must not stay open
			try {
				raw.close();
			} catch (IOException closing) {
				e.addSuppressed(closing);
			}
			throw e;
		}
	}

	/**
	 * Output for {@code path}: "-" is stdout (closing it only flushes), anything
	 * else is created or truncated.
	 */
	public static OutputStream openOutput(String path) throws IOException {
		if ("-".equals(path)) {
			return new FilterOutputStream(System.out) {
				@Override
				public void write(byte[] b, int off, int len) throws IOException {
					out.write(b, off, len); // FilterOutputStream would write byte by byte
				}

				@Override
				public void close() throws IOException {
					flush(); // stdout belongs to the process
				}
			};
		}
		return Files.newOutputStream(Path.of(path));
	}
}
