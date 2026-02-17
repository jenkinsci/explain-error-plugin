package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import java.util.List;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class PipelineLogExtractorTest {

    @Test
    void testNullFlowExecutionFallsBackToBuildLog(JenkinsRule jenkins) throws Exception {
        // Create a mock WorkflowRun where getExecution() returns null
        WorkflowRun mockRun = mock(WorkflowRun.class);
        when(mockRun.getExecution()).thenReturn(null);
        when(mockRun.getLog(100)).thenReturn(List.of("Build started", "ERROR: Something failed"));
        when(mockRun.getUrl()).thenReturn("job/test/1/");

        PipelineLogExtractor extractor = new PipelineLogExtractor(mockRun, 100);

        // Should not throw NullPointerException
        List<String> logLines = assertDoesNotThrow(() -> extractor.getFailedStepLog());

        // Should fall back to build log
        assertNotNull(logLines);
        assertEquals(2, logLines.size());
        assertEquals("ERROR: Something failed", logLines.get(1));

        // URL should be set (either console or stages depending on plugin availability)
        String url = extractor.getUrl();
        assertNotNull(url, "URL should not be null after getFailedStepLog()");
        assertTrue(url.contains("job/test/1/"), "URL should reference the build");
    }

    @Test
    void testNonPipelineBuildFallsBackToBuildLog(JenkinsRule jenkins) throws Exception {
        // FreeStyleBuild is not a WorkflowRun, so it should skip the pipeline path entirely
        FreeStyleProject project = jenkins.createFreeStyleProject();
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        PipelineLogExtractor extractor = new PipelineLogExtractor(build, 100);
        List<String> logLines = extractor.getFailedStepLog();

        assertNotNull(logLines);
        assertFalse(logLines.isEmpty());

        String url = extractor.getUrl();
        assertNotNull(url);
        assertTrue(url.contains(build.getUrl()), "URL should reference the build");
    }
}
