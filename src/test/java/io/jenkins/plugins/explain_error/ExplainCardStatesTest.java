package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.exception.AuthenticationException;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import io.jenkins.plugins.explain_error.provider.FakeAIProvider;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlButton;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.core.Authentication;

/**
 * What the console card and its button show while an explanation is requested, when the
 * request fails, and when the build already has one.
 */
@WithJenkins
class ExplainCardStatesTest {

    private static final String REJECTED_KEY = "{\"error\": {\"message\": \"Incorrect API key provided\"}}";

    private JenkinsRule jenkins;
    private GlobalConfigurationImpl config;
    private FakeAIProvider provider;

    @BeforeEach
    void setUp(JenkinsRule jenkins) {
        this.jenkins = jenkins;
        config = GlobalConfigurationImpl.get();
        provider = new FakeAIProvider();
        config.setEnableExplanation(true);
        config.setAiProvider(provider);
    }

    private FreeStyleBuild failedBuild() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new FailureBuilder());
        return jenkins.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0));
    }

    private static ErrorExplanationAction explanation() {
        ErrorExplanationAction action = new ErrorExplanationAction(
                "plain text", "", "line 1\nline 2", "DeepSeek", "deepseek-v4-flash", 2);
        action.setStructuredData(new JenkinsLogAnalysis(
                "The lock file is out of sync.", List.of("Run `npm install`."), null, "npm error code EUSAGE",
                "Lock file is out of sync"));
        return action;
    }

    private JSONObject post(JenkinsRule.WebClient client, FreeStyleBuild build, String endpoint) throws Exception {
        URL url = new URL(jenkins.jenkins.getRootUrl() + build.getUrl() + "console-explain-error/" + endpoint);
        return JSONObject.fromObject(
                client.getPage(new WebRequest(url, HttpMethod.POST)).getWebResponse().getContentAsString());
    }

    private static boolean hidden(DomElement element) {
        return element.getAttribute("class").contains("jenkins-hidden");
    }

    @Test
    void buildStatusSaysWhetherTheBuildHasAnExplanation() throws Exception {
        jenkins.jenkins.setCrumbIssuer(null);
        FreeStyleBuild build = failedBuild();
        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            JSONObject status = post(client, build, "checkBuildStatus");
            assertEquals(2, status.getInt("buildingStatus"));
            assertEquals("none", status.getString("explanation"));

            PendingExplanations.add(build);
            try {
                assertEquals("pending", post(client, build, "checkBuildStatus").getString("explanation"));
            } finally {
                PendingExplanations.remove(build);
            }

            build.addAction(explanation());
            assertEquals("ready", post(client, build, "checkBuildStatus").getString("explanation"));
        }
    }

    @Test
    void aRequestThatNeverReturnsStopsCountingAsPending() throws Exception {
        FreeStyleBuild build = failedBuild();
        PendingExplanations.add(build, System.currentTimeMillis() - PendingExplanations.MAX_AGE_MILLIS - 1);

        assertFalse(PendingExplanations.contains(build));
    }

    @Test
    void automaticExplanationIsPendingUntilTheProviderAnswers() throws Exception {
        CountDownLatch asked = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);
        config.setAiProvider(new FakeAIProvider() {
            @Override
            public Assistant createAssistant(@CheckForNull Item item, @CheckForNull Authentication authentication,
                                             @CheckForNull Double temperature) {
                Assistant assistant = super.createAssistant(item, authentication, temperature);
                return (errorLogs, language, customContext) -> {
                    asked.countDown();
                    try {
                        answer.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return assistant.analyzeLogs(errorLogs, language, customContext);
                };
            }
        });
        config.setEnableAutoExplainOnFailure(true);
        try {
            FreeStyleBuild build = failedBuild();
            assertTrue(asked.await(30, TimeUnit.SECONDS), "the listener should have asked the provider");

            assertTrue(PendingExplanations.contains(build));
            HtmlPage page = jenkins.createWebClient().getPage(build);
            DomElement pending = page.getElementById("explain-error-pending");
            assertNotNull(pending, "the build page should say an explanation is on its way");
            assertTrue(pending.getAttribute("data-status-url")
                    .endsWith(build.getUrl() + "console-explain-error/checkBuildStatus"));

            answer.countDown();
            for (int i = 0; i < 100 && PendingExplanations.contains(build); i++) {
                Thread.sleep(100);
            }
            assertFalse(PendingExplanations.contains(build));
            assertNotNull(build.getAction(ErrorExplanationAction.class));
            assertNull(jenkins.createWebClient().getPage(build).getElementById("explain-error-pending"));
        } finally {
            answer.countDown();
            config.setEnableAutoExplainOnFailure(false);
        }
    }

    @Test
    void aFailureTheServerRecognisesComesWithPlainWordsAndTheDetail() throws Exception {
        jenkins.jenkins.setCrumbIssuer(null);
        config.setAiProvider(new FakeAIProvider() {
            @Override
            public Assistant createAssistant(@CheckForNull Item item, @CheckForNull Authentication authentication,
                                             @CheckForNull Double temperature) {
                return (errorLogs, language, customContext) -> {
                    throw new AuthenticationException(REJECTED_KEY);
                };
            }
        });
        FreeStyleBuild build = failedBuild();

        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            JSONObject response = post(client, build, "explainConsoleError");

            assertEquals("error", response.getString("status"));
            assertEquals("The AI provider rejected the credentials. Check the API key in the plugin configuration.",
                    response.getString("reason"));
            assertEquals("API request failed: " + REJECTED_KEY, response.getString("message"));
        }
    }

    @Test
    void aFailureTheServerDoesNotRecogniseHasNoReason() throws Exception {
        jenkins.jenkins.setCrumbIssuer(null);
        provider.setThrowError(true);
        FreeStyleBuild build = failedBuild();

        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            JSONObject response = post(client, build, "explainConsoleError");

            assertEquals("error", response.getString("status"));
            assertFalse(response.has("reason"));
            assertEquals("API request failed: Request failed.", response.getString("message"));
        }
    }

    @Test
    void aFailedRequestStaysInTheCardAndCanBeTriedAgain() throws Exception {
        provider.setThrowError(true);
        FreeStyleBuild build = failedBuild();

        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            HtmlPage page = client.getPage(build, "console");
            client.waitForBackgroundJavaScript(10_000);
            HtmlButton button = page.querySelector(".explain-error-btn");
            assertNotNull(button, "the console page should offer to explain the failure");
            assertEquals("Explain Error", button.getTextContent());
            DomElement card = page.getElementById("explain-error-container");
            DomElement failure = page.getElementById("explain-error-failure");
            assertTrue(hidden(card));
            assertTrue(hidden(failure));

            button.click();
            client.waitForBackgroundJavaScript(10_000);

            assertFalse(hidden(card), "the card should stay open on a failure");
            assertFalse(hidden(failure));
            assertEquals("API request failed: Request failed.",
                    page.getElementById("explain-error-failure-headline").getTextContent());
            assertTrue(hidden(page.getElementById("explain-error-failure-details")),
                    "there is no detail beyond the message itself");
            assertTrue(hidden(page.getElementById("explain-error-spinner")));
            assertEquals("Explain Error", button.getTextContent());

            provider.setThrowError(false);
            HtmlButton retry = page.querySelector("#explain-error-retry");
            assertEquals("Try again", retry.getTextContent().trim());
            retry.click();
            client.waitForBackgroundJavaScript(10_000);

            assertTrue(hidden(failure), "the failure should go once a request succeeds");
            DomElement content = page.getElementById("explain-error-content");
            assertFalse(hidden(content));
            assertTrue(content.getTextContent().contains("Request was successful"), content.getTextContent());
            assertEquals("Show explanation", button.getTextContent());
        }
    }

    @Test
    void aRecognisedFailureLeadsWithPlainWordsAndKeepsWhatTheProviderSent() throws Exception {
        config.setAiProvider(new FakeAIProvider() {
            @Override
            public Assistant createAssistant(@CheckForNull Item item, @CheckForNull Authentication authentication,
                                             @CheckForNull Double temperature) {
                return (errorLogs, language, customContext) -> {
                    throw new AuthenticationException(REJECTED_KEY);
                };
            }
        });
        FreeStyleBuild build = failedBuild();

        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            HtmlPage page = client.getPage(build, "console");
            client.waitForBackgroundJavaScript(10_000);
            page.<HtmlButton>querySelector(".explain-error-btn").click();
            client.waitForBackgroundJavaScript(10_000);

            assertEquals("The AI provider rejected the credentials. Check the API key in the plugin configuration.",
                    page.getElementById("explain-error-failure-headline").getTextContent());
            assertFalse(hidden(page.getElementById("explain-error-failure-details")));
            assertEquals("API request failed: " + REJECTED_KEY,
                    page.getElementById("explain-error-failure-detail").getTextContent());
        }
    }

    @Test
    void aBuildThatHasAnExplanationOffersToShowItAndSaysWhenItWasGenerated() throws Exception {
        FreeStyleBuild build = failedBuild();
        ErrorExplanationAction action = explanation();
        build.addAction(action);
        build.save();

        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            HtmlPage page = client.getPage(build, "console");
            client.waitForBackgroundJavaScript(10_000);
            HtmlButton button = page.querySelector(".explain-error-btn");
            assertEquals("Show explanation", button.getTextContent());

            button.click();
            client.waitForBackgroundJavaScript(10_000);

            assertFalse(hidden(page.getElementById("explain-error-container")));
            assertEquals(0, provider.getCallCount(), "showing an explanation must not request a new one");
            DomNode generated = page.querySelector("#explain-error-content .eep-generated");
            assertNotNull(generated, "the card should say when the explanation was generated");
            assertEquals("Generated on " + action.getFormattedTimestamp(), generated.getTextContent().trim());
        }
    }

    @Test
    void theExplanationPageKeepsItsOwnTimeAndDoesNotRepeatIt() throws Exception {
        FreeStyleBuild build = failedBuild();
        build.addAction(explanation());
        build.save();

        HtmlPage page = jenkins.createWebClient().getPage(build, "error-explanation");

        assertTrue(page.asNormalizedText().contains("Generated on: "), page.asNormalizedText());
        assertNull(page.querySelector(".eep-generated"));
    }

    @Test
    void aBuildWhoseExplanationIsOnItsWayDoesNotOfferToRequestAnother() throws Exception {
        FreeStyleBuild build = failedBuild();
        PendingExplanations.add(build);
        try (JenkinsRule.WebClient client = jenkins.createWebClient()) {
            HtmlPage page = client.getPage(build, "console");
            client.waitForBackgroundJavaScript(2_000);
            HtmlButton button = page.querySelector(".explain-error-btn");

            assertEquals("Explaining…", button.getTextContent());
            assertTrue(button.isDisabled());

            build.addAction(explanation());
            PendingExplanations.remove(build);
            client.waitForBackgroundJavaScript(10_000);

            assertEquals("Show explanation", button.getTextContent());
            assertFalse(button.isDisabled());
        } finally {
            PendingExplanations.remove(build);
        }
    }
}
