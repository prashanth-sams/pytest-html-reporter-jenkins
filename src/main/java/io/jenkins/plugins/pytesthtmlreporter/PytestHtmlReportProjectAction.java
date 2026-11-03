package io.jenkins.plugins.pytesthtmlreporter;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Action;
import hudson.model.Job;
import hudson.model.Run;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

/** Links a job to its latest pytest report and draws the results trend on the job page. */
public class PytestHtmlReportProjectAction implements Action {

    /** How many builds back the trend chart reaches. */
    static final int TREND_BUILDS = 30;

    /** How many completed builds are searched for a report. */
    private static final int SEARCH_LIMIT = 100;

    private static final int CHART_WIDTH = 520;
    private static final int CHART_HEIGHT = 210;
    private static final int AXIS_LEFT = 40;
    private static final int AXIS_BOTTOM = 22;
    private static final int TOP_PADDING = 28;

    private static final String[][] SERIES = {
        {"phr-pass", "passed"}, {"phr-fail", "failed"}, {"phr-error", "errors"}, {"phr-skip", "skipped"}
    };

    private final Job<?, ?> job;

    public PytestHtmlReportProjectAction(@NonNull Job<?, ?> job) {
        this.job = job;
    }

    public Job<?, ?> getJob() {
        return job;
    }

    /**
     * Always shown: this action only exists while the job's last successful build carries a report
     * (see {@link PytestHtmlReportBuildAction#getProjectActions()}).
     */
    @Override
    public String getIconFileName() {
        return PytestHtmlReportBuildAction.ICON;
    }

    @Override
    public String getDisplayName() {
        return "Latest pytest HTML Report";
    }

    @Override
    public String getUrlName() {
        return PytestHtmlReportBuildAction.URL_NAME;
    }

    /** The newest completed build that published a report, or {@code null}. */
    @CheckForNull
    public Run<?, ?> getLatestRun() {
        int searched = 0;
        for (Run<?, ?> r = job.getLastCompletedBuild();
                r != null && searched < SEARCH_LIMIT;
                r = r.getPreviousCompletedBuild()) {
            if (r.getAction(PytestHtmlReportBuildAction.class) != null) {
                return r;
            }
            searched++;
        }
        return null;
    }

    /** Redirects to the report of the newest build that has one. */
    public void doIndex(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        Run<?, ?> latest = getLatestRun();
        if (latest == null) {
            rsp.sendError(StaplerResponse2.SC_NOT_FOUND, "No build of this job has published a pytest HTML report.");
            return;
        }
        rsp.sendRedirect2(req.getContextPath() + "/" + latest.getUrl() + PytestHtmlReportBuildAction.URL_NAME + "/");
    }

    /** One point of the trend: a build and the totals it recorded. */
    public static final class TrendPoint {
        private final int number;
        private final PytestReportSummary summary;

        TrendPoint(int number, PytestReportSummary summary) {
            this.number = number;
            this.summary = summary;
        }

        public int getNumber() {
            return number;
        }

        public PytestReportSummary getSummary() {
            return summary;
        }
    }

    /** The builds that recorded totals, oldest first, at most {@link #TREND_BUILDS}. */
    @NonNull
    public List<TrendPoint> getTrend() {
        List<TrendPoint> points = new ArrayList<>();
        int searched = 0;
        for (Run<?, ?> r = job.getLastCompletedBuild();
                r != null && points.size() < TREND_BUILDS && searched < SEARCH_LIMIT;
                r = r.getPreviousCompletedBuild()) {
            searched++;
            PytestHtmlReportBuildAction action = r.getAction(PytestHtmlReportBuildAction.class);
            if (action != null && action.getSummary() != null) {
                points.add(new TrendPoint(r.getNumber(), action.getSummary()));
            }
        }
        Collections.reverse(points);
        return points;
    }

    /** Whether there are at least two builds to draw a trend from. */
    public boolean isTrendAvailable() {
        return getTrend().size() > 1;
    }

