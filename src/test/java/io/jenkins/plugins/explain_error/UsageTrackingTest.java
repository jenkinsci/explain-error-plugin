package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.explain_error.provider.OpenAIProvider;
import io.jenkins.plugins.explain_error.provider.TestProvider;
import java.util.ArrayList;
import java.util.List;
import java.io.PrintWriter;
import java.io.StringWriter;
import net.sf.json.JSONObject;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@WithJenkins
class UsageTrackingTest {

    private final RecordingUsageRecorder recorder = new RecordingUsageRecorder();

    @BeforeEach
    void setUp() {
        UsageRecorders.setRecorderSupplier(() -> List.of(recorder));
    }

    @AfterEach
    void tearDown() {
        UsageRecorders.resetRecorderSupplier();
        if (Jenkins.getInstanceOrNull() != null) {
            GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
            config.setEnableExplanation(true);
            config.setAiProvider(null);
            config.setEnableQuota(false);
            config.setMaxProviderCallsPerWindow(100);
            config.setQuotaWindow(QuotaWindow.HOURLY);
        }
    }

    @Test
    void pipelineStepSuccessEmitsUsageEvent(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl.get().setAiProvider(new TestProvider());

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "usage-pipeline-success");
        job.setDefinition(new CpsFlowDefinition("node {\n  explainError()\n}", true));

        WorkflowRun run = jenkins.buildAndAssertSuccess(job);

