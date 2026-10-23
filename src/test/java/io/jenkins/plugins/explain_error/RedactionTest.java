package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import hudson.util.FormValidation;
import io.jenkins.plugins.explain_error.provider.FakeAIProvider;
import java.net.URL;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Secrets are masked in everything sent to the AI provider, wherever the explanation is asked for. */
@WithJenkins
@DisabledOnOs(OS.WINDOWS)
class RedactionTest {

    private static final String AWS_KEY_ID = LogRedactorTest.AWS_KEY_ID;
    private static final String GITHUB_TOKEN = LogRedactorTest.GITHUB_TOKEN;
    private static final String LEAKY_STEP = "sh '''set +x\necho \"DB_PASSWORD=hunter2hunter2\"\necho \"key "
            + AWS_KEY_ID + "\"\necho \"BUILD FAILED: cannot connect to db\"\nexit 1'''";

    private final FakeAIProvider provider = new FakeAIProvider();
    private JenkinsRule jenkins;

    @BeforeEach
    void setUp(JenkinsRule jenkins) {
        this.jenkins = jenkins;
        GlobalConfigurationImpl.get().setAiProvider(provider);
    }

    @Test
    void theStepSendsTheLogWithItsSecretsMasked() throws Exception {
        WorkflowRun run = explain("step", "explainError()", LEAKY_STEP);

        String sent = provider.getLastErrorLogs();
        assertFalse(sent.contains("hunter2hunter2") || sent.contains(AWS_KEY_ID), sent);
        assertTrue(sent.contains("DB_PASSWORD=[REDACTED]") && sent.contains("BUILD FAILED"), sent);
        jenkins.assertLogContains("Masked 2 secrets in what is sent to the AI provider.", run);
        ErrorExplanationAction action = run.getAction(ErrorExplanationAction.class);
        assertEquals(2, action.getRedactedCount());
    }

    @Test
    void customContextIsMaskedToo() throws Exception {
        explain("context", "explainError(customContext: 'Deploy with api_key=ctx-secret-123')", LEAKY_STEP);

        String context = provider.getLastCustomContext();
        assertTrue(context.contains("Deploy with api_key=[REDACTED]"), context);
        assertFalse(context.contains("ctx-secret-123"), context);
    }

    @Test
    void workspaceFilesSentAsContextAreMasked() throws Exception {
        explain("workspace", "explainError(includeWorkspaceContext: true, workspaceContextPaths: 'pom.xml')",
                "writeFile file: 'pom.xml', text: '<password>pom-secret-1</password><url>https://ci:"
                        + "pom-secret-2@repo.example.com</url>'\n" + LEAKY_STEP);

        String context = provider.getLastCustomContext();
        assertFalse(context.contains("pom-secret-2"), context);
        assertTrue(context.contains("https://ci:[REDACTED]@repo.example.com"), context);
    }

    @Test
    void withMaskingOffTheLogIsSentAsItIs() throws Exception {
        GlobalConfigurationImpl.get().setRedactSecrets(false);

        WorkflowRun run = explain("off", "explainError()", LEAKY_STEP);

        assertTrue(provider.getLastErrorLogs().contains(AWS_KEY_ID), provider.getLastErrorLogs());
        jenkins.assertLogNotContains("Masked", run);
        assertEquals(0, run.getAction(ErrorExplanationAction.class).getRedactedCount());
    }

    @Test
    void thePatternsOfTheAdministratorApplyEvenWithTheBuiltInRulesOff() throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        config.setRedactSecrets(false);
        config.setRedactionPatterns("# the database\n(?i)cannot connect to (?<secret>db)\n");

        explain("own", "explainError()", LEAKY_STEP);

