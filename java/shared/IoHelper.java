package shared;

import static shared.TextHelper.lower;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Opens the tools' input and output, so that they can always be declared in a
 * try-with-resources header:
 *
 * <pre>
 * try (InputStream in = IoHelper.openInput(input); OutputStream out = IoHelper.openOutput(output)) { … }
 * try (InputStream in = IoHelper.openInput(input); var out = IoHelper.openSafeOutput(output)) { … }
 * </pre>
 *
 * "-" stands for stdin / stdout. Closing what these methods return never closes
 * {@code System.in} or {@code System.out}: whoever opens a resource closes it,
 * and the standard streams belong to the process.
 *
 * <p>
 * {@link #openOutput} writes as it goes; {@link #openSafeOutput} writes all or
 * nothing (see {@link SafeOutput}).
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

	/**
	 * All-or-nothing output for {@code path}: "-" is stdout, anything else a file
	 * (see {@link #openSafeOutput(Path)}).
	 */
	public static SafeOutput openSafeOutput(String path) throws IOException {
		Objects.requireNonNull(path, "path");
		return "-".equals(path) ? new SafeOutput(null) : openSafeOutput(Path.of(path));
	}

	/**
	 * All-or-nothing output for the file {@code file}; nothing is created or
	 * changed until {@link SafeOutput#commit()}. Fails now if the file could not
	 * be written then: its folder does not exist or is not writable, or it is a
	 * folder or read-only.
	 */
	public static SafeOutput openSafeOutput(Path file) throws IOException {
		Objects.requireNonNull(file, "file");
		Path folder = file.toAbsolutePath().getParent();
		if (folder == null || !Files.isDirectory(folder)) {
			throw new NoSuchFileException(file.toString(), null, "folder not found");
		}
		if (Files.isDirectory(file)) {
			throw new FileSystemException(file.toString(), null, "is a folder");
		}
		if (Files.exists(file) ? !Files.isWritable(file) : !Files.isWritable(folder)) {
			throw new AccessDeniedException(file.toString());
		}
		return new SafeOutput(file);
	}

	/**
	 * An output that is written all or nothing: the data is kept in memory,
	 * compressed, and reaches the file or stdout only on {@link #commit()}. If the
	 * tool fails before that, the target is never touched: an existing file keeps
	 * its old contents, stdout gets nothing (so the next tool in a pipe sees an
	 * empty input instead of a silently cut one). Made by
	 * {@link IoHelper#openSafeOutput}.
	 *
	 * <pre>
	 * try (var out = IoHelper.openSafeOutput(output)) { // "-" = stdout
	 * 	… write to out.stream() …
	 * 	out.commit();
	 * } // without commit(): nothing written
	 * </pre>
	 *
	 * Memory: the compressed data, about 1/8 of the output for dictionary text.
	 * A file target is checked when opened (folder exists, writable), so a wrong
	 * path fails before the work, not after it. Only an I/O error during
	 * {@code commit()} itself (e.g. disk full) can leave a partial file.
	 */
	public static final class SafeOutput implements AutoCloseable {

		/** The target file, or {@code null} for stdout. */
		private final Path file;
		private final Deflater deflater = new Deflater(Deflater.BEST_SPEED);
		private final Buffer buffer = new Buffer();
		private final DeflaterOutputStream deflating = new DeflaterOutputStream(buffer, deflater, 64 * 1024);
		/** Buffered, so that small writes (single bytes) don't each reach the deflater. */
		private final BufferedOutputStream buffered = new BufferedOutputStream(deflating, 64 * 1024);
		private final OutputStream stream = new FilterOutputStream(buffered) {
			@Override
			public void write(byte[] b, int off, int len) throws IOException {
				out.write(b, off, len); // FilterOutputStream would write byte by byte
			}

			@Override
			public void close() throws IOException {
				flush(); // the SafeOutput ends it: commit() or close()
			}
		};
		private boolean finished;
		private boolean committed;

		/**
		 * The compressed data, in blocks of fixed size: growing never copies (unlike
		 * a ByteArrayOutputStream, which doubles its array and for a moment holds
		 * both), and reading it back copies nothing either.
		 */
		private static final class Buffer extends OutputStream {
			private static final int BLOCK_SIZE = 256 * 1024;
			private final List<byte[]> blocks = new ArrayList<>();
			/** Bytes used in the last block. */
			private int used = BLOCK_SIZE;

			@Override
			public void write(int b) {
				write(new byte[] { (byte) b }, 0, 1);
			}

			@Override
			public void write(byte[] b, int off, int len) {
				while (len > 0) {
					if (used == BLOCK_SIZE) {
						blocks.add(new byte[BLOCK_SIZE]);
						used = 0;
					}
					int n = Math.min(len, BLOCK_SIZE - used);
					System.arraycopy(b, off, blocks.getLast(), used, n);
					used += n;
					off += n;
					len -= n;
				}
			}

			InputStream inputStream() {
				var parts = new ArrayList<InputStream>(blocks.size());
				for (int i = 0; i < blocks.size(); i++) {
					int length = i == blocks.size() - 1 ? used : BLOCK_SIZE;
					parts.add(new ByteArrayInputStream(blocks.get(i), 0, length));
				}
				return new SequenceInputStream(Collections.enumeration(parts));
			}
		}

		/** Only through {@link IoHelper#openSafeOutput}, which checks the target. */
		private SafeOutput(Path file) {
			this.file = file;
		}

		/**
		 * Where to write the data. Closing it only flushes; it cannot be used after
		 * {@link #contents()} or {@link #commit()}.
		 */
		public OutputStream stream() {
			if (finished) {
				throw new IllegalStateException("output already finished");
			}
			return stream;
		}

		/**
		 * The data written so far, decompressed — e.g. to validate it before
		 * {@link #commit()}. Ends the writing. The caller closes it.
		 */
		public InputStream contents() throws IOException {
			finish();
			return new InflaterInputStream(buffer.inputStream());
		}

		/**
		 * Writes the data to the target: the file is created or overwritten, stdout
		 * is flushed (never closed). Ends the writing; only once.
		 */
		public void commit() throws IOException {
			if (committed) {
				throw new IllegalStateException("output already committed");
			}
			committed = true;
			try (InputStream in = contents();
					OutputStream out = file == null ? openOutput("-") : Files.newOutputStream(file)) {
				in.transferTo(out);
			}
		}

		/** Frees the compressor; without {@link #commit()} nothing was written. */
		@Override
		public void close() {
			deflater.end();
		}

		private void finish() throws IOException {
			if (!finished) {
				finished = true;
				buffered.flush();
				deflating.finish();
			}
		}
	}
}
