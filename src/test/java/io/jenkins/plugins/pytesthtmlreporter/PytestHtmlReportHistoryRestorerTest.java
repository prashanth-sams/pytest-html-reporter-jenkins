package io.jenkins.plugins.pytesthtmlreporter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class PytestHtmlReportHistoryRestorerTest {

    /** Deletes the report folder, as a fresh agent or a workspace cleanup would. */
    private static final class CleanWorkspace extends TestBuilder {
        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                throws InterruptedException, IOException {
            build.getWorkspace().child("report").deleteRecursive();
            return true;
        }
    }

    /**
     * Does to the report folder what pytest-html-reporter does: when the previous report is still
     * there, its output.json is moved into archive/, then the new report and output.json are written.
     */
    private static final class PytestRun extends TestBuilder {
        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                throws InterruptedException, IOException {
            FilePath report = build.getWorkspace().child("report");
            FilePath html = report.child(PytestHtmlReportPublisher.DEFAULT_REPORT_FILE);
            FilePath json = report.child("output.json");
            if (html.exists() && json.exists()) {
                report.child("archive").mkdirs();
                json.renameTo(report.child("archive/output_" + build.getNumber() + ".json"));
            }
            html.write(PytestHtmlReportPublisherTest.REPORT_HTML, "UTF-8");
            json.write(PytestHtmlReportPublisherTest.PASSING_JSON, "UTF-8");
            return true;
        }
    }

    @Test
    void historySurvivesCleanWorkspaces(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject();
        p.getBuildersList().add(new CleanWorkspace());
        p.getBuildersList().add(new PytestHtmlReportHistoryRestorer());
        p.getBuildersList().add(new PytestRun());
        p.getPublishersList().add(new PytestHtmlReportPublisher());

        FreeStyleBuild first = j.buildAndAssertSuccess(p);
        j.assertLogContains("No earlier build published a pytest HTML report; nothing to restore.", first);
        assertArrayEquals(new String[0], archived(first));

        FreeStyleBuild second = j.buildAndAssertSuccess(p);
        j.assertLogContains("Restored 2 file(s) of report history from " + first.getFullDisplayName(), second);
        assertArrayEquals(new String[] {"output_2.json"}, archived(second));

        FreeStyleBuild third = j.buildAndAssertSuccess(p);
        j.assertLogContains("Restored 3 file(s) of report history from " + second.getFullDisplayName(), third);
        assertArrayEquals(new String[] {"output_2.json", "output_3.json"}, archived(third));
    }

    @Test
    void existingHistoryIsKept(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject();
        p.getBuildersList().add(new PytestHtmlReportHistoryRestorer());
        p.getBuildersList().add(new PytestRun());
        p.getPublishersList().add(new PytestHtmlReportPublisher());

        j.buildAndAssertSuccess(p);
        FreeStyleBuild second = j.buildAndAssertSuccess(p);
        j.assertLogContains("already holds a report history; nothing to restore.", second);
    }

    @Test
    void configurationRoundTrips(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject();
        PytestHtmlReportHistoryRestorer restorer = new PytestHtmlReportHistoryRestorer();
        restorer.setReportDir("out/report");
        p.getBuildersList().add(restorer);

        j.configRoundtrip(p);

        j.assertEqualDataBoundBeans(restorer, p.getBuildersList().get(PytestHtmlReportHistoryRestorer.class));
    }

    @Test
    void pipelineStep(JenkinsRule j) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "pipeline");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  restorePytestHtmlReportHistory reportDir: 'out'\n"
                        + "  writeFile file: 'out/pytest_html_report.html', text: '<html>report</html>'\n"
                        + "  writeFile file: 'out/output.json', text: '{}'\n"
                        + "  publishPytestHtmlReport reportDir: 'out'\n"
                        + "  deleteDir()\n"
                        + "}",
                true));

        j.buildAndAssertSuccess(job);
        WorkflowRun second = j.buildAndAssertSuccess(job);
        j.assertLogContains("Restored 2 file(s) of report history", second);
    }

    private static String[] archived(FreeStyleBuild build) {
        String[] names = new File(PytestHtmlReportBuildAction.getReportDir(build), "archive").list();
        if (names == null) {
            return new String[0];
        }
        Arrays.sort(names);
        return names;
    }
}
