# pytest HTML Reporter

Publishes the report written by [pytest-html-reporter](https://github.com/prashanth-sams/pytest-html-reporter)
in Jenkins: every build gets a link to its own copy of the report, the build page shows the run's totals, and the
job page charts pytest results across builds.

## What it does

| Where | What you get |
|---|---|
| Build page | A **pytest HTML Report** link and a summary: total, passed, failed, errors, skipped, xfailed / xpassed, reruns, pass rate and coverage when `pytest-cov` ran |
| Build sidebar | **pytest HTML Report**, serving the full report with its screenshots and coverage pages |
| Job page | A **pytest results trend** chart for the last 30 builds, and a **Latest pytest HTML Report** link |
| Build result | Optionally marks the build **unstable** when tests failed or errored |

The plugin reads the totals from the `output.json` file that pytest-html-reporter writes next to the report.
If that file is missing, the report is still published, just without the summary and trend.

## Requirements

* Jenkins 2.568.3 or newer
* `pytest-html-reporter` installed in the Python environment that runs your tests:

  ```
  pip install pytest-html-reporter
  ```

## Usage

### Pipeline

```groovy
pipeline {
    agent any
    stages {
        stage('Test') {
            steps {
                sh 'pip install -r requirements.txt pytest-html-reporter'
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

All parameters are optional:

```groovy
publishPytestHtmlReport(
    reportDir: 'report',                      // the folder given to --html-report
    reportFile: 'pytest_html_report.html',    // the report's file name inside it
    markUnstableOnFailure: true,              // failed or errored tests make the build unstable
    allowMissing: false                       // a missing report fails the build
)
```

| Parameter | Default | Description |
|---|---|---|
| `reportDir` | `report` | Workspace-relative folder that pytest-html-reporter wrote into. Environment variables are expanded. Everything in it is published. |
| `reportFile` | `pytest_html_report.html` | The report's file name. Set it when `--html-report` names a file, e.g. `--html-report=./out/index.html` → `reportDir: 'out', reportFile: 'index.html'`. |
| `markUnstableOnFailure` | `true` | Mark the build unstable when any test failed or errored. Skips and `xfail` do not count. |
| `allowMissing` | `false` | Log a missing report instead of failing the build. |

The Snippet Generator (**Pipeline Syntax** → `publishPytestHtmlReport`) builds the call for you.

### Freestyle

Add the post-build action **Publish pytest HTML report** and set the report directory to the folder given to
`--html-report`.

## Viewing the report: Content Security Policy

pytest-html-reporter writes a single interactive page that relies on inline JavaScript and styles. Jenkins serves
files produced by builds with a strict
[Content Security Policy](https://www.jenkins.io/doc/book/security/user-content/), which blocks that JavaScript, so
the report opens but its tabs and charts do not work.

The recommended fix is to set a **Resource Root URL** under *Manage Jenkins → System*. Jenkins then serves build
content from that separate host, which keeps your Jenkins safe while letting the report's scripts run. See
[Resource Root URL](https://www.jenkins.io/doc/book/security/user-content/#resource-root-url).

Relaxing the Content Security Policy globally (the `hudson.model.DirectoryBrowserSupport.CSP` system property) also
works, but it applies to every file every build produces and is not recommended.

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
