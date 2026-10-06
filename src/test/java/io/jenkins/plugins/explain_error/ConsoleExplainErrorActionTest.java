package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.explain_error.provider.FakeAIProvider;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.concurrent.ExecutionException;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import jenkins.model.Jenkins;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.SleepBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

@WithJenkins
class ConsoleExplainErrorActionTest {

    private ConsoleExplainErrorAction action;
    private FreeStyleBuild build;
    private JenkinsRule rule;
    private final FakeAIProvider provider = new FakeAIProvider();
    FreeStyleProject project;

    @BeforeEach
    void setUp(JenkinsRule jenkins) throws Exception {
        this.rule = jenkins;
        rule.jenkins.setCrumbIssuer(null);
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        config.setAiProvider(provider);
        project = jenkins.createFreeStyleProject("test");
        build = jenkins.buildAndAssertSuccess(project);
        action = new ConsoleExplainErrorAction(build);
    }

    @Test
    void testBasicFunctionality() {
        assertNotNull(action);
        assertEquals(build, action.getRun());
        assertNull(action.getIconFileName()); // Should be null for AJAX functionality
        assertNull(action.getDisplayName()); // Should be null for AJAX functionality
        assertEquals("console-explain-error", action.getUrlName());
    }

    @Test
    void testCreateCachedResponse() throws Exception {
        // Use reflection to access the private method
        Method method = ConsoleExplainErrorAction.class.getDeclaredMethod("createCachedResponse", String.class);
        method.setAccessible(true);

        String originalExplanation = "This is the original AI explanation.";
        String cachedResponse = (String) method.invoke(action, originalExplanation);

        assertNotNull(cachedResponse);
        assertTrue(cachedResponse.contains(originalExplanation));
        assertTrue(cachedResponse.contains("previously generated explanation"));
        assertTrue(cachedResponse.contains("Generate New"));
    }

    @Test
    void testCreateCachedResponseWithNullInput() throws Exception {
        String cachedResponse = action.createCachedResponse(null);

        assertNotNull(cachedResponse);
        assertTrue(cachedResponse.contains("null"));
        assertTrue(cachedResponse.contains("previously generated explanation"));
    }

    @Test
    void testCreateCachedResponseWithEmptyInput() throws Exception {
        String cachedResponse = action.createCachedResponse("");

        assertNotNull(cachedResponse);
        assertTrue(cachedResponse.contains("previously generated explanation"));
    }

    @Test
    void testCreateCachedResponseWithLongExplanation() throws Exception {
        StringBuilder longExplanation = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longExplanation.append("This is line ").append(i).append(" of a very long explanation.\n");
        }

        String cachedResponse = action.createCachedResponse(longExplanation.toString());

