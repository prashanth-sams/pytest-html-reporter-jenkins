# pytest HTML Reporter

Publishes the report written by [pytest-html-reporter](https://github.com/prashanth-sams/pytest-html-reporter)
in Jenkins: every build gets a link to its own copy of the report, the build page shows the run's totals, the job
page charts pytest results across builds, and the report keeps its history even on agents that start every build
from a clean workspace.

## What it does

| Where | What you get |
|---|---|
| Build page | A **pytest HTML Report** link and a summary: total, passed, failed, errors, skipped, xfailed / xpassed, reruns, pass rate, and coverage when `pytest-cov` ran |
| Build sidebar | **pytest HTML Report**, serving the full report with its screenshots and coverage pages |
| Job page | A **pytest results trend** chart of the last 30 builds, each bar linking to that build's report, and a **Latest pytest HTML Report** link |
| Build result | Optionally marks the build **unstable** when tests failed or errored |
| Report history | `restorePytestHtmlReportHistory` brings the report's *Archives*, trends and analytics back into a clean workspace |

The plugin reads the totals from the `output.json` file that pytest-html-reporter writes next to the report.
If that file is missing, the report is still published, just without the summary and trend.

## Usage

Install `pytest-html-reporter` in the Python environment that runs your tests:

```
pip install pytest-html-reporter
```

### Pipeline

```groovy
pipeline {
    agent any
    stages {
        stage('Test') {
            steps {
                sh 'pip install -r requirements.txt pytest-html-reporter'
                // Optional: keeps the report's history when the workspace starts out clean
                restorePytestHtmlReportHistory reportDir: 'report'
                // pytest exits non-zero when a test fails; let the publisher decide the build result
                sh 'pytest tests/ --html-report=./report || true'
            }
        }
    }
    post {
        always {
            publishPytestHtmlReport reportDir: 'report'
        }
    }
}
```

#### `publishPytestHtmlReport`

Copies the report into the build and records its totals. All parameters are optional:

| Parameter | Default | Description |
|---|---|---|
| `reportDir` | `report` | Workspace-relative folder that pytest-html-reporter wrote into: the value of `--html-report`. Environment variables are expanded. Everything in it is published. |
| `reportFile` | `pytest_html_report.html` | The report's file name inside `reportDir`. Set it when `--html-report` names a file, e.g. `--html-report=./out/index.html` → `reportDir: 'out', reportFile: 'index.html'`. |
| `markUnstableOnFailure` | `true` | Mark the build unstable when any test failed or errored. Skips and `xfail` do not count. |
| `allowMissing` | `false` | Log a missing report instead of failing the build. |

A build keeps one report; publishing a second time in the same build replaces the first. To combine sharded or
parallel runs into one report, use `pytest-html-reporter merge` before publishing.

#### `restorePytestHtmlReportHistory`

pytest-html-reporter keeps the history behind its *Archives* tab, trends and analytics in the report folder itself:
each run moves the previous `output.json` into `archive/`. On agents that start every build from a clean
workspace (containers, Kubernetes, workspace cleanup) that folder is gone, and every report starts its history over.

Run this step before pytest. It copies `output.json`, `archive/*.json` and the previous report from the last build
that published one back into the workspace, so pytest-html-reporter carries on where it left off. It does nothing
when the workspace still holds a report history, and only copies regular files from inside the published report.

| Parameter | Default | Description |
|---|---|---|
| `reportDir` | `report` | The same folder as in `publishPytestHtmlReport`. |

Retention still follows pytest-html-reporter's own `--archive-count`, `--archive-days` and `--archive-since` options.

The Snippet Generator (**Pipeline Syntax**) builds both calls for you.

### Freestyle

* Add the build step **Restore pytest HTML report history** before the step that runs pytest (optional).
* Add the post-build action **Publish pytest HTML report**, with the report directory given to `--html-report`.

## Viewing the report: Content Security Policy

pytest-html-reporter writes a single interactive page that relies on inline JavaScript. Jenkins serves files
produced by builds with a strict
[Content Security Policy](https://www.jenkins.io/doc/book/security/user-content/), which blocks that JavaScript, so
the report opens but its tabs and charts do not respond. When that is the case, the build page says so next to the
report link.

The recommended fix is to set a **Resource Root URL** under *Manage Jenkins → System*. Jenkins then serves build
content from that separate address, which keeps Jenkins safe while letting the report's scripts run. See
[Resource Root URL](https://www.jenkins.io/doc/book/security/user-content/#resource-root-url).

Relaxing the Content Security Policy globally (the `hudson.model.DirectoryBrowserSupport.CSP` system property) also
works, but it applies to every file every build produces and is not recommended.

## Permissions

Viewing a report needs read access to the job. When the optional *Artifacts* permission is enabled
(`hudson.security.ArtifactsPermission`), reports need it too, like archived artifacts.

## Building from source

```
mvn verify
```

Run a development Jenkins with the plugin installed:

```
mvn hpi:run
```

Then open <http://localhost:8080/jenkins/>.

## Reporting issues and contributing

Report bugs and feature requests in the
[issue tracker](https://github.com/jenkinsci/pytest-html-reporter-plugin/issues). For issues with the report itself,
use the [pytest-html-reporter tracker](https://github.com/prashanth-sams/pytest-html-reporter/issues).

Pull requests are welcome; please include tests. See the
[Jenkins contribution guidelines](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md).

## License

Licensed under the [MIT License](LICENSE).
