package shared;

import java.io.*;
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
 * Reference: RFC 1952 (gzip) + dictzip source (dict-1.x).
 */
public class DictzipUtil {

  /** Standard dictzip uncompressed chunk size (bytes). */
  private static final int CHUNK_SIZE = 58315;

  /** Convenience overload for ByteArrayOutputStream. */
  public static void write(ByteArrayOutputStream src, OutputStream dst)
      throws IOException {
    write(src.toByteArray(), dst);
  }

  /**
   * Compress {@code input} in dictzip format and write to {@code out}.
   * {@code out} is flushed but not closed.
   */
  public static void write(byte[] input, OutputStream out) throws IOException {

    // ── 1. Compress: one deflate stream, FULL_FLUSH at each chunk end ────
    List<byte[]> chunks = new ArrayList<>();

    // at least one chunk, so an empty input still yields a valid stream
    int total = Math.max(1, (input.length + CHUNK_SIZE - 1) / CHUNK_SIZE);

    Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true); // raw deflate
    byte[] buf = new byte[CHUNK_SIZE + 1024];
    try {
      for (int i = 0; i < total; i++) {
        int off = i * CHUNK_SIZE;
        int len = Math.min(CHUNK_SIZE, input.length - off);
        boolean last = i == total - 1;

        deflater.setInput(input, off, len);
        ByteArrayOutputStream chunkBuf = new ByteArrayOutputStream();
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
      }
    } finally {
      deflater.end();
    }

    // ── 2. CRC32 of original input ────────────────────────────────────────
    CRC32 crc = new CRC32();
    crc.update(input);

    // ── 3. Gzip header with RA extra field ───────────────────────────────
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

    // ── 4. Compressed payload ────────────────────────────────────────────
    for (byte[] chunk : chunks)
      out.write(chunk);

    // ── 5. Gzip trailer ──────────────────────────────────────────────────
    writeLE32(out, (int) crc.getValue()); // CRC32
    writeLE32(out, input.length);         // ISIZE (mod 2^32)

    out.flush();
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