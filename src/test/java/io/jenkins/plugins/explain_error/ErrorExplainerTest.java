package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.TaskListener;
import hudson.util.Secret;
import io.jenkins.plugins.explain_error.provider.GeminiProvider;
import io.jenkins.plugins.explain_error.provider.OpenAIProvider;
import io.jenkins.plugins.explain_error.provider.TestProvider;
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
        config.setAiProvider(new OpenAIProvider(null, "test-model", null));

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
        TestProvider provider = new TestProvider();
        config.setAiProvider(provider);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        // Test with valid error text (will fail with API but should not throw exception)
        assertDoesNotThrow(() -> {
            ErrorExplanationAction action = errorExplainer.explainErrorText("Build failed", "", build);
            assertEquals("Summary: Request was successful\n", action.getExplanation());
        });

        // Test with null input
        ExplanationException e = assertThrows(ExplanationException.class, () -> {
            errorExplainer.explainErrorText(null, "", build);
            // Should return error message about no error text provided
        });
        assertEquals("No error logs provided for explanation.", e.getMessage());

        // Test with empty input
        e = assertThrows(ExplanationException.class, () -> {
            errorExplainer.explainErrorText("", "", build);
            // Should return error message about no error text provided
        });
        assertEquals("No error logs provided for explanation.", e.getMessage());

        // Test with whitespace only input
        e = assertThrows(ExplanationException.class, () -> {
            errorExplainer.explainErrorText("   ", "", build);
            // Should return error message about no error text provided
        });
        assertEquals("No error logs provided for explanation.", e.getMessage());

        // Test with invalid config input
        e = assertThrows(ExplanationException.class, () -> {
            provider.setApiKey(null);
            errorExplainer.explainErrorText("Build Failed", "", build);
        });
        assertEquals("The provider is not properly configured.", e.getMessage());

        // Test with request exception config input
        e = assertThrows(ExplanationException.class, () -> {
            provider.setApiKey(Secret.fromString("test-key"));
            provider.setThrowError(true);
            errorExplainer.explainErrorText("Build failed", "", build);
        });
        assertEquals("API request failed: Request failed.", e.getMessage());
    }

    @Test
    void testFolderLevelProviderResolution(JenkinsRule jenkins) throws Exception {
        ErrorExplainer errorExplainer = new ErrorExplainer();
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();

        // Setup global configuration with OpenAI
        config.setEnableExplanation(true);
        TestProvider globalProvider = new TestProvider();
        globalProvider.setProviderName("Global Provider");
        config.setAiProvider(globalProvider);

        // Create a folder with Gemini configuration
        Folder folder = jenkins.jenkins.createProject(Folder.class, "team-folder");
        ExplainErrorFolderProperty folderProperty = new ExplainErrorFolderProperty();
        folderProperty.setEnableExplanation(true);
        TestProvider folderProvider = new TestProvider();
        folderProvider.setProviderName("Folder Provider");
        folderProperty.setAiProvider(folderProvider);
        folder.addProperty(folderProperty);

        // Create a project in the folder
        FreeStyleProject project = folder.createProject(FreeStyleProject.class, "test-project");
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        // Explain error should use folder provider
        ErrorExplanationAction action = errorExplainer.explainErrorText("Build failed", "", build);
        assertNotNull(action);
        assertEquals("Folder Provider", action.getProviderName());
    }

    @Test
    void testFolderLevelProviderFallbackToGlobal(JenkinsRule jenkins) throws Exception {
        ErrorExplainer errorExplainer = new ErrorExplainer();
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();

        // Setup global configuration
        config.setEnableExplanation(true);
        TestProvider globalProvider = new TestProvider();
        globalProvider.setProviderName("Global Provider");
        config.setAiProvider(globalProvider);

        // Create a folder without configuration
        Folder folder = jenkins.jenkins.createProject(Folder.class, "empty-folder");
        FreeStyleProject project = folder.createProject(FreeStyleProject.class, "test-project-2");
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        // Explain error should use global provider
        ErrorExplanationAction action = errorExplainer.explainErrorText("Build failed", "", build);
        assertNotNull(action);
        assertEquals("Global Provider", action.getProviderName());
    }

    @Test
    void testFolderLevelDisabledExplanation(JenkinsRule jenkins) throws Exception {
        ErrorExplainer errorExplainer = new ErrorExplainer();
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();

        // Setup global configuration as disabled
        config.setEnableExplanation(false);
        TestProvider globalProvider = new TestProvider();
        globalProvider.setProviderName("Global Provider");
        config.setAiProvider(globalProvider);

        // Create a folder with provider configured (would normally enable it)
        Folder folder = jenkins.jenkins.createProject(Folder.class, "disabled-folder");
        ExplainErrorFolderProperty folderProperty = new ExplainErrorFolderProperty();
        folderProperty.setEnableExplanation(true);
        TestProvider folderProvider = new TestProvider();
        folderProvider.setProviderName("Folder Provider");
        folderProperty.setAiProvider(folderProvider);  // Provider configured and enabled at folder level
        folder.addProperty(folderProperty);

        // Create a project in the folder
        FreeStyleProject project = folder.createProject(FreeStyleProject.class, "test-project-3");
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        // Should use folder-level provider even though global is disabled
        ErrorExplanationAction action = errorExplainer.explainErrorText("Build failed", "", build);
        assertNotNull(action);
        assertEquals("Folder Provider", action.getProviderName());
    }

    @Test
    void testFolderLevelDisabledWithoutProviderFallbackToGlobal(JenkinsRule jenkins) throws Exception {
        ErrorExplainer errorExplainer = new ErrorExplainer();
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();

        // Setup global configuration as enabled
        config.setEnableExplanation(true);
        TestProvider globalProvider = new TestProvider();
        globalProvider.setProviderName("Global Provider");
        config.setAiProvider(globalProvider);

        // Create a folder with disabled explanation but NO provider configured
        Folder folder = jenkins.jenkins.createProject(Folder.class, "disabled-no-provider-folder");
        ExplainErrorFolderProperty folderProperty = new ExplainErrorFolderProperty();
        folderProperty.setEnableExplanation(false);
        // No provider configured at folder level
        folder.addProperty(folderProperty);

        // Create a project in the folder
        FreeStyleProject project = folder.createProject(FreeStyleProject.class, "test-project-fallback");
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        // Should fallback to global provider (folder only has enableExplanation=false but no provider)
        ErrorExplanationAction action = errorExplainer.explainErrorText("Build failed", "", build);
        assertNotNull(action);
        assertEquals("Global Provider", action.getProviderName());
    }

    @Test
    void testFolderLevelEnabledOverridesGlobalDisabled(JenkinsRule jenkins) throws Exception {
        ErrorExplainer errorExplainer = new ErrorExplainer();
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();

        // Setup global configuration as DISABLED
        config.setEnableExplanation(false);
        TestProvider globalProvider = new TestProvider();
        config.setAiProvider(globalProvider);

        // Create a folder with ENABLED explanation (should override global disabled)
        Folder folder = jenkins.jenkins.createProject(Folder.class, "enabled-folder-override");
        ExplainErrorFolderProperty folderProperty = new ExplainErrorFolderProperty();
        folderProperty.setEnableExplanation(true);
        TestProvider folderProvider = new TestProvider();
        folderProvider.setProviderName("Folder Override Provider");
        folderProperty.setAiProvider(folderProvider);
        folder.addProperty(folderProperty);

        // Create a project in the folder
        FreeStyleProject project = folder.createProject(FreeStyleProject.class, "test-project-override");
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        // Explain error SHOULD run (folder-level enabled overrides global disabled)
        ErrorExplanationAction action = errorExplainer.explainErrorText("Build failed", "", build);
        assertNotNull(action);
        assertEquals("Folder Override Provider", action.getProviderName());
    }

    @Test
    void testNestedFolderProviderResolution(JenkinsRule jenkins) throws Exception {
        ErrorExplainer errorExplainer = new ErrorExplainer();
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();

        // Setup global configuration
        config.setEnableExplanation(true);
        TestProvider globalProvider = new TestProvider();
        globalProvider.setProviderName("Global Provider");
        config.setAiProvider(globalProvider);

        // Create parent folder with configuration
        Folder parentFolder = jenkins.jenkins.createProject(Folder.class, "parent-folder");
        ExplainErrorFolderProperty parentProperty = new ExplainErrorFolderProperty();
        parentProperty.setEnableExplanation(true);
        TestProvider parentProvider = new TestProvider();
        parentProvider.setProviderName("Parent Provider");
        parentProperty.setAiProvider(parentProvider);
        parentFolder.addProperty(parentProperty);

        // Create child folder without configuration
        Folder childFolder = parentFolder.createProject(Folder.class, "child-folder");
        FreeStyleProject project = childFolder.createProject(FreeStyleProject.class, "test-project-4");
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        // Should use parent folder's provider
        ErrorExplanationAction action = errorExplainer.explainErrorText("Build failed", "", build);
        assertNotNull(action);
        assertEquals("Parent Provider", action.getProviderName());
    }

    @Test
    void testNestedFolderProviderOverride(JenkinsRule jenkins) throws Exception {
        ErrorExplainer errorExplainer = new ErrorExplainer();
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();

        // Setup global configuration
        config.setEnableExplanation(true);
        TestProvider globalProvider = new TestProvider();
        globalProvider.setProviderName("Global Provider");
        config.setAiProvider(globalProvider);

        // Create parent folder with configuration
        Folder parentFolder = jenkins.jenkins.createProject(Folder.class, "parent-folder-2");
        ExplainErrorFolderProperty parentProperty = new ExplainErrorFolderProperty();
        parentProperty.setEnableExplanation(true);
        TestProvider parentProvider = new TestProvider();
        parentProvider.setProviderName("Parent Provider");
        parentProperty.setAiProvider(parentProvider);
        parentFolder.addProperty(parentProperty);

        // Create child folder with its own configuration (override)
        Folder childFolder = parentFolder.createProject(Folder.class, "child-folder-2");
        ExplainErrorFolderProperty childProperty = new ExplainErrorFolderProperty();
        childProperty.setEnableExplanation(true);
        TestProvider childProvider = new TestProvider();
        childProvider.setProviderName("Child Provider");
        childProperty.setAiProvider(childProvider);
        childFolder.addProperty(childProperty);

        FreeStyleProject project = childFolder.createProject(FreeStyleProject.class, "test-project-5");
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        // Should use child folder's provider (closest match)
        ErrorExplanationAction action = errorExplainer.explainErrorText("Build failed", "", build);
        assertNotNull(action);
        assertEquals("Child Provider", action.getProviderName());
    }
}
