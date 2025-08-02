package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.TaskListener;
import hudson.util.Secret;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ErrorExplainerTest {

    @Test
    void testErrorExplainerBasicFunctionality(JenkinsRule jenkins) throws Exception {
        ErrorExplainer errorExplainer = new ErrorExplainer();
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        
        // Test when plugin is disabled
        config.setEnableExplanation(false);
        
        FreeStyleProject project = jenkins.createFreeStyleProject();
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        TaskListener listener = jenkins.createTaskListener();
        
        // Should not throw exception when disabled
        assertDoesNotThrow(() -> {
            errorExplainer.explainError(build, listener, "ERROR", 100);
        });
    }

    @Test
    void testErrorExplainerWithInvalidConfig(JenkinsRule jenkins) throws Exception {
        ErrorExplainer errorExplainer = new ErrorExplainer();
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        
        // Test with null API key
        config.setEnableExplanation(true);
        config.setApiKey(null);
        
        FreeStyleProject project = jenkins.createFreeStyleProject();
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        TaskListener listener = jenkins.createTaskListener();
        
        // Should not throw exception with null API key
        assertDoesNotThrow(() -> {
            errorExplainer.explainError(build, listener, "ERROR", 100);
        });
    }

    @Test
    void testErrorExplainerTextMethods(JenkinsRule jenkins) throws Exception {
        ErrorExplainer errorExplainer = new ErrorExplainer();
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        
        // Setup valid configuration
        config.setEnableExplanation(true);
        config.setApiKey(Secret.fromString("test-api-key"));
        config.setProvider(AIProvider.OPENAI);
        config.setApiUrl("https://api.openai.com/v1/chat/completions");
        config.setModel("gpt-3.5-turbo");
        
        FreeStyleProject project = jenkins.createFreeStyleProject();
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        
        // Test with valid error text (will fail with API but should not throw exception)
        assertDoesNotThrow(() -> {
            String result = errorExplainer.explainErrorText("Build failed", build);
            // Result should be a non-empty error message since we're using a fake API key
            assertNotNull(result);
            assertFalse(result.isEmpty());
            // Should contain error message indicating communication failure
            assertTrue(result.contains("Failed to") || result.contains("ERROR"));
        });
        
        // Test with null input
        assertDoesNotThrow(() -> {
            String result = errorExplainer.explainErrorText(null, build);
            // Should return error message about no error text provided
            assertNotNull(result);
            assertEquals("No error text provided to explain.", result);
        });
        
        // Test with empty input
        assertDoesNotThrow(() -> {
            String result = errorExplainer.explainErrorText("", build);
            // Should return error message about no error text provided
            assertNotNull(result);
            assertEquals("No error text provided to explain.", result);
        });
    }
}
