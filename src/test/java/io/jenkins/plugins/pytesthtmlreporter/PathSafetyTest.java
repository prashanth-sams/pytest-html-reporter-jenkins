package io.jenkins.plugins.pytesthtmlreporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The report history is copied from the controller to an agent, so nothing outside the report may come along. */
class PathSafetyTest {

    @Test
    void reportFileMustStayInsideTheReportDirectory() {
        assertTrue(PytestHtmlReportPublisher.isSafeRelativePath("pytest_html_report.html"));
        assertTrue(PytestHtmlReportPublisher.isSafeRelativePath("sub/index.html"));
        assertTrue(PytestHtmlReportPublisher.isSafeRelativePath("report..html"));
        assertFalse(PytestHtmlReportPublisher.isSafeRelativePath("../secrets/master.key"));
        assertFalse(PytestHtmlReportPublisher.isSafeRelativePath("sub/../../x.html"));
        assertFalse(PytestHtmlReportPublisher.isSafeRelativePath("..\\x.html"));
        assertFalse(PytestHtmlReportPublisher.isSafeRelativePath("/etc/passwd"));
        assertFalse(PytestHtmlReportPublisher.isSafeRelativePath("C:\\Windows\\win.ini"));
        assertFalse(PytestHtmlReportPublisher.isSafeRelativePath(""));
    }

    @Test
    void restoresOutputJsonReportAndArchive(@TempDir Path tmp) throws IOException {
        Path base = report(tmp);
        Files.writeString(base.resolve("archive/output_2.json"), "{}");
        Files.writeString(base.resolve("archive/notes.txt"), "not history");

        assertEquals(
                List.of("output.json", "pytest_html_report.html", "archive/output_1.json", "archive/output_2.json"),
                PytestHtmlReportHistoryRestorer.historyFiles(base, "pytest_html_report.html"));
    }

    @Test
    void symbolicLinksAreNotRestored(@TempDir Path tmp) throws IOException {
        Path base = report(tmp);
        Path secret = Files.writeString(tmp.resolve("secret.key"), "secret");
        Files.delete(base.resolve("pytest_html_report.html"));
        symlink(base.resolve("pytest_html_report.html"), secret);
        symlink(base.resolve("archive/output_2.json"), secret);

        assertEquals(
                List.of("output.json", "archive/output_1.json"),
                PytestHtmlReportHistoryRestorer.historyFiles(base, "pytest_html_report.html"));
    }

    @Test
    void symbolicallyLinkedArchiveIsNotRestored(@TempDir Path tmp) throws IOException {
        Path base = report(tmp);
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        Files.writeString(elsewhere.resolve("credentials.json"), "{}");
        Path archive = base.resolve("archive");
        Files.delete(archive.resolve("output_1.json"));
        Files.delete(archive);
        symlink(archive, elsewhere);

        assertEquals(
                List.of("output.json", "pytest_html_report.html"),
                PytestHtmlReportHistoryRestorer.historyFiles(base, "pytest_html_report.html"));
    }

    @Test
    void indexFileOutsideTheReportIsNotRestored(@TempDir Path tmp) throws IOException {
        Path base = report(tmp);
        Files.writeString(tmp.resolve("build/secret.html"), "secret");

        assertEquals(
                List.of("output.json", "archive/output_1.json"),
                PytestHtmlReportHistoryRestorer.historyFiles(base, "../secret.html"));
    }

    @Test
    void missingReportRestoresNothing(@TempDir Path tmp) throws IOException {
        assertEquals(List.of(), PytestHtmlReportHistoryRestorer.historyFiles(tmp.resolve("missing"), null));
    }

    /** A build's copy of a report the way the publisher stores it. */
    private static Path report(Path tmp) throws IOException {
        Path base = Files.createDirectories(tmp.resolve("build/pytest-html-reporter"));
        Files.writeString(base.resolve("output.json"), "{}");
        Files.writeString(base.resolve("pytest_html_report.html"), "<html></html>");
        Files.createDirectories(base.resolve("archive"));
        Files.writeString(base.resolve("archive/output_1.json"), "{}");
        return base;
    }

    private static void symlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("symbolic links cannot be created here: " + e);
        }
    }
}
