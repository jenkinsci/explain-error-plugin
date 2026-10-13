package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Result;
import io.jenkins.plugins.explain_error.provider.FakeAIProvider;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class PipelineGraphViewDecoratorTest {

    @Test
    void onlyTheStagesPageOfABuildCounts(JenkinsRule jenkins) {
        assertTrue(PipelineGraphViewDecorator.isStagesPage("stages"));
        assertTrue(PipelineGraphViewDecorator.isStagesPage("stages/tree"));
        assertFalse(PipelineGraphViewDecorator.isStagesPage("console"));
        assertFalse(PipelineGraphViewDecorator.isStagesPage("stages-archive"));
        assertFalse(PipelineGraphViewDecorator.isStagesPage("artifact/stages/report.txt"));
        assertFalse(PipelineGraphViewDecorator.isStagesPage(""));
    }

    @Test
    void aJobCalledStagesGetsOneCardOnItsConsolePage(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl.get().setAiProvider(new FakeAIProvider());
        WorkflowJob job = jenkins.jenkins.createProject(WorkflowJob.class, "stages");
        job.setDefinition(new CpsFlowDefinition("error 'boom'", true));
        WorkflowRun run = jenkins.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));

        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            HtmlPage console = client.getPage(run, "console");

            assertEquals(1, console.querySelectorAll("#explain-error-container").size());
            DomElement card = console.getElementById("explain-error-container");
            assertEquals("console", card.getAttribute("data-surface"));
            assertNull(console.querySelector("script[src*='explain-error-graph-view']"),
                    "the Graph View script belongs to the Stages page only");
        }
    }
}
