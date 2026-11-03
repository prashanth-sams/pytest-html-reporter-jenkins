/*
 * pytest-html-reporter writes a report that needs inline JavaScript. Jenkins serves files produced by
 * builds with a Content-Security-Policy that blocks it, unless a Resource Root URL is configured. This
 * asks for the report the way the browser will and shows the hint only when its scripts would be blocked.
 */
(function () {
  function blocksInlineScripts(policy) {
    if (!policy) {
      return false;
    }
    var directives = {};
    policy.split(";").forEach(function (part) {
      var tokens = part.trim().split(/\s+/);
      if (tokens[0]) {
        directives[tokens[0].toLowerCase()] = tokens.slice(1);
      }
    });
    if (directives.sandbox && directives.sandbox.indexOf("allow-scripts") === -1) {
      return true;
    }
    var scripts = directives["script-src"] || directives["default-src"];
    return Boolean(scripts) && scripts.indexOf("'unsafe-inline'") === -1;
  }

  function check(hint) {
    if (typeof fetch !== "function") {
      return;
    }
    try {
      fetch(hint.getAttribute("data-report-url"), {
        method: "HEAD",
        redirect: "manual",
        credentials: "same-origin"
      })
        .then(function (response) {
          // A redirect means the report is served from the Resource Root URL, where its scripts run.
          if (response.type === "opaqueredirect") {
            return;
          }
          if (blocksInlineScripts(response.headers.get("Content-Security-Policy"))) {
            hint.classList.remove("jenkins-hidden");
          }
        })
        .catch(function () {
          // The hint is only advice; leave it hidden when the report cannot be checked.
        });
    } catch (e) {
      // Same as above: never let the hint break the build page.
    }
  }

  Behaviour.specify(".phr-csp-hint", "phr-csp-hint", 0, check);
})();