        assertEquals(1, recorder.events.size());
        UsageEvent event = recorder.events.get(0);
        assertEquals(UsageEvent.EntryPoint.PIPELINE_STEP, event.entryPoint());
        assertEquals(UsageEvent.Result.SUCCESS, event.result());
        assertEquals("Test", event.providerName());
        assertEquals("test-model", event.model());
        assertFalse(event.downstreamLogsCollected());
        assertTrue(event.inputLogLineCount() > 0);
        assertTrue(event.durationMillis() >= 0);
        assertTrue(run.getAction(ErrorExplanationAction.class).hasValidExplanation());
    }

    @Test
    void pipelineStepInvalidConfigurationEmitsMisconfiguredUsageEvent(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl.get().setAiProvider(new OpenAIProvider(null, "test-model", null));

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "usage-pipeline-misconfigured");
        job.setDefinition(new CpsFlowDefinition("node {\n  explainError()\n}", true));

        WorkflowRun run = jenkins.buildAndAssertSuccess(job);

        assertEquals(1, recorder.events.size());
        UsageEvent event = recorder.events.get(0);
        assertEquals(UsageEvent.EntryPoint.PIPELINE_STEP, event.entryPoint());
        assertEquals(UsageEvent.Result.MISCONFIGURED, event.result());
        assertEquals("OpenAI", event.providerName());
        assertEquals("test-model", event.model());
        assertEquals(0, event.inputLogLineCount());
        jenkins.assertLogContains("No Api key configured for OpenAI.", run);
        jenkins.assertLogContains("[explain-error] Provider configuration is invalid.", run);
    }

    @Test
    void consoleActionSuccessAndCacheHitEmitDistinctUsageEvents(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl.get().setAiProvider(new TestProvider());

        FreeStyleProject project = jenkins.createFreeStyleProject("usage-console-cache");
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        ConsoleExplainErrorAction action = new ConsoleExplainErrorAction(build);

        JSONObject firstResponse = invokeExplainConsoleError(action, null, null);
        JSONObject secondResponse = invokeExplainConsoleError(action, null, null);

        assertEquals("success", firstResponse.getString("status"));
        assertEquals("success", secondResponse.getString("status"));

        assertEquals(2, recorder.events.size());
        UsageEvent successEvent = recorder.events.get(0);
        UsageEvent cacheHitEvent = recorder.events.get(1);

        assertEquals(UsageEvent.Result.SUCCESS, successEvent.result());
        assertEquals(UsageEvent.Result.CACHE_HIT, cacheHitEvent.result());
        assertEquals(UsageEvent.EntryPoint.CONSOLE_ACTION, successEvent.entryPoint());
        assertEquals(UsageEvent.EntryPoint.CONSOLE_ACTION, cacheHitEvent.entryPoint());
        assertEquals("Test", successEvent.providerName());
        assertEquals("Test", cacheHitEvent.providerName());
        assertEquals("test-model", successEvent.model());
        assertEquals("test-model", cacheHitEvent.model());
        assertFalse(successEvent.downstreamLogsCollected());
        assertFalse(cacheHitEvent.downstreamLogsCollected());
        assertTrue(successEvent.inputLogLineCount() > 0);
        assertEquals(successEvent.inputLogLineCount(), cacheHitEvent.inputLogLineCount());
    }

    @Test
    void consoleActionDisabledRequestEmitsDisabledUsageEvent(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        config.setEnableExplanation(false);
        config.setAiProvider(new TestProvider());

        FreeStyleProject project = jenkins.createFreeStyleProject("usage-console-disabled");
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        ConsoleExplainErrorAction action = new ConsoleExplainErrorAction(build);

        JSONObject response = invokeExplainConsoleError(action, null, null);
        assertEquals("warning", response.getString("status"));
        assertEquals("Unknown", response.getString("providerName"));

        assertEquals(1, recorder.events.size());
        UsageEvent event = recorder.events.get(0);
        assertEquals(UsageEvent.EntryPoint.CONSOLE_ACTION, event.entryPoint());
        assertEquals(UsageEvent.Result.DISABLED, event.result());
        assertEquals("Test", event.providerName());
        assertEquals("test-model", event.model());
        assertEquals(0, event.inputLogLineCount());
    }

    @Test
    void pipelineStepDisabledRequestEmitsDisabledUsageEventWithConfiguredProvider(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        config.setEnableExplanation(false);
        config.setAiProvider(new TestProvider());

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "usage-pipeline-disabled");
        job.setDefinition(new CpsFlowDefinition("node {\n  explainError()\n}", true));

        WorkflowRun run = jenkins.buildAndAssertSuccess(job);

        assertEquals(1, recorder.events.size());
        UsageEvent event = recorder.events.get(0);
        assertEquals(UsageEvent.EntryPoint.PIPELINE_STEP, event.entryPoint());
        assertEquals(UsageEvent.Result.DISABLED, event.result());
        assertEquals("Test", event.providerName());
        assertEquals("test-model", event.model());
        assertEquals(0, event.inputLogLineCount());
        jenkins.assertLogContains("[explain-error] Explanation is disabled by configuration.", run);
    }

    @Test
    void directProviderCallsAreNotTracked() throws Exception {
        TestProvider provider = new TestProvider();

        provider.explainError("Direct provider call", null);

        assertTrue(recorder.events.isEmpty());
    }

    @Test
    void pipelineStepQuotaExceededEmitsQuotaRejectedEvent(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        config.setAiProvider(new TestProvider());
        config.setEnableQuota(true);
        config.setMaxProviderCallsPerWindow(0);
        config.setQuotaWindow(QuotaWindow.HOURLY);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "quota-pipeline-rejected");
        job.setDefinition(new CpsFlowDefinition("node {\n  explainError()\n}", true));

        WorkflowRun run = jenkins.buildAndAssertSuccess(job);

        assertEquals(1, recorder.events.size());
        UsageEvent event = recorder.events.get(0);
        assertEquals(UsageEvent.EntryPoint.PIPELINE_STEP, event.entryPoint());
        assertEquals(UsageEvent.Result.QUOTA_REJECTED, event.result());
        assertEquals("Test", event.providerName());
        assertEquals("test-model", event.model());
        assertEquals(0, event.inputLogLineCount());
        jenkins.assertLogContains("[explain-error] Provider call quota exceeded.", run);
    }

    @Test
    void consoleActionQuotaExceededEmitsQuotaRejectedEvent(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        config.setAiProvider(new TestProvider());
        config.setEnableQuota(true);
        config.setMaxProviderCallsPerWindow(0);
        config.setQuotaWindow(QuotaWindow.DAILY);

        FreeStyleProject project = jenkins.createFreeStyleProject("quota-console-rejected");
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        ConsoleExplainErrorAction action = new ConsoleExplainErrorAction(build);

        JSONObject response = invokeExplainConsoleError(action, null, null);

        assertEquals("warning", response.getString("status"));
        assertTrue(response.getString("message").contains("quota exceeded"));

        assertEquals(1, recorder.events.size());
        UsageEvent event = recorder.events.get(0);
        assertEquals(UsageEvent.EntryPoint.CONSOLE_ACTION, event.entryPoint());
        assertEquals(UsageEvent.Result.QUOTA_REJECTED, event.result());
        assertEquals("Test", event.providerName());
        assertEquals("test-model", event.model());
    }

    @Test
    void quotaDisabledAllowsUnlimitedCalls(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        config.setAiProvider(new TestProvider());
        config.setEnableQuota(false);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "quota-disabled-pipeline");
        job.setDefinition(new CpsFlowDefinition("node {\n  explainError()\n}", true));

        jenkins.buildAndAssertSuccess(job);
        jenkins.buildAndAssertSuccess(job);

        assertEquals(2, recorder.events.size());
        assertTrue(recorder.events.stream().allMatch(e -> e.result() == UsageEvent.Result.SUCCESS));
    }

    @Test
    void cacheHitDoesNotConsumeQuota(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        config.setAiProvider(new TestProvider());
        config.setEnableQuota(true);
        config.setMaxProviderCallsPerWindow(1);
        config.setQuotaWindow(QuotaWindow.HOURLY);

        FreeStyleProject project = jenkins.createFreeStyleProject("quota-cache-hit");
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        ConsoleExplainErrorAction action = new ConsoleExplainErrorAction(build);

        // First call: real provider call, consumes 1 quota slot
        JSONObject firstResponse = invokeExplainConsoleError(action, null, null);
        assertEquals("success", firstResponse.getString("status"));

        // Second call: cache hit, should NOT go through quota and should succeed
        JSONObject secondResponse = invokeExplainConsoleError(action, null, null);
        assertEquals("success", secondResponse.getString("status"));

        assertEquals(2, recorder.events.size());
        assertEquals(UsageEvent.Result.SUCCESS, recorder.events.get(0).result());
        assertEquals(UsageEvent.Result.CACHE_HIT, recorder.events.get(1).result());
    }

    private static class RecordingUsageRecorder implements UsageRecorder {
        private final List<UsageEvent> events = new ArrayList<>();

        @Override
        public void record(UsageEvent event) {
            events.add(event);
        }
    }

    private JSONObject invokeExplainConsoleError(ConsoleExplainErrorAction action, String forceNew, String maxLines)
            throws Exception {
        StaplerRequest2 request = mock(StaplerRequest2.class);
        when(request.getParameter("forceNew")).thenReturn(forceNew);
        when(request.getParameter("maxLines")).thenReturn(maxLines);

        StaplerResponse2 response = mock(StaplerResponse2.class);
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body, true));

        action.doExplainConsoleError(request, response);
        return JSONObject.fromObject(body.toString());
    }
}
