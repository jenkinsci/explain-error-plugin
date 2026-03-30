package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.util.Secret;
import io.jenkins.plugins.explain_error.provider.TestProvider;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.net.URL;

@WithJenkins
class ConsolePageDecoratorTest {

    private ConsolePageDecorator decorator;
    private GlobalConfigurationImpl config;
    private TestProvider provider;
    private JenkinsRule rule;


    @BeforeEach
    void setUp(JenkinsRule jenkins) {
        rule = jenkins;
        rule.jenkins.setCrumbIssuer(null);
        decorator = new ConsolePageDecorator();
        config = GlobalConfigurationImpl.get();

        // Reset to default state
        provider = new TestProvider();
        config.setEnableExplanation(true);
        config.setAiProvider(provider);
    }


    @Test
    void testIsExplainErrorEnabledWithValidConfig() {
        // With valid configuration, should return true
        assertTrue(decorator.isExplainErrorEnabled());
    }

    @Test
    void testIsExplainErrorEnabledWhenDisabled() {
        config.setEnableExplanation(false);

        // Should return false when explanation is disabled
        assertFalse(decorator.isExplainErrorEnabled());
    }

    @Test
    void testIsExplainErrorEnabledWhenAiProviderIsNull() {
        // Should return false (not throw NPE) when aiProvider is null
        config.setAiProvider(null);
        assertFalse(decorator.isExplainErrorEnabled());
    }

    @Test
    void testIsExplainErrorEnabledWithNullApiKey() {
        provider.setApiKey(null);

        // Should return false when API key is null
        assertFalse(decorator.isExplainErrorEnabled());
    }

    @Test
    void testIsExplainErrorEnabledWithEmptyApiKey() {
        provider.setApiKey(Secret.fromString(""));

        // Should return false when API key is empty
        assertFalse(decorator.isExplainErrorEnabled());
    }

    @Test
    void testIsExplainErrorEnabledWithBlankApiKey() {
        provider.setApiKey(Secret.fromString("   "));

        // Should return false when API key is blank
        assertFalse(decorator.isExplainErrorEnabled());
    }

    @Test
    void testExtensionAnnotation() {
        // Test that the decorator is properly annotated as an Extension
        assertTrue(decorator.getClass().isAnnotationPresent(hudson.Extension.class));
    }

    @Test
    void testInheritance() {
        // Test that the decorator extends PageDecorator
        assertTrue(decorator instanceof hudson.model.PageDecorator);
    }

    @Test
    void testMultipleConditionsDisabled() {
        // Test when multiple conditions are not met
        config.setEnableExplanation(false);
        provider.setApiKey(null);

        assertFalse(decorator.isExplainErrorEnabled());
    }

    @Test
    void testConfigurationChanges() {
        // Test that the decorator responds to configuration changes
        assertTrue(decorator.isExplainErrorEnabled());

        config.setEnableExplanation(false);
        assertFalse(decorator.isExplainErrorEnabled());

        config.setEnableExplanation(true);
        assertTrue(decorator.isExplainErrorEnabled());

        provider.setApiKey(null);
        assertFalse(decorator.isExplainErrorEnabled());
    }

    @Test
    void testEdgeCaseApiKey() {
        // Test with various edge cases for API key
        provider.setApiKey(Secret.fromString("a")); // Very short key
        assertTrue(decorator.isExplainErrorEnabled()); // Should still work, validation is elsewhere

        provider.setApiKey(Secret.fromString("\t\n\r ")); // Whitespace only
        assertFalse(decorator.isExplainErrorEnabled());
    }

    @Test
    void testConsistentBehavior() {
        // Test that multiple calls return consistent results
        boolean result1 = decorator.isExplainErrorEnabled();
        boolean result2 = decorator.isExplainErrorEnabled();
        boolean result3 = decorator.isExplainErrorEnabled();

        assertEquals(result1, result2);
        assertEquals(result2, result3);
    }

    @Test
    void testValidMinimalConfiguration() {
        // Test with minimal valid configuration
        config.setEnableExplanation(true);
        config.setApiKey(Secret.fromString("k"));

        assertTrue(decorator.isExplainErrorEnabled());
    }

