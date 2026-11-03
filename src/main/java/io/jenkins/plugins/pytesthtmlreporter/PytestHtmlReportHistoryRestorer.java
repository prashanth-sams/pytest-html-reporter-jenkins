package io.jenkins.plugins.pytesthtmlreporter;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Puts the history pytest-html-reporter keeps next to its report back into the workspace before the
 * tests run, so the report's Archives, trends and analytics carry on when every build starts from a
 * clean workspace (ephemeral agents, containers, workspace cleanup).
 *
 * <p>pytest-html-reporter moves the previous run's {@code output.json} into {@code archive/} when the
 * previous report is still there, and reads {@code archive/*.json} for its history. This step copies
 * those files from the last build that published a report.
 *
 * <p>Pipeline: {@code restorePytestHtmlReportHistory reportDir: 'report'}, before running pytest.
 */
public class PytestHtmlReportHistoryRestorer extends Builder implements SimpleBuildStep {

    private static final String ARCHIVE = "archive";

    /** How many completed builds are searched for a report. */
    private static final int SEARCH_LIMIT = 50;

    private String reportDir = PytestHtmlReportPublisher.DEFAULT_REPORT_DIR;

    @DataBoundConstructor
    public PytestHtmlReportHistoryRestorer() {}

    @NonNull
    public String getReportDir() {
        return reportDir;
    }

    /** Workspace-relative folder given to {@code pytest --html-report}. Defaults to {@code report}. */
    @DataBoundSetter
    public void setReportDir(String reportDir) {
        String value = Util.fixEmptyAndTrim(reportDir);
        this.reportDir = value == null ? PytestHtmlReportPublisher.DEFAULT_REPORT_DIR : value;
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
        FilePath target = workspace.child(env.expand(reportDir));
        if (target.child(PytestHtmlReportPublisher.OUTPUT_JSON).exists()) {
            log.println(PytestHtmlReportPublisher.LOG_PREFIX + target.getRemote()
                    + " already holds a report history; nothing to restore.");
            return;
        }
        Run<?, ?> previous = findPreviousReport(run);
        if (previous == null) {
            log.println(PytestHtmlReportPublisher.LOG_PREFIX
                    + "No earlier build published a pytest HTML report; nothing to restore.");
            return;
        }
        PytestHtmlReportBuildAction action = previous.getAction(PytestHtmlReportBuildAction.class);
        Path base = PytestHtmlReportBuildAction.getReportDir(previous).toPath();
        List<String> files = historyFiles(base, action == null ? null : action.getIndexFile());
        for (String relative : files) {
            new FilePath(base.resolve(relative).toFile()).copyTo(target.child(relative));
        }
        log.println(PytestHtmlReportPublisher.LOG_PREFIX + "Restored " + files.size()
                + " file(s) of report history from " + previous.getFullDisplayName() + " into "
                + target.getRemote());
    }

    /** The newest completed build before this one that published a report with a summary file. */
    @CheckForNull
    static Run<?, ?> findPreviousReport(@NonNull Run<?, ?> run) {
        int searched = 0;
        for (Run<?, ?> r = run.getPreviousCompletedBuild();
                r != null && searched < SEARCH_LIMIT;
                r = r.getPreviousCompletedBuild()) {
            searched++;
            if (r.getAction(PytestHtmlReportBuildAction.class) != null
                    && new File(PytestHtmlReportBuildAction.getReportDir(r), PytestHtmlReportPublisher.OUTPUT_JSON)
                            .isFile()) {
                return r;
            }
        }
        return null;
    }

    /**
     * The files to restore, relative to the build's copy of the report: {@code output.json}, the
     * report itself and {@code archive/*.json}. The copy came from the build's workspace, so only
     * regular files whose real location is inside the copy are returned: a symbolic link planted in
     * the workspace must not make Jenkins copy files from the controller to the agent.
     */
    @NonNull
    static List<String> historyFiles(@NonNull Path base, @CheckForNull String indexFile) throws IOException {
        List<String> files = new ArrayList<>();
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
            return files;
        }
        Path realBase = base.toRealPath();
        addIfSafe(files, realBase, base, PytestHtmlReportPublisher.OUTPUT_JSON);
        if (indexFile != null && PytestHtmlReportPublisher.isSafeRelativePath(indexFile)) {
            addIfSafe(files, realBase, base, indexFile);
        }
        Path archive = base.resolve(ARCHIVE);
        if (Files.isDirectory(archive, LinkOption.NOFOLLOW_LINKS)) {
            List<String> archived = new ArrayList<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(archive, "*.json")) {
                for (Path entry : entries) {
                    archived.add(ARCHIVE + "/" + entry.getFileName());
                }
            }
            Collections.sort(archived);
            for (String relative : archived) {
                addIfSafe(files, realBase, base, relative);
            }
        }
        return files;
    }

    private static void addIfSafe(List<String> files, Path realBase, Path base, String relative) throws IOException {
        Path file = base.resolve(relative).normalize();
        if (!file.startsWith(base) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (file.toRealPath().startsWith(realBase)) {
            files.add(relative);
        }
    }

    @Extension
    @Symbol("restorePytestHtmlReportHistory")
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Restore pytest HTML report history";
        }

        public String getDefaultReportDir() {
            return PytestHtmlReportPublisher.DEFAULT_REPORT_DIR;
        }
    }
}
