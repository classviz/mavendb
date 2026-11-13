package org.mavendb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

/**
 * Unit tests for {@link VersionAnalyser}.
 */
@DisplayName("VersionAnalyser Tests")
class VersionAnalyserTest {

    @Test
    @DisplayName("Simple semantic version (1.0.0)")
    void testSimpleSemanticVersion() {
        VersionAnalyser analyser = new VersionAnalyser("1.0.0");
        assertEquals(1, analyser.majorVersion());
    }

    @Test
    @DisplayName("Two-part version (2.5)")
    void testTwoPartVersion() {
        VersionAnalyser analyser = new VersionAnalyser("2.5");
        assertEquals(2, analyser.majorVersion());
    }

    @Test
    @DisplayName("Single digit version (3)")
    void testSingleDigitVersion() {
        VersionAnalyser analyser = new VersionAnalyser("3");
        // Single digit without dot - treated as non-numeric
        assertEquals(0, analyser.majorVersion());
    }

    @Test
    @DisplayName("Complex version (1.2.3.4)")
    void testComplexVersion() {
        VersionAnalyser analyser = new VersionAnalyser("1.2.3.4");
        assertEquals(1, analyser.majorVersion());
    }

    @Test
    @DisplayName("Version with letter prefix (v2.1.0)")
    void testVersionWithLetterPrefix() {
        VersionAnalyser analyser = new VersionAnalyser("v2.1.0");
        assertEquals(2, analyser.majorVersion());
    }

    @Test
    @DisplayName("Version with SNAPSHOT suffix (1.5.0-SNAPSHOT)")
    void testVersionWithSnapshotSuffix() {
        VersionAnalyser analyser = new VersionAnalyser("1.5.0-SNAPSHOT");
        assertEquals(1, analyser.majorVersion());
    }

    @Test
    @DisplayName("Version with release candidate (2.0.0-RC1)")
    void testVersionWithRCTag() {
        VersionAnalyser analyser = new VersionAnalyser("2.0.0-RC1");
        assertEquals(2, analyser.majorVersion());
    }

    @Test
    @DisplayName("Date-like version (2016.12.13)")
    void testDateLikeVersion() {
        VersionAnalyser analyser = new VersionAnalyser("2016.12.13");
        assertEquals(2016, analyser.majorVersion());
    }

    @Test
    @DisplayName("Date-like version with time (20050805.042414)")
    void testDateLikeVersionWithTime() {
        VersionAnalyser analyser = new VersionAnalyser("20050805.042414");
        // Large first component treated as year - substring up to YEAR_LENGTH (10)
        // 20050805 is 8 chars, so full value is used
        assertEquals(20050805, analyser.majorVersion());
    }

    @Test
    @DisplayName("Date-like version (2023.01.15)")
    void testDateVersion2023() {
        VersionAnalyser analyser = new VersionAnalyser("2023.01.15");
        assertEquals(2023, analyser.majorVersion());
    }

    @Test
    @DisplayName("Version with multiple dots (1...2...3)")
    void testVersionWithMultipleDots() {
        VersionAnalyser analyser = new VersionAnalyser("1...2...3");
        assertEquals(1, analyser.majorVersion());
    }

    @Test
    @DisplayName("Very large major version (exceeds MAJOR_VERSION_MAX)")
    void testVeryLargeMajorVersion() {
        VersionAnalyser analyser = new VersionAnalyser("99999.1.2");
        // 99999 > MAJOR_VERSION_MAX(922), so treated as date/year format
        // Takes first 10 chars of 99999: 99999, but majorVersion capped based on logic
        long major = analyser.majorVersion();
        // Actual behavior: it's treated as date format, major version extracted differently
        assertEquals(99999, major);
    }

    @Test
    @DisplayName("Null version")
    void testNullVersion() {
        VersionAnalyser analyser = new VersionAnalyser(null);
        assertEquals(0, analyser.majorVersion());
        assertEquals(0, analyser.versionSeq());
    }

    @Test
    @DisplayName("Empty string version")
    void testEmptyVersion() {
        VersionAnalyser analyser = new VersionAnalyser("");
        assertEquals(0, analyser.majorVersion());
    }

    @Test
    @DisplayName("Non-numeric version (alpha-beta-gamma)")
    void testNonNumericVersion() {
        VersionAnalyser analyser = new VersionAnalyser("alpha-beta-gamma");
        assertEquals(0, analyser.majorVersion());
    }

    @Test
    @DisplayName("Version sequence comparison - 1.0 vs 1.1")
    void testVersionSequenceOrdering() {
        VersionAnalyser v1 = new VersionAnalyser("1.0");
        VersionAnalyser v2 = new VersionAnalyser("1.1");
        // v1.1 should have a higher sequence than v1.0
        long seq1 = v1.versionSeq();
        long seq2 = v2.versionSeq();
        assertEquals(1, v1.majorVersion());
        assertEquals(1, v2.majorVersion());
        // v1.1 > v1.0 in sequence (minor version is second part)
        assertTrue(seq2 > seq1, "1.1 should have a higher version sequence than 1.0");
    }

    @Test
    @DisplayName("Version sequence for 2.0 vs 1.9")
    void testVersionSequenceComparison() {
        VersionAnalyser v1 = new VersionAnalyser("1.9");
        VersionAnalyser v2 = new VersionAnalyser("2.0");
        // v2.0 should have higher major version
        assertEquals(1, v1.majorVersion());
        assertEquals(2, v2.majorVersion());
        long seq1 = v1.versionSeq();
        long seq2 = v2.versionSeq();
        // seq2 should be higher due to major version difference
        assertTrue(seq2 > seq1, "2.0 should have a higher version sequence than 1.9");
    }

    @Test
    @DisplayName("toString representation")
    void testToString() {
        VersionAnalyser analyser = new VersionAnalyser("1.2.3");
        String str = analyser.toString();
        assertEquals("1, " + analyser.versionSeq(), str);
    }

    @Test
    @DisplayName("Version with special characters (1.2@3#4)")
    void testVersionWithSpecialCharacters() {
        VersionAnalyser analyser = new VersionAnalyser("1.2@3#4");
        assertEquals(1, analyser.majorVersion());
    }

    @Test
    @DisplayName("Leading zeros (01.002.0003)")
    void testVersionWithLeadingZeros() {
        VersionAnalyser analyser = new VersionAnalyser("01.002.0003");
        assertEquals(1, analyser.majorVersion());
    }

    @Test
    @DisplayName("Maven central example: citizen-intelligence-agency 2016.12.13")
    void testMavenCentralExample1() {
        VersionAnalyser analyser = new VersionAnalyser("2016.12.13");
        assertEquals(2016, analyser.majorVersion());
    }

    @Test
    @DisplayName("Maven central example: felix 5.2.0")
    void testMavenCentralExample2() {
        VersionAnalyser analyser = new VersionAnalyser("5.2.0");
        assertEquals(5, analyser.majorVersion());
    }

    @Test
    @DisplayName("Consistency - same input produces same output")
    void testConsistency() {
        String versionStr = "1.2.3-SNAPSHOT";
        VersionAnalyser a1 = new VersionAnalyser(versionStr);
        VersionAnalyser a2 = new VersionAnalyser(versionStr);
        assertEquals(a1.majorVersion(), a2.majorVersion());
        assertEquals(a1.versionSeq(), a2.versionSeq());
    }

    @Test
    @DisplayName("Version with only major component")
    void testMajorOnlyVersion() {
        VersionAnalyser analyser = new VersionAnalyser("42");
        // Single number without dots - treated as non-numeric, returns 0
        assertEquals(0, analyser.majorVersion());
    }
}