        assertNotNull(cachedResponse);
        assertTrue(cachedResponse.contains(longExplanation.toString()));
        assertTrue(cachedResponse.contains("previously generated explanation"));
    }

    @Test
    void testCreateCachedResponseWithSpecialCharacters() throws Exception {
        String specialExplanation = "Error with special chars: <>&\"'\nUnicode: ñáéíóú 中文 العربية";
        String cachedResponse = action.createCachedResponse(specialExplanation);

        assertNotNull(cachedResponse);
        assertTrue(cachedResponse.contains(specialExplanation));
        assertTrue(cachedResponse.contains("previously generated explanation"));
    }

    @Test
    void testGetRun() {
        assertEquals(build, action.getRun());
    }

    @Test
    void testExplainConsoleError() throws IOException {
        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            ErrorExplanationAction action = build.getAction(ErrorExplanationAction.class);
            assertNull(action);
            URL url = new URL(rule.jenkins.getRootUrl() + build.getUrl() + "console-explain-error/explainConsoleError");
            WebRequest request = new WebRequest(url, HttpMethod.POST);
            client.getPage(request);
            action = build.getAction(ErrorExplanationAction.class);
            assertNotNull(action);
            assertEquals("Summary: Request was successful\n", action.getExplanation());
        }
    }

    @Test
    void testExplainConsoleErrorProviderFailureReturnsJson() throws IOException {
        provider.setThrowError(true);
        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            URL url = new URL(rule.jenkins.getRootUrl() + build.getUrl() + "console-explain-error/explainConsoleError");
            WebRequest request = new WebRequest(url, HttpMethod.POST);

            Page page = client.getPage(request);
            String content = page.getWebResponse().getContentAsString();
            JSONObject responseJson = JSONObject.fromObject(content);

            assertEquals("error", responseJson.getString("status"));
            assertEquals("Test", responseJson.getString("providerName"));
            assertTrue(responseJson.getString("message").contains("API request failed: Request failed."));
        }
    }

    @Test
    void testExplainConsoleErrorSecondCall() throws IOException {
        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            URL url = new URL(rule.jenkins.getRootUrl() + build.getUrl() + "console-explain-error/explainConsoleError");
            WebRequest request = new WebRequest(url, HttpMethod.POST);
            client.getPage(request);
            ErrorExplanationAction action = build.getAction(ErrorExplanationAction.class);
            assertNotNull(action);
            assertEquals("Summary: Request was successful\n", action.getExplanation());
            provider.setAnswerMessage("Second call");
            client.getPage(request);
            assertEquals(1, provider.getCallCount());
            action = build.getAction(ErrorExplanationAction.class);
            assertEquals("Summary: Request was successful\n", action.getExplanation());
        }
    }
    @Test
    void testExplainConsoleErrorSecondCallForceNew() throws IOException {
        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            URL url = new URL(rule.jenkins.getRootUrl() + build.getUrl() + "console-explain-error/explainConsoleError");
            WebRequest request = new WebRequest(url, HttpMethod.POST);
            client.getPage(request);
            ErrorExplanationAction action = build.getAction(ErrorExplanationAction.class);
            assertNotNull(action);
            assertEquals("Summary: Request was successful\n", action.getExplanation());
            provider.setAnswerMessage("Second call");
            request.setRequestParameters(java.util.Collections.singletonList(
                new org.htmlunit.util.NameValuePair("forceNew", "true")
            ));
            client.getPage(request);
            assertEquals(2, provider.getCallCount());
            action = build.getAction(ErrorExplanationAction.class);
            assertEquals("Summary: Second call\n", action.getExplanation());
        }
    }

    @Test
    void testCheckBuildStatus() throws IOException, ExecutionException, InterruptedException {
        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            URL url = new URL(rule.jenkins.getRootUrl() + build.getUrl() + "console-explain-error/checkBuildStatus");
            WebRequest request = new WebRequest(url, HttpMethod.POST);
            Page page = client.getPage(request);
            String content = page.getWebResponse().getContentAsString();
            JSONObject responseJson = JSONObject.fromObject(content);
            assertEquals(0, responseJson.getInt("buildingStatus"));

            // Test build is running
            project.getBuildersList().add(new SleepBuilder(2000));
            build = project.scheduleBuild2(0).waitForStart();
            url = new URL(rule.jenkins.getRootUrl() + build.getUrl() + "console-explain-error/checkBuildStatus");
            request = new WebRequest(url, HttpMethod.POST);
            page = client.getPage(request);
            content = page.getWebResponse().getContentAsString();
            responseJson = JSONObject.fromObject(content);
            assertEquals(1, responseJson.getInt("buildingStatus"));

            // Test build failed
            project.getBuildersList().clear();
            project.getBuildersList().add(new FailureBuilder());
            build = project.scheduleBuild2(0).get();
            url = new URL(rule.jenkins.getRootUrl() + build.getUrl() + "console-explain-error/checkBuildStatus");
            request = new WebRequest(url, HttpMethod.POST);
            page = client.getPage(request);
            content = page.getWebResponse().getContentAsString();
            responseJson = JSONObject.fromObject(content);
            assertEquals(2, responseJson.getInt("buildingStatus"));
        }
    }

    @Test
    void actionFollowsTheRunAndReportsEndpointErrors() throws Exception {
        FreeStyleBuild other = rule.buildAndAssertSuccess(project);
        action.onAttached(other);
        assertSame(other, action.getRun());
        action.onLoad(build);
        assertSame(build, action.getRun());

        // An unparsable maxLines parameter falls back to the default number of lines.
        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            URL url = new URL(rule.jenkins.getRootUrl() + build.getUrl()
                    + "console-explain-error/explainConsoleError?maxLines=lots");
            Page page = client.getPage(new WebRequest(url, HttpMethod.POST));
            assertEquals("success", JSONObject.fromObject(page.getWebResponse().getContentAsString())
                    .getString("status"));
        }

        // Callers without Job/Read get an error response instead of an explanation.
        rule.jenkins.setSecurityRealm(rule.createDummySecurityRealm());
        rule.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ).everywhere().to("reader"));
        StaplerRequest2 request = mock(StaplerRequest2.class);
        StaplerResponse2 response = mock(StaplerResponse2.class);
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        try (ACLContext ignored = ACL.as2(User.getById("reader", true).impersonate2())) {
            action.doExplainConsoleError(request, response);
            action.doCheckBuildStatus(request, response);
        }

        JSONObject error = JSONObject.fromObject(body.toString());
        assertEquals("error", error.getString("status"));
        assertTrue(error.getString("message").contains("Job/Read"), error.toString());
        verify(response).setStatus(500);
    }
}
