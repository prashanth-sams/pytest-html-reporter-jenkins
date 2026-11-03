package io.jenkins.plugins.pytesthtmlreporter;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import java.io.IOException;
import java.io.PrintStream;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Copies the report written by pytest-html-reporter from the workspace into the build, links it
 * from the build page and records the run's totals for the project trend chart.
 *
 * <p>Pipeline: {@code publishPytestHtmlReport reportDir: 'report'}.
 */
public class PytestHtmlReportPublisher extends Recorder implements SimpleBuildStep {

    static final String LOG_PREFIX = "[pytest-html-reporter] ";
    public static final String DEFAULT_REPORT_DIR = "report";
    public static final String DEFAULT_REPORT_FILE = "pytest_html_report.html";
    static final String OUTPUT_JSON = "output.json";

    /**
     * Larger {@code output.json} files are not parsed on the controller; the report is still published.
     * pytest-html-reporter writes a few hundred bytes per test, so this covers suites of 100,000 tests.
     */
    static final long MAX_OUTPUT_JSON_BYTES = 32L * 1024 * 1024;

    private String reportDir = DEFAULT_REPORT_DIR;
    private String reportFile = DEFAULT_REPORT_FILE;
    private boolean allowMissing;
    private boolean markUnstableOnFailure = true;

    @DataBoundConstructor
    public PytestHtmlReportPublisher() {}

    @NonNull
    public String getReportDir() {
        return reportDir;
    }

    /** Workspace-relative folder given to {@code pytest --html-report}. Defaults to {@code report}. */
    @DataBoundSetter
    public void setReportDir(String reportDir) {
        String value = Util.fixEmptyAndTrim(reportDir);
        this.reportDir = value == null ? DEFAULT_REPORT_DIR : value;
    }

    @NonNull
    public String getReportFile() {
        return reportFile;
    }

    /** The report's file name inside {@link #getReportDir()}. Defaults to {@code pytest_html_report.html}. */
    @DataBoundSetter
    public void setReportFile(String reportFile) {
        String value = Util.fixEmptyAndTrim(reportFile);
        this.reportFile = value == null ? DEFAULT_REPORT_FILE : value;
    }

    public boolean isAllowMissing() {
        return allowMissing;
    }

    /** When {@code true} a missing report is logged instead of failing the build. */
    @DataBoundSetter
    public void setAllowMissing(boolean allowMissing) {
        this.allowMissing = allowMissing;
    }

    public boolean isMarkUnstableOnFailure() {
        return markUnstableOnFailure;
    }

    /** When {@code true} (the default) a run with failed or errored tests marks the build unstable. */
    @DataBoundSetter
    public void setMarkUnstableOnFailure(boolean markUnstableOnFailure) {
        this.markUnstableOnFailure = markUnstableOnFailure;
    }

    @Override
    public void perform(
            @NonNull Run<?, ?> run,
            @NonNull FilePath workspace,
            @NonNull EnvVars env,
            @NonNull Launcher launcher,
            @NonNull TaskListener listener)
            throws InterruptedException, IOException {
        PrintStream log = listener.getLogger();
        String dir = env.expand(reportDir);
        String file = env.expand(reportFile);
        if (!isSafeRelativePath(file)) {
            throw new AbortException(
                    LOG_PREFIX + "reportFile must name a file inside reportDir, without '..': " + file);
        }

        FilePath source = workspace.child(dir);
        FilePath index = source.child(file);
        if (!index.exists()) {
            String message = "No pytest HTML report found at " + index.getRemote();
            if (allowMissing) {
                log.println(LOG_PREFIX + message + "; nothing to publish.");
                return;
            }
            throw new AbortException(LOG_PREFIX + message + ". Run pytest with --html-report=" + dir
                    + ", or point reportDir/reportFile at the report.");
        }

        if (run.getAction(PytestHtmlReportBuildAction.class) != null) {
            log.println(LOG_PREFIX + "Replacing the report published earlier in this build;"
                    + " a build keeps one pytest HTML report.");
        }
        FilePath target = new FilePath(PytestHtmlReportBuildAction.getReportDir(run));
        if (target.exists()) {
            target.deleteRecursive();
        }
        target.mkdirs();
        int copied = source.copyRecursiveTo("**/*", target);
        log.println(LOG_PREFIX + "Published " + copied + " file(s) from " + source.getRemote());

        PytestReportSummary summary = readSummary(target.child(OUTPUT_JSON), log);
        run.addOrReplaceAction(new PytestHtmlReportBuildAction(file, summary));

        if (summary == null) {
            return;
        }
        log.println(LOG_PREFIX + summary.getTotal() + " tests: " + summary.getPassed() + " passed, "
                + summary.getFailed() + " failed, " + summary.getErrors() + " errors, " + summary.getSkipped()
                + " skipped");
        if (markUnstableOnFailure && summary.getFailedOrErrored() > 0) {
            log.println(LOG_PREFIX + "Marking the build unstable because tests failed.");
            run.setResult(Result.UNSTABLE);
        }
    }

    /** Whether a path names a file inside a folder: relative, and without {@code ..} segments. */
    static boolean isSafeRelativePath(@NonNull String path) {
        String normalized = path.replace('\\', '/');
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            return false;
        }
        for (String segment : normalized.split("/")) {
            if (segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    @CheckForNull
    private static PytestReportSummary readSummary(FilePath json, PrintStream log)
            throws IOException, InterruptedException {
        if (!json.exists()) {
            log.println(LOG_PREFIX + OUTPUT_JSON + " not found; the report is published without a summary.");
            return null;
        }
        long size = json.length();
        if (size > MAX_OUTPUT_JSON_BYTES) {
            log.println(LOG_PREFIX + OUTPUT_JSON + " is " + size + " bytes, more than the " + MAX_OUTPUT_JSON_BYTES
                    + " this plugin reads; the report is published without a summary.");
            return null;
        }
        try {
            return PytestReportSummary.parse(json.readToString());
        } catch (RuntimeException e) {
            // A summary that cannot be read must not stop the report from being published.
            log.println(LOG_PREFIX + "Could not read " + OUTPUT_JSON + ": " + e.getMessage());
            return null;
        }
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Extension
    @Symbol("publishPytestHtmlReport")
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Publish pytest HTML report";
        }

        public String getDefaultReportDir() {
            return DEFAULT_REPORT_DIR;
        }

        public String getDefaultReportFile() {
            return DEFAULT_REPORT_FILE;
        }
    }
}
