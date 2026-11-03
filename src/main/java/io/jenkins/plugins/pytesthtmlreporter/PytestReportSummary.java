package io.jenkins.plugins.pytesthtmlreporter;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;

/**
 * The totals of one pytest run, read from the {@code output.json} file that pytest-html-reporter
 * writes next to its HTML report.
 *
 * <p>The file is produced on the agent by the build, so it is treated as untrusted: only numbers
 * and a few short strings are kept, and anything missing or malformed reads as zero.
 */
public final class PytestReportSummary implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final int MAX_TEXT_LENGTH = 64;

    private final int passed;
    private final int failed;
    private final int skipped;
    private final int errors;
    private final int xpassed;
    private final int xfailed;
    private final int reruns;
    private final int total;
    private final int suites;
    private final String status;
    private final String date;
    private final Double coveragePercent;

    PytestReportSummary(
            int passed,
            int failed,
            int skipped,
            int errors,
            int xpassed,
            int xfailed,
            int reruns,
            int total,
            int suites,
            String status,
            String date,
            Double coveragePercent) {
        this.passed = passed;
        this.failed = failed;
        this.skipped = skipped;
        this.errors = errors;
        this.xpassed = xpassed;
        this.xfailed = xfailed;
        this.reruns = reruns;
        this.total = total;
        this.suites = suites;
        this.status = status;
        this.date = date;
        this.coveragePercent = coveragePercent;
    }

    /**
     * Parses the contents of an {@code output.json} file.
     *
     * @throws IllegalArgumentException if the text is not a JSON object
     */
    @NonNull
    public static PytestReportSummary parse(@NonNull String json) {
        try {
            JSONObject root = JSONObject.fromObject(json);
            if (root.isNullObject()) {
                throw new IllegalArgumentException("output.json does not hold a JSON object");
            }
            JSONObject statusList = root.optJSONObject("status_list");
            if (statusList == null) {
                statusList = new JSONObject();
            }
            int passed = number(statusList, "pass");
            int failed = number(statusList, "fail");
            int skipped = number(statusList, "skip");
            int errors = number(statusList, "error");
            int xpassed = number(statusList, "xpass");
            int xfailed = number(statusList, "xfail");
            int reruns = number(statusList, "rerun");
            int total = number(root, "total_tests");
            if (total == 0) {
                total = passed + failed + skipped + errors + xpassed + xfailed;
            }
            Double coverage = null;
            JSONObject coverageObject = root.optJSONObject("coverage");
            if (coverageObject != null) {
                coverage = decimal(coverageObject, "percent");
            }
            return new PytestReportSummary(
                    passed,
                    failed,
                    skipped,
                    errors,
                    xpassed,
                    xfailed,
                    reruns,
                    total,
                    number(root, "total_suite"),
                    text(root, "status"),
                    text(root, "date"),
                    coverage);
        } catch (JSONException e) {
            throw new IllegalArgumentException("output.json is not valid JSON: " + e.getMessage(), e);
        }
    }

    private static int number(JSONObject object, String key) {
        Object value = object.opt(key);
        if (value instanceof Number n) {
            return Math.max(0, n.intValue());
        }
        if (value instanceof String s) {
            try {
                return Math.max(0, Integer.parseInt(s.trim()));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    @CheckForNull
    private static Double decimal(JSONObject object, String key) {
        Object value = object.opt(key);
        double d;
        if (value instanceof Number n) {
            d = n.doubleValue();
        } else if (value instanceof String s) {
            try {
                d = Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            return null;
        }
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return null;
        }
        return Math.max(0, Math.min(100, d));
    }

    @CheckForNull
    private static String text(JSONObject object, String key) {
        Object value = object.opt(key);
        if (!(value instanceof String s) || s.isBlank()) {
            return null;
        }
        s = s.trim();
        return s.length() > MAX_TEXT_LENGTH ? s.substring(0, MAX_TEXT_LENGTH) : s;
    }

    public int getPassed() {
        return passed;
    }

    public int getFailed() {
        return failed;
    }

    public int getSkipped() {
        return skipped;
    }

    public int getErrors() {
        return errors;
    }

    public int getXpassed() {
        return xpassed;
    }

    public int getXfailed() {
        return xfailed;
    }

    public int getReruns() {
        return reruns;
    }

    public int getTotal() {
        return total;
    }

    public int getSuites() {
        return suites;
    }

    /** {@code PASS} or {@code FAIL} as pytest-html-reporter recorded it, or {@code null}. */
    @CheckForNull
    public String getStatus() {
        return status;
    }

    /** The date pytest-html-reporter stamped the run with, or {@code null}. */
    @CheckForNull
    public String getDate() {
        return date;
    }

    /** Line coverage in percent when pytest-cov ran, otherwise {@code null}. */
    @CheckForNull
    public Double getCoveragePercent() {
        return coveragePercent;
    }

    /** Coverage with one decimal, e.g. {@code 87.5}, or {@code null} when coverage was not measured. */
    @CheckForNull
    public String getCoverageLabel() {
        return coveragePercent == null ? null : String.format(Locale.ROOT, "%.1f", coveragePercent);
    }

    /** Tests that failed or errored: the ones that make a build unstable. */
    public int getFailedOrErrored() {
        return failed + errors;
    }

    /** Pass rate in whole percent of the tests that ran (skipped tests excluded). */
    public int getPassRate() {
        int ran = passed + failed + errors + xpassed + xfailed;
        if (ran == 0) {
            return 0;
        }
        return (int) Math.floor(100.0 * (passed + xfailed) / ran);
    }
}
