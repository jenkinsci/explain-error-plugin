package io.jenkins.plugins.explain_error.autofix;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Item;
import hudson.model.Result;
import hudson.util.Secret;
import io.jenkins.plugins.explain_error.GlobalConfigurationImpl;
import io.jenkins.plugins.explain_error.provider.FakeAIProvider;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.core.Authentication;

/**
 * What a whole {@code explainError(autoFix: true)} run writes to the build log about its outcome.
 */
@WithJenkins
class AutoFixLogTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REPO_API = "/api/v3/repos/acme/app";
    private static final String BRANCH = "/git/refs?/heads/fix/jenkins-ai-[0-9a-z-]+";
    private static final String PULL_REQUEST = "https://github.example.com/acme/app/pull/7";
    private static final String POM = "<project>\n  <version>1.0</version>\n</project>\n";
    private static final String POM_DIFF = "--- a/pom.xml\n+++ b/pom.xml\n@@ -1,3 +1,3 @@\n <project>\n"
            + "-  <version>1.0</version>\n+  <version>1.1</version>\n </project>";

    private WireMockServer server;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    /** A provider whose fix suggestion is the given diff for {@code pom.xml}. */
    private static void aiSuggests(String diff) throws Exception {
        String suggestion = MAPPER.writeValueAsString(new FixSuggestion(true, "Bump the version", "high", "dependency",
                List.of(new FixSuggestion.FileChange("pom.xml", "modify", diff, "Bump version")), "bump the version"));
        GlobalConfigurationImpl.get().setAiProvider(new FakeAIProvider() {
            @Override
            public FixAssistant createFixAssistant(@CheckForNull Item item,
                                                   @CheckForNull Authentication authentication) {
                return errorLogs -> suggestion;
            }
        });
    }

    private WorkflowRun runAutoFix(JenkinsRule jenkins) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(new StringCredentialsImpl(
                CredentialsScope.GLOBAL, "scm-token", "", Secret.fromString("not-a-real-token")));
        WorkflowJob job = jenkins.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition("node { explainError(autoFix: true, autoFixCredentialsId: 'scm-token', "
                + "autoFixRemoteUrl: 'https://github.example.com/acme/app.git', autoFixScmType: 'github', "
                + "autoFixGithubEnterpriseUrl: 'http://localhost:" + server.port() + "', "
                + "autoFixAllowedPaths: 'pom.xml') }", true));
        return jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
    }

    private static int occurrences(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    @Test
    void aCreatedPullRequestIsLoggedOnce(JenkinsRule jenkins) throws Exception {
        aiSuggests(POM_DIFF);
        server.stubFor(get(urlEqualTo(REPO_API)).willReturn(okJson(
                "{\"default_branch\":\"main\",\"permissions\":{\"push\":true}}")));
        server.stubFor(get(urlEqualTo(REPO_API + "/git/ref/heads/main"))
                .willReturn(okJson("{\"object\":{\"sha\":\"base-sha\"}}")));
        server.stubFor(post(urlEqualTo(REPO_API + "/git/refs")).willReturn(aResponse().withStatus(201).withBody("{}")));
        server.stubFor(get(urlEqualTo(REPO_API + "/contents/pom.xml?ref=main")).willReturn(okJson(
                MAPPER.createObjectNode().put("content",
                        Base64.getMimeEncoder().encodeToString(POM.getBytes(StandardCharsets.UTF_8))).toString())));
        server.stubFor(get(urlPathMatching(REPO_API + BRANCH))
                .willReturn(okJson("{\"object\":{\"sha\":\"branch-sha\"}}")));
        server.stubFor(get(urlEqualTo(REPO_API + "/git/commits/branch-sha"))
                .willReturn(okJson("{\"tree\":{\"sha\":\"tree-sha\"}}")));
        for (String object : List.of("blobs", "trees", "commits")) {
            server.stubFor(post(urlEqualTo(REPO_API + "/git/" + object))
                    .willReturn(aResponse().withStatus(201).withBody("{\"sha\":\"" + object + "-sha\"}")));
        }
        server.stubFor(patch(urlPathMatching(REPO_API + BRANCH)).willReturn(okJson("{}")));
        server.stubFor(post(urlEqualTo(REPO_API + "/pulls")).willReturn(aResponse().withStatus(201)
                .withBody("{\"number\":7,\"html_url\":\"" + PULL_REQUEST + "\"}")));

        String log = JenkinsRule.getLog(runAutoFix(jenkins));

        assertEquals(1, occurrences(log, PULL_REQUEST), log);
        assertEquals(1, occurrences(log, "[AutoFix] Pull request created: " + PULL_REQUEST), log);
    }

    @Test
    void aFailureIsLoggedOnceWithItsReason(JenkinsRule jenkins) throws Exception {
        aiSuggests("this is not a diff");

        String log = JenkinsRule.getLog(runAutoFix(jenkins));

        assertEquals(1, occurrences(log, "Invalid diff for pom.xml"), log);
        assertEquals(1, occurrences(log, "[AutoFix] Status: FAILED - Invalid diff for pom.xml: "), log);
        assertEquals(0, server.getAllServeEvents().size(), "an invalid diff must not reach the SCM");
    }
}
