package io.jenkins.plugins.pytesthtmlreporter;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import java.io.IOException;
import java.util.List;
import org.htmlunit.Page;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class PytestHtmlReportPublisherTest {

    private static final String REPORT_HTML = "<html><body><h1>pytest-html-reporter sample</h1></body></html>";
    private static final String PASSING_JSON =
            "{\"status\": \"PASS\", \"total_suite\": 1, \"total_tests\": \"4\","
                    + " \"status_list\": {\"pass\": \"4\", \"fail\": \"0\", \"skip\": \"0\", \"error\": \"0\"}}";

    /** Writes a report the way {@code pytest --html-report=<dir>} lays one out. */
    private static final class WriteReport extends TestBuilder {
        private final String dir;
        private final String json;

        WriteReport(String dir, String json) {
            this.dir = dir;
            this.json = json;
        }

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                throws InterruptedException, IOException {
            FilePath report = build.getWorkspace().child(dir);
            report.child(PytestHtmlReportPublisher.DEFAULT_REPORT_FILE).write(REPORT_HTML, "UTF-8");
            report.child("pytest_screenshots/test_login.png").write("png", "UTF-8");
            if (json != null) {
                report.child("output.json").write(json, "UTF-8");
            }
            return true;
        }
    }

    @Test
    void publishesReportAndMarksUnstableOnFailures(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject();
        p.getBuildersList().add(new WriteReport("report", PytestReportSummaryTest.sampleJson()));
        p.getPublishersList().add(new PytestHtmlReportPublisher());

        FreeStyleBuild b = j.assertBuildStatus(Result.UNSTABLE, p.scheduleBuild2(0));
        j.assertLogContains("11 tests: 8 passed, 1 failed, 0 errors, 1 skipped", b);

        PytestHtmlReportBuildAction action = b.getAction(PytestHtmlReportBuildAction.class);
        assertNotNull(action);
        assertEquals(PytestHtmlReportPublisher.DEFAULT_REPORT_FILE, action.getIndexFile());
        assertNotNull(action.getSummary());
        assertEquals(1, action.getSummary().getFailed());

        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            Page index = wc.goTo(b.getUrl() + "pytest-html-report/", null);
            assertThat(index.getWebResponse().getContentAsString(), containsString("pytest-html-reporter sample"));
            Page screenshot = wc.goTo(b.getUrl() + "pytest-html-report/pytest_screenshots/test_login.png", null);
            assertEquals("png", screenshot.getWebResponse().getContentAsString());
            Page buildPage = wc.goTo(b.getUrl(), null);
            assertThat(buildPage.getWebResponse().getContentAsString(), containsString("11 tests:"));
        }
    }

    @Test
    void passingRunStaysSuccessfulAndBuildsTrend(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject();
        p.getBuildersList().add(new WriteReport("report", PASSING_JSON));
        p.getPublishersList().add(new PytestHtmlReportPublisher());

        j.buildAndAssertSuccess(p);
        FreeStyleBuild second = j.buildAndAssertSuccess(p);

        PytestHtmlReportProjectAction projectAction = p.getAction(PytestHtmlReportProjectAction.class);
        assertNotNull(projectAction);
        assertEquals(second, projectAction.getLatestRun());
        List<PytestHtmlReportProjectAction.TrendPoint> trend = projectAction.getTrend();
        assertEquals(2, trend.size());
        assertEquals(1, trend.get(0).getNumber());
        assertTrue(projectAction.isTrendAvailable());
        assertThat(projectAction.getTrendSvg(), containsString("<rect"));

        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            Page latest = wc.goTo(p.getUrl() + "pytest-html-report/", null);
            assertThat(latest.getUrl().toString(), containsString(second.getUrl() + "pytest-html-report/"));
            assertThat(latest.getWebResponse().getContentAsString(), containsString("pytest-html-reporter sample"));
            Page jobPage = wc.goTo(p.getUrl(), null);
            assertThat(jobPage.getWebResponse().getContentAsString(), containsString("pytest results trend"));
        }
    }

    @Test
    void failuresDoNotChangeResultWhenDisabled(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject();
        p.getBuildersList().add(new WriteReport("report", PytestReportSummaryTest.sampleJson()));
        PytestHtmlReportPublisher publisher = new PytestHtmlReportPublisher();
        publisher.setMarkUnstableOnFailure(false);
        p.getPublishersList().add(publisher);

        j.buildAndAssertSuccess(p);
    }

    @Test
    void reportWithoutOutputJsonIsStillPublished(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject();
        p.getBuildersList().add(new WriteReport("report", null));
        p.getPublishersList().add(new PytestHtmlReportPublisher());

        FreeStyleBuild b = j.buildAndAssertSuccess(p);
        PytestHtmlReportBuildAction action = b.getAction(PytestHtmlReportBuildAction.class);
        assertNotNull(action);
        assertNull(action.getSummary());
        assertFalse(p.getAction(PytestHtmlReportProjectAction.class).isTrendAvailable());
    }

    @Test
    void missingReportFailsTheBuild(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject();
        p.getPublishersList().add(new PytestHtmlReportPublisher());

        FreeStyleBuild b = j.buildAndAssertStatus(Result.FAILURE, p);
        j.assertLogContains("No pytest HTML report found", b);
        assertNull(b.getAction(PytestHtmlReportBuildAction.class));
    }

    @Test
    void missingReportIsToleratedWhenAllowed(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject();
        PytestHtmlReportPublisher publisher = new PytestHtmlReportPublisher();
        publisher.setAllowMissing(true);
        p.getPublishersList().add(publisher);

        FreeStyleBuild b = j.buildAndAssertSuccess(p);
        j.assertLogContains("nothing to publish", b);
    }

    @Test
    void configurationRoundTrips(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject();
        PytestHtmlReportPublisher publisher = new PytestHtmlReportPublisher();
        publisher.setReportDir("reports/${BUILD_NUMBER}");
        publisher.setReportFile("index.html");
        publisher.setAllowMissing(true);
        publisher.setMarkUnstableOnFailure(false);
        p.getPublishersList().add(publisher);

        j.configRoundtrip(p);

        j.assertEqualDataBoundBeans(publisher, p.getPublishersList().get(PytestHtmlReportPublisher.class));
    }

    @Test
    void pipelineStep(JenkinsRule j) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "pipeline");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: 'out/index.html', text: '<html>pipeline report</html>'\n"
                        + "  writeFile file: 'out/output.json', text: '" + PASSING_JSON + "'\n"
                        + "  publishPytestHtmlReport reportDir: 'out', reportFile: 'index.html'\n"
                        + "}",
                true));

        WorkflowRun run = j.buildAndAssertSuccess(job);
        PytestHtmlReportBuildAction action = run.getAction(PytestHtmlReportBuildAction.class);
        assertNotNull(action);
        assertEquals(4, action.getSummary().getPassed());

        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            Page index = wc.goTo(run.getUrl() + "pytest-html-report/", null);
            assertThat(index.getWebResponse().getContentAsString(), containsString("pipeline report"));
        }
    }
}
