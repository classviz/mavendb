package org.mavendb;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UTFDataFormatException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.zip.GZIPInputStream;

/**
 * Drop-in replacement for {@link org.apache.maven.index.reader.ChunkReader} that reads the same
 * Maven index chunk format, but much faster.
 *
 * <p>The upstream {@code ChunkReader} wraps the input as
 * {@code new DataInputStream(new GZIPInputStream(in, 2048))} with no buffer between the two,
 * so every {@code readInt()} / {@code readByte()} / {@code readUnsignedShort()} turns into
 * one-byte {@code GZIPInputStream.read()} calls, each of which goes down into the native
 * {@code Inflater}. With ~90 million records of ~15 fields each, that is billions of native
 * calls. Here the decompressed stream is buffered, so those small reads are served from a
 * Java byte array.
 *
 * <p>The record format and the modified-UTF-8 decoding match {@code ChunkReader} in
 * {@code indexer-reader} 7.1.x exactly.
 */
final class BufferedChunkReader implements Closeable, Iterable<Map<String, String>> {

    /** Buffer size for both the compressed input and the decompressed output. */
    private static final int BUFFER_SIZE = 256 * 1024;

    private final String name;
    private final DataInputStream in;
    private final int version;
    private final Date timestamp;

    /**
     * Open a chunk.
     *
     * @param name Chunk name, for logging
     * @param inputStream Raw (gzip-compressed) chunk stream; closed by {@link #close()}
     * @throws IOException if the chunk header cannot be read
     */
    BufferedChunkReader(final String name, final InputStream inputStream) throws IOException {
        this.name = name.trim();
        this.in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(inputStream, BUFFER_SIZE), BUFFER_SIZE));
        this.version = this.in.readByte() & 0xff;
        this.timestamp = new Date(this.in.readLong());
    }

    String getName() {
        return this.name;
    }

    int getVersion() {
        return this.version;
    }

    Date getTimestamp() {
        return this.timestamp;
    }

    @Override
    public Iterator<Map<String, String>> iterator() {
        return new Iterator<>() {
            private Map<String, String> next = readRecordUnchecked();

            @Override
            public boolean hasNext() {
                return this.next != null;
            }

            @Override
            public Map<String, String> next() {
                if (this.next == null) {
                    throw new NoSuchElementException("chunk depleted");
                }
                Map<String, String> result = this.next;
                this.next = readRecordUnchecked();
                return result;
            }
        };
    }

    @Override
    public void close() throws IOException {
        this.in.close();
    }

    private Map<String, String> readRecordUnchecked() {
        try {
            return readRecord();
        } catch (IOException e) {
            throw new UncheckedIOException("read error in chunk " + this.name, e);
        }
    }

    private Map<String, String> readRecord() throws IOException {
        int fieldCount;
        try {
            fieldCount = this.in.readInt();
        } catch (EOFException ex) {
            return null; // no more documents
        }

        Map<String, String> recordMap = HashMap.newHashMap(fieldCount);
        for (int i = 0; i < fieldCount; i++) {
            this.in.readByte(); // flags: neglect them
            String key = this.in.readUTF();
            String value = readLongUTF();
            recordMap.put(key, value);
        }
        return recordMap;
    }

    /**
     * Read a modified-UTF-8 string with an {@code int} length prefix
     * (same as {@code ChunkReader.readUTF}, which allows values longer than 64KB).
     */
    private String readLongUTF() throws IOException {
        int utflen = this.in.readInt();
        byte[] bytes;
        try {
            bytes = new byte[utflen];
        } catch (OutOfMemoryError e) {
            throw new IOException("Index data content is corrupt", e);
        }
        this.in.readFully(bytes, 0, utflen);
        return decodeModifiedUtf8(bytes);
    }

    /**
     * Decode modified UTF-8, with the same rules and errors as {@code ChunkReader.readUTF}.
     */
    static String decodeModifiedUtf8(final byte[] bytes) throws UTFDataFormatException {
        final int utflen = bytes.length;

        // Fast path: pure ASCII, which is the vast majority of index values
        int count = 0;
        while (count < utflen && bytes[count] >= 0) {
            count++;
        }
        if (count == utflen) {
            return new String(bytes, 0, utflen, StandardCharsets.ISO_8859_1); // ASCII == Latin-1, compact string, no char[]
        }

        char[] chars = new char[utflen];
        for (int i = 0; i < count; i++) {
            chars[i] = (char) bytes[i];
        }
        int charCount = count;

        while (count < utflen) {
            int c = bytes[count] & 0xff;
            switch (c >> 4) {
                case 0, 1, 2, 3, 4, 5, 6, 7 -> {
                    /* 0xxxxxxx */
                    count++;
                    chars[charCount++] = (char) c;
                }
                case 12, 13 -> {
                    /* 110x xxxx 10xx xxxx */
                    count += 2;
                    if (count > utflen) {
                        throw new UTFDataFormatException("malformed input: partial character at end");
                    }
                    int char2 = bytes[count - 1];
                    if ((char2 & 0xC0) != 0x80) {
                        throw new UTFDataFormatException("malformed input around byte " + count);
                    }
                    chars[charCount++] = (char) (((c & 0x1F) << 6) | (char2 & 0x3F));
                }
                case 14 -> {
                    /* 1110 xxxx 10xx xxxx 10xx xxxx */
                    count += 3;
                    if (count > utflen) {
                        throw new UTFDataFormatException("malformed input: partial character at end");
                    }
                    int char2 = bytes[count - 2];
                    int char3 = bytes[count - 1];
                    if (((char2 & 0xC0) != 0x80) || ((char3 & 0xC0) != 0x80)) {
                        throw new UTFDataFormatException("malformed input around byte " + (count - 1));
                    }
                    chars[charCount++] = (char) (((c & 0x0F) << 12) | ((char2 & 0x3F) << 6) | (char3 & 0x3F));
                }
                default ->
                    /* 10xx xxxx, 1111 xxxx */
                    throw new UTFDataFormatException("malformed input around byte " + count);
            }
        }
        return new String(chars, 0, charCount);
    }
}
