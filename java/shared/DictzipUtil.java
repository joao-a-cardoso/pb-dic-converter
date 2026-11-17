package shared;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Writes data in dictzip format (.dict.dz).
 *
 * Dictzip is gzip-compatible but splits the input into fixed-size
 * uncompressed chunks and records each chunk's compressed size in a
 * custom gzip extra field ("RA"). This allows sdcv / KOReader to seek
 * directly to any entry without decompressing the whole file.
 *
 * As in the reference dictzip, the whole input is one deflate stream:
 * each chunk boundary is a FULL_FLUSH (byte-aligned, compression history
 * reset), so a reader can start inflating at any chunk after seeking to
 * it, and only the last chunk ends with the final deflate block. The file
 * is therefore also a plain gzip file that gzip / zcat / GZIPInputStream
 * can read from start to end.
 *
 * The data is compressed while it is written ({@link Compressor}): only one
 * uncompressed chunk and the compressed chunks are kept in memory, never the
 * whole uncompressed input. The compressed chunks must be kept because the
 * header, written first, holds their sizes.
 *
 * Reference: RFC 1952 (gzip) + dictzip source (dict-1.x).
 */
public final class DictzipUtil {

	private DictzipUtil() {
		// static members only: not instantiable (Effective Java, Item 4)
	}

  /** Standard dictzip uncompressed chunk size (bytes). */
  private static final int CHUNK_SIZE = 58315;

  /**
   * Compress {@code input} in dictzip format and write to {@code out}.
   * {@code out} is flushed but not closed.
   */
  public static void write(byte[] input, OutputStream out) throws IOException {
    try (var compressor = new Compressor()) {
      compressor.write(input, 0, input.length);
      compressor.finishTo(out);
    }
  }

  /**
   * Compresses data in dictzip format as it is written, then writes the
   * whole .dz file with {@link #finishTo}. Holds a native {@link Deflater}:
   * use with try-with-resources.
   *
   * <pre>
   * try (var dz = new DictzipUtil.Compressor()) {
   *   for (byte[] part : parts) dz.write(part);
   *   dz.finishTo(out);
   * }
   * </pre>
   */
  public static final class Compressor implements AutoCloseable {

    private final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true); // raw deflate
    private final CRC32 crc = new CRC32();
    private final List<byte[]> chunks = new ArrayList<>();
    /** The current uncompressed chunk; compressed only when more data follows or at the end. */
    private final byte[] pending = new byte[CHUNK_SIZE];
    private int pendingLen;
    private long size;
    private final byte[] buf = new byte[CHUNK_SIZE + 1024];
    private final ByteArrayOutputStream chunkBuf = new ByteArrayOutputStream(CHUNK_SIZE);
    private boolean finished;

    /** Number of uncompressed bytes written so far. */
    public long size() {
      return size;
    }

    public void write(byte[] b) throws IOException {
      write(b, 0, b.length);
    }

    public void write(byte[] b, int off, int len) throws IOException {
      if (finished)
        throw new IllegalStateException("dictzip data already written");
      crc.update(b, off, len);
      size += len;
      while (len > 0) {
        // a full chunk is compressed only now that more data follows: the
        // last chunk must end the deflate stream instead (finishTo)
        if (pendingLen == CHUNK_SIZE) {
          compressPending(false);
        }
        int n = Math.min(len, CHUNK_SIZE - pendingLen);
        System.arraycopy(b, off, pending, pendingLen, n);
        pendingLen += n;
        off += n;
        len -= n;
      }
    }

    /**
     * Compresses the last chunk and writes the .dz file (header, chunks,
     * trailer) to {@code out}; {@code out} is flushed but not closed.
     * Only once.
     */
    public void finishTo(OutputStream out) throws IOException {
      if (finished)
        throw new IllegalStateException("dictzip data already written");
      // the last chunk; an empty input still yields one (empty) chunk, so the
      // deflate stream is valid
      compressPending(true);
      finished = true;
      deflater.end();

      // ── Gzip header with RA extra field ─────────────────────────────────
      //
      //  RA subfield data layout (raLen bytes):
      //    VER   2  version = 1
      //    CHLEN 2  uncompressed chunk size
      //    CHCNT 2  number of chunks
      //    SIZE  2  compressed size of chunk[0]
      //    SIZE  2  compressed size of chunk[1]  ...
      //
      //  XLEN = SI1(1)+SI2(1)+LEN(2) + raLen = 4 + raLen
      int raLen = 6 + chunks.size() * 2;
      int xlen  = 4 + raLen;

      out.write(0x1f);          // ID1
      out.write(0x8b);          // ID2
      out.write(0x08);          // CM  = deflate
      out.write(0x04);          // FLG = FEXTRA
      writeLE32(out, 0);        // MTIME
      out.write(0x00);          // XFL
      out.write(0xff);          // OS  = unknown
      writeLE16(out, xlen);     // XLEN

      // RA subfield
      out.write('R');
      out.write('A');
      writeLE16(out, raLen);
      writeLE16(out, 1);              // version
      writeLE16(out, CHUNK_SIZE);     // uncompressed chunk length
      writeLE16(out, chunks.size());  // chunk count
      for (byte[] chunk : chunks)
        writeLE16(out, chunk.length); // compressed size of each chunk

      // ── Compressed payload ──────────────────────────────────────────────
      for (byte[] chunk : chunks)
        out.write(chunk);

      // ── Gzip trailer ────────────────────────────────────────────────────
      writeLE32(out, (int) crc.getValue()); // CRC32
      writeLE32(out, (int) size);           // ISIZE (mod 2^32)

      out.flush();
    }

    /** Frees the native deflater (also when {@link #finishTo} was not reached). */
    @Override
    public void close() {
      deflater.end();
    }

    /** Compresses the pending chunk: FULL_FLUSH, or the final block when {@code last}. */
    private void compressPending(boolean last) throws IOException {
      deflater.setInput(pending, 0, pendingLen);
      chunkBuf.reset();
      if (last) {
        // final block: ends the deflate stream
        deflater.finish();
        while (!deflater.finished()) {
          int n = deflater.deflate(buf);
          chunkBuf.write(buf, 0, n);
        }
      } else {
        // FULL_FLUSH: emit all pending output, byte-align and reset the
        // history, so this chunk boundary is a valid restart point.
        // A full buffer means there may be more output, so call again.
        int n;
        do {
          n = deflater.deflate(buf, 0, buf.length, Deflater.FULL_FLUSH);
          chunkBuf.write(buf, 0, n);
        } while (n == buf.length);
      }

      byte[] compressed = chunkBuf.toByteArray();
      if (compressed.length > 0xFFFF)
        throw new IOException("Chunk too large for dictzip 16-bit size field: "
            + compressed.length);
      chunks.add(compressed);
      pendingLen = 0;
    }
  }

  // ── Little-endian helpers ─────────────────────────────────────────────

  private static void writeLE16(OutputStream out, int v) throws IOException {
    out.write( v        & 0xff);
    out.write((v >>  8) & 0xff);
  }

  private static void writeLE32(OutputStream out, int v) throws IOException {
    out.write( v        & 0xff);
    out.write((v >>  8) & 0xff);
    out.write((v >> 16) & 0xff);
    out.write((v >> 24) & 0xff);
  }
}
