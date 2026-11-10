package org.mavendb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.index.reader.ChunkReader;
import org.apache.maven.index.reader.ChunkWriter;
import org.junit.jupiter.api.Test;

/**
 * {@link BufferedChunkReader} must read exactly what the upstream {@link ChunkReader} reads.
 */
class BufferedChunkReaderTest {

    private static byte[] writeChunk(List<Map<String, String>> records, Date timestamp) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ChunkWriter writer = new ChunkWriter("test", out, 1, timestamp)) {
            writer.writeChunk(records.iterator());
        }
        return out.toByteArray();
    }

    private static List<Map<String, String>> sampleRecords() {
        List<Map<String, String>> records = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            Map<String, String> rec = new LinkedHashMap<>();
            rec.put("u", "org.example|artifact-" + i + "|1.0." + i + "|NA|jar");
            rec.put("i", "jar|1657893876000|229462|0|0|0|jar");
            rec.put("m", String.valueOf(1765382166548L + i));
            rec.put("n", "Name " + i);
            records.add(rec);
        }
        // Non-ASCII: 2-byte and 3-byte UTF-8, and the modified-UTF-8 encoding of NUL
        records.add(Map.of("u", "org.example|unicode|1.0|NA|jar", "d", "Größe, 日本語, café, \u0000 end"));
        // A value over 64KB, which needs the int length prefix
        records.add(Map.of("u", "org.example|big|1.0|NA|jar", "Export-Package", "x;version=\"1\",".repeat(10_000)));
        // Empty value and a record with no fields
        records.add(Map.of("u", "org.example|empty|1.0|NA|jar", "d", ""));
        records.add(Map.of());
        return records;
    }

    @Test
    void readsSameRecordsAsUpstreamChunkReader() throws IOException {
        Date timestamp = new Date(1700000000000L);
        List<Map<String, String>> written = sampleRecords();
        byte[] chunk = writeChunk(written, timestamp);

        List<Map<String, String>> expected = new ArrayList<>();
        try (ChunkReader reader = new ChunkReader("test", new ByteArrayInputStream(chunk))) {
            reader.forEach(expected::add);
            assertEquals(1, reader.getVersion());
        }

        List<Map<String, String>> actual = new ArrayList<>();
        try (BufferedChunkReader reader = new BufferedChunkReader(" test ", new ByteArrayInputStream(chunk))) {
            reader.forEach(actual::add);
            assertEquals("test", reader.getName());
            assertEquals(1, reader.getVersion());
            assertEquals(timestamp, reader.getTimestamp());
        }

        assertEquals(written.size(), actual.size());
        assertEquals(expected, actual);
        assertEquals(written, actual);
    }
}