    @Test
    void testContainerIsInjectedWhenEnabled() throws Exception {
        FreeStyleProject project = rule.createFreeStyleProject("test");
        FreeStyleBuild build = rule.buildAndAssertSuccess(project);

        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            URL url = url = new URL(rule.jenkins.getRootUrl() + build.getUrl() + "console");
            WebRequest request = new WebRequest(url, HttpMethod.POST);
            Page page = client.getPage(request);
            String content = page.getWebResponse().getContentAsString();
            assertTrue(content.contains("explain-error-container"));

            // Test consoleFull Url
            url = new URL(rule.jenkins.getRootUrl() + build.getUrl() + "consoleFull");
            request = new WebRequest(url, HttpMethod.POST);
            page = client.getPage(request);
            content = page.getWebResponse().getContentAsString();
            assertTrue(content.contains("explain-error-container"));
        }
    }

    @Test
    void testContainerIsInjectedWithExistingExplanationWhenDisabled() throws Exception {
        FreeStyleProject project = rule.createFreeStyleProject("test");
        FreeStyleBuild build = rule.buildAndAssertSuccess(project);
        build.addAction(new ErrorExplanationAction("This is a test explanation of the error", "",
            "ERROR: Build failed\nFinished: FAILURE", "Ollama" ));
        build.save();
        config.setEnableExplanation(false);

        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            URL url = url = new URL(rule.jenkins.getRootUrl() + build.getUrl() + "console");
            WebRequest request = new WebRequest(url, HttpMethod.POST);
            Page page = client.getPage(request);
            String content = page.getWebResponse().getContentAsString();

            // Parse content as HTML and get element by id "explain-error-content"
            Document doc = Jsoup.parse(content);
            Element explainContent = doc.getElementById("explain-error-content");
            assertNotNull(explainContent, "Element with id 'explain-error-content' should exist");
            assertTrue(explainContent.text().contains("This is a test explanation of the error"));

        }
    }

    @Test
    void testContainerIsNotInjectedWhenDisabled() throws Exception {
        config.setEnableExplanation(false);
        FreeStyleProject project = rule.createFreeStyleProject("test");
        FreeStyleBuild build = rule.buildAndAssertSuccess(project);

        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            URL url = url = new URL(rule.jenkins.getRootUrl() + build.getUrl() + "console");
            WebRequest request = new WebRequest(url, HttpMethod.POST);
            Page page = client.getPage(request);
            String content = page.getWebResponse().getContentAsString();
            assertFalse(content.contains("explain-error-container"));

            // Test consoleFull Url
            url = new URL(rule.jenkins.getRootUrl() + build.getUrl() + "consoleFull");
            request = new WebRequest(url, HttpMethod.POST);
            page = client.getPage(request);
            content = page.getWebResponse().getContentAsString();
            assertFalse(content.contains("explain-error-container"));
        }
    }

    @Test
    void testContainerIsNotInjectedOnOtherPages() throws Exception {
        FreeStyleProject project = rule.createFreeStyleProject("test");
        FreeStyleBuild build = rule.buildAndAssertSuccess(project);

        try (JenkinsRule.WebClient client = rule.createWebClient()) {
            URL url = url = new URL(rule.jenkins.getRootUrl() + build.getUrl());
            WebRequest request = new WebRequest(url, HttpMethod.POST);
            Page page = client.getPage(request);
            String content = page.getWebResponse().getContentAsString();
            assertFalse(content.contains("explain-error-container"));

            // Test project Url
            url = new URL(rule.jenkins.getRootUrl() + project.getUrl());
            request = new WebRequest(url, HttpMethod.POST);
            page = client.getPage(request);
            content = page.getWebResponse().getContentAsString();
            assertFalse(content.contains("explain-error-container"));

            // Test Jenkins root Url
            url = new URL(rule.jenkins.getRootUrl());
            request = new WebRequest(url, HttpMethod.POST);
            page = client.getPage(request);
            content = page.getWebResponse().getContentAsString();
            assertFalse(content.contains("explain-error-container"));
        }
    }
}