        String sent = provider.getLastErrorLogs();
        assertTrue(sent.contains("cannot connect to [REDACTED]"), sent);
        assertTrue(sent.contains(AWS_KEY_ID), "the built-in rules are off: " + sent);
    }

    @Test
    void nothingIsSentWhenSomethingWasMaskedAndTheConfigurationSaysSo() throws Exception {
        GlobalConfigurationImpl.get().setBlockWhenRedacted(true);

        WorkflowRun run = explain("block", "explainError()", LEAKY_STEP);

        jenkins.assertLogContains("RETURNED=false", run);
        jenkins.assertLogContains(ErrorExplainer.BLOCKED, run);
        assertEquals(0, provider.getCallCount());
        assertNull(run.getAction(ErrorExplanationAction.class));
    }

    @Test
    void aLogWithNothingToMaskIsSentEvenWhenBlocking() throws Exception {
        GlobalConfigurationImpl.get().setBlockWhenRedacted(true);

        WorkflowRun run = explain("clean", "explainError()", "sh 'echo plain failure; exit 1'");

        jenkins.assertLogContains("RETURNED=true", run);
        assertEquals(1, provider.getCallCount());
    }

    @Test
    void theConsoleButtonSendsTheLogMasked() throws Exception {
        jenkins.jenkins.setCrumbIssuer(null);
        FreeStyleBuild build = leakyFreestyleBuild();

        JSONObject response = postExplain(build);

        assertEquals("success", response.getString("status"), response.toString());
        String sent = provider.getLastErrorLogs();
        assertFalse(sent.contains(GITHUB_TOKEN), sent);
        assertTrue(sent.contains("https://x-access-token:[REDACTED]@github.com/o/r"), sent);
        assertEquals(1, build.getAction(ErrorExplanationAction.class).getRedactedCount());
    }

    @Test
    void theConsoleButtonSaysWhyNothingWasSent() throws Exception {
        jenkins.jenkins.setCrumbIssuer(null);
        GlobalConfigurationImpl.get().setBlockWhenRedacted(true);
        FreeStyleBuild build = leakyFreestyleBuild();

        JSONObject response = postExplain(build);

        assertEquals("warning", response.getString("status"), response.toString());
        assertTrue(response.getString("message").contains(ErrorExplainer.BLOCKED), response.toString());
        assertEquals(0, provider.getCallCount());
    }

    @Test
    void thePreviewShowsWhatTheButtonWouldSendAndSendsNothing() throws Exception {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ).everywhere().to("reader")
                .grant(Jenkins.READ).everywhere().to("outsider"));
        FreeStyleBuild build = leakyFreestyleBuild();

        try (JenkinsRule.WebClient client = jenkins.createWebClient().login("reader")) {
            Page page = client.goTo(build.getUrl() + "console-explain-error/preview", "text/plain");
            String preview = page.getWebResponse().getContentAsString();
            assertTrue(preview.contains("Masked 1 secret in what is sent"), preview);
            assertTrue(preview.contains("https://x-access-token:[REDACTED]@github.com/o/r"), preview);
            assertFalse(preview.contains(GITHUB_TOKEN), preview);
        }
        try (JenkinsRule.WebClient client = jenkins.createWebClient().login("outsider")) {
            client.setThrowExceptionOnFailingStatusCode(false);
            int status = client.goTo(build.getUrl() + "console-explain-error/preview", null)
                    .getWebResponse().getStatusCode();
            assertTrue(status == 403 || status == 404, "no Item/Read, no preview: " + status);
        }
        assertEquals(0, provider.getCallCount());
    }

    @Test
    void anInvalidPatternIsReportedOnTheForm() {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();

        assertEquals(FormValidation.Kind.OK, config.doCheckRedactionPatterns("# hosts\n\\bbuild\\d+\\b").kind);
        FormValidation invalid = config.doCheckRedactionPatterns("ok\n([");
        assertEquals(FormValidation.Kind.ERROR, invalid.kind);
        assertTrue(invalid.getMessage().contains("Pattern 2"), invalid.getMessage());
    }

    @Test
    void theSettingsSurviveTheConfigurationForm() throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        assertTrue(config.isRedactSecrets(), "masking is on by default");
        config.setRedactSecrets(false);
        config.setRedactionPatterns("\\bbuild\\d+\\b");
        config.setBlockWhenRedacted(true);

        jenkins.configRoundtrip();

        assertFalse(config.isRedactSecrets());
        assertEquals("\\bbuild\\d+\\b", config.getRedactionPatterns());
        assertTrue(config.isBlockWhenRedacted());
    }

    private FreeStyleBuild leakyFreestyleBuild() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(hudson.model.AbstractBuild<?, ?> build, hudson.Launcher launcher,
                                   hudson.model.BuildListener listener) {
                listener.getLogger().println("git clone https://x-access-token:" + GITHUB_TOKEN
                        + "@github.com/o/r");
                listener.getLogger().println("fatal: repository not found");
                return false;
            }
        });
        return jenkins.buildAndAssertStatus(Result.FAILURE, project);
    }

    private JSONObject postExplain(FreeStyleBuild build) throws Exception {
        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            WebRequest request = new WebRequest(new URL(jenkins.getURL(), build.getUrl()
                    + "console-explain-error/explainConsoleError"), HttpMethod.POST);
            return JSONObject.fromObject(client.getPage(request).getWebResponse().getContentAsString());
        }
    }

    private WorkflowRun explain(String name, String step, String failure) throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition("node { try { " + failure + " } catch (e) { def r = " + step
                + "; echo \"RETURNED=${r != null}\" } }", true));
        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        assertNotNull(run);
        return run;
    }
}
