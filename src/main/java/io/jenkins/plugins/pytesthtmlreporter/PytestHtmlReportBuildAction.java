package io.jenkins.plugins.pytesthtmlreporter;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.FilePath;
import hudson.Functions;
import hudson.model.Action;
import hudson.model.DirectoryBrowserSupport;
import hudson.model.Run;
import jakarta.servlet.ServletException;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import jenkins.model.RunAction2;
import jenkins.tasks.SimpleBuildStep;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

/** Serves a build's copy of the pytest HTML report and carries the run's totals. */
public class PytestHtmlReportBuildAction implements RunAction2, SimpleBuildStep.LastBuildAction {

    static final String URL_NAME = "pytest-html-report";
    static final String ICON = "symbol-document-text-outline plugin-ionicons-api";
    private static final String REPORT_FOLDER = "pytest-html-reporter";

    private transient Run<?, ?> run;
    private final String indexFile;

    @CheckForNull
    private final PytestReportSummary summary;

    public PytestHtmlReportBuildAction(@NonNull String indexFile, @CheckForNull PytestReportSummary summary) {
        this.indexFile = indexFile;
        this.summary = summary;
    }

    /** Where a build keeps its copy of the report. */
    @NonNull
    static File getReportDir(@NonNull Run<?, ?> run) {
        return new File(run.getRootDir(), REPORT_FOLDER);
    }

    @Override
    public void onAttached(Run<?, ?> r) {
        this.run = r;
    }

    @Override
    public void onLoad(Run<?, ?> r) {
        this.run = r;
    }

    public Run<?, ?> getRun() {
        return run;
    }

    @Override
    public String getIconFileName() {
        return ICON;
    }

    @Override
    public String getDisplayName() {
        return "pytest HTML Report";
    }

    @Override
    public String getUrlName() {
        return URL_NAME;
    }

    @NonNull
    public String getIndexFile() {
        return indexFile;
    }

    @CheckForNull
    public PytestReportSummary getSummary() {
        return summary;
    }

    /**
     * Serves the report and every file next to it (screenshots, coverage pages). Files are served
     * through {@link DirectoryBrowserSupport}, so Jenkins' Content-Security-Policy and resource root
     * URL settings apply to them, and like archived artifacts they need the Artifacts permission when
     * that permission is enabled.
     */
    public void doDynamic(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        if (Functions.isArtifactsPermissionEnabled()) {
            run.checkPermission(Run.ARTIFACTS);
        }
        DirectoryBrowserSupport dbs =
                new DirectoryBrowserSupport(this, new FilePath(getReportDir(run)), getDisplayName(), ICON, false);
        dbs.setIndexFileName(indexFile);
        dbs.generateResponse(req, rsp, this);
    }

    @Override
    public Collection<? extends Action> getProjectActions() {
        return Collections.singleton(new PytestHtmlReportProjectAction(run.getParent()));
    }
}