    /**
     * The trend as a stacked bar chart in inline SVG, or an empty string when fewer than two builds
     * recorded totals. Built here rather than in Jelly so that only numbers computed on the controller
     * reach the page: it carries no text from the build. Colors come from {@code trend.css}, which maps
     * the classes to Jenkins' theme variables. Each bar links to that build's report, relative to the
     * job page the chart is shown on.
     */
    @NonNull
    public String getTrendSvg() {
        List<TrendPoint> points = getTrend();
        if (points.size() < 2) {
            return "";
        }
        int max = 1;
        for (TrendPoint p : points) {
            PytestReportSummary s = p.getSummary();
            max = Math.max(max, s.getPassed() + s.getFailed() + s.getErrors() + s.getSkipped());
        }
        int plotWidth = CHART_WIDTH - AXIS_LEFT - 8;
        int plotHeight = CHART_HEIGHT - AXIS_BOTTOM - TOP_PADDING;
        double slot = (double) plotWidth / points.size();
        double barWidth = Math.max(2, slot * 0.7);
        int baseline = TOP_PADDING + plotHeight;

        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" class=\"phr-trend\" viewBox=\"0 0 ")
                .append(CHART_WIDTH)
                .append(' ')
                .append(CHART_HEIGHT)
                .append("\" role=\"img\" aria-label=\"pytest results of the last ")
                .append(points.size())
                .append(" builds\">");

        // legend
        int legendX = AXIS_LEFT;
        for (String[] series : SERIES) {
            svg.append("<rect class=\"")
                    .append(series[0])
                    .append("\" x=\"")
                    .append(legendX)
                    .append("\" y=\"4\" width=\"10\" height=\"10\"/>");
            svg.append("<text class=\"phr-legend\" x=\"")
                    .append(legendX + 14)
                    .append("\" y=\"13\">")
                    .append(series[1])
                    .append("</text>");
            legendX += 80;
        }

        // axis with its maximum and zero
        svg.append("<line class=\"phr-axis\" x1=\"")
                .append(AXIS_LEFT)
                .append("\" y1=\"")
                .append(baseline)
                .append("\" x2=\"")
                .append(CHART_WIDTH - 4)
                .append("\" y2=\"")
                .append(baseline)
                .append("\"/>");
        svg.append("<text class=\"phr-label\" x=\"")
                .append(AXIS_LEFT - 6)
                .append("\" y=\"")
                .append(TOP_PADDING + 9)
                .append("\" text-anchor=\"end\">")
                .append(max)
                .append("</text>");
        svg.append("<text class=\"phr-label\" x=\"")
                .append(AXIS_LEFT - 6)
                .append("\" y=\"")
                .append(baseline)
                .append("\" text-anchor=\"end\">0</text>");

        int labelEvery = Math.max(1, (int) Math.ceil(points.size() / 10.0));
        for (int i = 0; i < points.size(); i++) {
            TrendPoint p = points.get(i);
            PytestReportSummary s = p.getSummary();
            double x = AXIS_LEFT + i * slot + (slot - barWidth) / 2;
            svg.append("<a href=\"")
                    .append(p.getNumber())
                    .append('/')
                    .append(PytestHtmlReportBuildAction.URL_NAME)
                    .append("/\"><title>#")
                    .append(p.getNumber())
                    .append(": ")
                    .append(s.getPassed())
                    .append(" passed, ")
                    .append(s.getFailed())
                    .append(" failed, ")
                    .append(s.getErrors())
                    .append(" errors, ")
                    .append(s.getSkipped())
                    .append(" skipped</title>");
            double y = baseline;
            y = bar(svg, x, y, barWidth, s.getPassed(), max, plotHeight, "phr-pass");
            y = bar(svg, x, y, barWidth, s.getSkipped(), max, plotHeight, "phr-skip");
            y = bar(svg, x, y, barWidth, s.getErrors(), max, plotHeight, "phr-error");
            bar(svg, x, y, barWidth, s.getFailed(), max, plotHeight, "phr-fail");
            svg.append("</a>");
            if (i % labelEvery == 0 || i == points.size() - 1) {
                svg.append("<text class=\"phr-label\" x=\"")
                        .append(fmt(x + barWidth / 2))
                        .append("\" y=\"")
                        .append(CHART_HEIGHT - 6)
                        .append("\" text-anchor=\"middle\">#")
                        .append(p.getNumber())
                        .append("</text>");
            }
        }
        svg.append("</svg>");
        return svg.toString();
    }

    private static double bar(
            StringBuilder svg,
            double x,
            double bottom,
            double width,
            int count,
            int max,
            int plotHeight,
            String cssClass) {
        if (count <= 0) {
            return bottom;
        }
        double height = (double) count * plotHeight / max;
        double top = bottom - height;
        svg.append("<rect class=\"")
                .append(cssClass)
                .append("\" x=\"")
                .append(fmt(x))
                .append("\" y=\"")
                .append(fmt(top))
                .append("\" width=\"")
                .append(fmt(width))
                .append("\" height=\"")
                .append(fmt(height))
                .append("\"/>");
        return top;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
