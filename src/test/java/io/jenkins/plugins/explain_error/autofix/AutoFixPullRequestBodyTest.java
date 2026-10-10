package io.jenkins.plugins.explain_error.autofix;

import static org.junit.jupiter.api.Assertions.assertEquals;

import hudson.model.FreeStyleBuild;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class AutoFixPullRequestBodyTest {

    @Test
    void bodyLinksBackToTheBuild(JenkinsRule jenkins) throws Exception {
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(jenkins.createFreeStyleProject("inventory-service"));
        FixSuggestion suggestion = new FixSuggestion(true, "why", "high", "dependency", List.of(), "pin requests");

        String body = new AutoFixOrchestrator().buildPrBody(build, suggestion, "{buildLink}\n{buildUrl}");

        String url = jenkins.getURL() + "job/inventory-service/1/";
        assertEquals("[#1](" + url + ")\n" + url, body);
    }
}
