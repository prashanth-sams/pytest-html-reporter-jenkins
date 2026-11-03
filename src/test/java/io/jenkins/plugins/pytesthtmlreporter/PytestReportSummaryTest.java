package io.jenkins.plugins.pytesthtmlreporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PytestReportSummaryTest {

    /** An {@code output.json} as written by pytest-html-reporter 0.4.5, with coverage added. */
    static String sampleJson() throws IOException {
        try (InputStream in = PytestReportSummaryTest.class.getResourceAsStream("output.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void readsOutputJsonWrittenByPytestHtmlReporter() throws IOException {
        PytestReportSummary s = PytestReportSummary.parse(sampleJson());
        assertEquals(8, s.getPassed());
        assertEquals(1, s.getFailed());
        assertEquals(1, s.getSkipped());
        assertEquals(0, s.getErrors());
        assertEquals(0, s.getXpassed());
        assertEquals(1, s.getXfailed());
        assertEquals(2, s.getReruns());
        assertEquals(11, s.getTotal());
        assertEquals(1, s.getSuites());
        assertEquals("FAIL", s.getStatus());
        assertEquals("Nov 03, 2026", s.getDate());
        assertEquals(87.5, s.getCoveragePercent());
        assertEquals("87.5", s.getCoverageLabel());
        assertEquals(1, s.getFailedOrErrored());
        // 8 passed + 1 xfailed out of 10 that ran
        assertEquals(90, s.getPassRate());
    }

    @Test
    void readsUnmodifiedOutputJsonOfPytestHtmlReporter045() throws IOException {
        String json;
        try (InputStream in = PytestReportSummaryTest.class.getResourceAsStream("output-0.4.5.json")) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        PytestReportSummary s = PytestReportSummary.parse(json);
        assertEquals(2, s.getPassed());
        assertEquals(1, s.getFailed());
        assertEquals(1, s.getSkipped());
        assertEquals(1, s.getXfailed());
        assertEquals(5, s.getTotal());
        assertEquals(1, s.getSuites());
        assertEquals("FAIL", s.getStatus());
        assertNull(s.getCoveragePercent());
        assertEquals(75, s.getPassRate());
    }

    @Test
    void numbersMayBeNumbersOrStrings() {
        PytestReportSummary s = PytestReportSummary.parse(
                "{\"status_list\": {\"pass\": 3, \"fail\": \"2\", \"error\": 1}, \"coverage\": {\"percent\": \"55.555\"}}");
        assertEquals(3, s.getPassed());
        assertEquals(2, s.getFailed());
        assertEquals(1, s.getErrors());
        assertEquals(6, s.getTotal(), "total is summed when total_tests is missing");
        assertEquals("55.6", s.getCoverageLabel());
    }

    @Test
    void malformedValuesReadAsZero() {
        PytestReportSummary s = PytestReportSummary.parse(
                "{\"status_list\": {\"pass\": \"many\", \"fail\": -4, \"skip\": [1]}, \"status\": 7, \"coverage\": {\"percent\": \"n/a\"}}");
        assertEquals(0, s.getPassed());
        assertEquals(0, s.getFailed());
        assertEquals(0, s.getSkipped());
        assertEquals(0, s.getTotal());
        assertEquals(0, s.getPassRate());
        assertNull(s.getStatus());
        assertNull(s.getCoveragePercent());
        assertNull(s.getCoverageLabel());
    }

    @Test
    void longTextIsCut() {
        PytestReportSummary s = PytestReportSummary.parse("{\"date\": \"" + "x".repeat(500) + "\"}");
        assertEquals(64, s.getDate().length());
    }

    @Test
    void emptyObjectIsAnEmptySummary() {
        PytestReportSummary s = PytestReportSummary.parse("{}");
        assertEquals(0, s.getTotal());
        assertNull(s.getDate());
    }

    @Test
    void notAJsonObjectIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PytestReportSummary.parse("<html>"));
        assertThrows(IllegalArgumentException.class, () -> PytestReportSummary.parse("[1, 2]"));
        assertThrows(IllegalArgumentException.class, () -> PytestReportSummary.parse("null"));
    }
}
