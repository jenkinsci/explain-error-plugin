package io.jenkins.plugins.explain_error;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import io.jenkins.plugins.explain_error.provider.AzureOpenAIProvider;
import io.jenkins.plugins.explain_error.provider.BaseAIProvider;
import io.jenkins.plugins.explain_error.provider.OpenAIProvider;
import io.jenkins.plugins.explain_error.provider.GeminiProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ExplainErrorFolderProperty.
 */
@WithJenkins
class ExplainErrorFolderPropertyTest {

    @Test
    void testFolderPropertyCreation(JenkinsRule jenkins) throws Exception {
        // Create a folder
        Folder folder = jenkins.jenkins.createProject(Folder.class, "test-folder");

        // Create and set the folder property
        ExplainErrorFolderProperty property = new ExplainErrorFolderProperty();
        property.setEnableExplanation(true);
        property.setAiProvider(new OpenAIProvider(null, "gpt-4", Secret.fromString("test-key")));

        folder.addProperty(property);

        // Verify the property was added
        ExplainErrorFolderProperty retrievedProperty = folder.getProperties().get(ExplainErrorFolderProperty.class);
        assertNotNull(retrievedProperty);
        assertTrue(retrievedProperty.isEnableExplanation());
        assertNotNull(retrievedProperty.getAiProvider());
        assertEquals("OpenAI", retrievedProperty.getAiProvider().getProviderName());
    }

    @Test
    void testFindFolderProvider(JenkinsRule jenkins) throws Exception {
        // Create a parent folder with configuration
        Folder parentFolder = jenkins.jenkins.createProject(Folder.class, "parent-folder");
        ExplainErrorFolderProperty parentProperty = new ExplainErrorFolderProperty();
        parentProperty.setEnableExplanation(true);
        BaseAIProvider parentProvider = new OpenAIProvider(null, "gpt-4", Secret.fromString("parent-key"));
        parentProperty.setAiProvider(parentProvider);
        parentFolder.addProperty(parentProperty);

        // Create a child folder without configuration
        Folder childFolder = parentFolder.createProject(Folder.class, "child-folder");

        // Find provider should return parent's provider
        BaseAIProvider foundProvider = ExplainErrorFolderProperty.findFolderProvider(childFolder);
        assertNotNull(foundProvider);
        assertEquals("OpenAI", foundProvider.getProviderName());
        assertEquals("gpt-4", foundProvider.getModel());
    }

    @Test
    void testFindFolderProviderWithChildOverride(JenkinsRule jenkins) throws Exception {
        // Create a parent folder with OpenAI configuration
        Folder parentFolder = jenkins.jenkins.createProject(Folder.class, "parent-folder-2");
        ExplainErrorFolderProperty parentProperty = new ExplainErrorFolderProperty();
        parentProperty.setEnableExplanation(true);
        parentProperty.setAiProvider(new OpenAIProvider(null, "gpt-4", Secret.fromString("parent-key")));
        parentFolder.addProperty(parentProperty);

        // Create a child folder with Gemini configuration (override)
        Folder childFolder = parentFolder.createProject(Folder.class, "child-folder-2");
        ExplainErrorFolderProperty childProperty = new ExplainErrorFolderProperty();
        childProperty.setEnableExplanation(true);
        BaseAIProvider childProvider = new GeminiProvider(null, "gemini-pro", Secret.fromString("child-key"));
        childProperty.setAiProvider(childProvider);
        childFolder.addProperty(childProperty);

        // Find provider should return child's provider (closer match)
        BaseAIProvider foundProvider = ExplainErrorFolderProperty.findFolderProvider(childFolder);
        assertNotNull(foundProvider);
        assertEquals("Google Gemini", foundProvider.getProviderName());
        assertEquals("gemini-pro", foundProvider.getModel());
    }

    @Test
    void testFindFolderProviderReturnsNullWhenNotConfigured(JenkinsRule jenkins) throws Exception {
        // Create a folder without configuration
        Folder folder = jenkins.jenkins.createProject(Folder.class, "empty-folder");

        // Find provider should return null
        BaseAIProvider foundProvider = ExplainErrorFolderProperty.findFolderProvider(folder);
        assertNull(foundProvider);
    }

    @Test
    void testFindFolderProviderReturnsNullWhenDisabled(JenkinsRule jenkins) throws Exception {
        // Create a folder with disabled configuration
        Folder folder = jenkins.jenkins.createProject(Folder.class, "disabled-folder");
        ExplainErrorFolderProperty property = new ExplainErrorFolderProperty();
        property.setEnableExplanation(false);
        property.setAiProvider(new OpenAIProvider(null, "gpt-4", Secret.fromString("test-key")));
        folder.addProperty(property);

        // Find provider should return null when disabled
        BaseAIProvider foundProvider = ExplainErrorFolderProperty.findFolderProvider(folder);
        assertNull(foundProvider);
    }

    @Test
    void testIsFolderExplanationEnabled(JenkinsRule jenkins) throws Exception {
        // Create a folder with enabled configuration
        Folder folder = jenkins.jenkins.createProject(Folder.class, "enabled-folder");
        ExplainErrorFolderProperty property = new ExplainErrorFolderProperty();
        property.setEnableExplanation(true);
        folder.addProperty(property);

        // Should return true
        assertTrue(ExplainErrorFolderProperty.isFolderExplanationEnabled(folder));
    }

    @Test
    void testIsFolderExplanationDisabled(JenkinsRule jenkins) throws Exception {
        // Create a folder with disabled configuration
        Folder folder = jenkins.jenkins.createProject(Folder.class, "disabled-folder-2");
        ExplainErrorFolderProperty property = new ExplainErrorFolderProperty();
        property.setEnableExplanation(false);
        folder.addProperty(property);

        // Should return false
        assertFalse(ExplainErrorFolderProperty.isFolderExplanationEnabled(folder));
    }

    @Test
    void testIsFolderExplanationEnabledByDefault(JenkinsRule jenkins) throws Exception {
        // Create a folder without configuration
        Folder folder = jenkins.jenkins.createProject(Folder.class, "default-folder");

        // Should return true (default)
        assertTrue(ExplainErrorFolderProperty.isFolderExplanationEnabled(folder));
    }

    @Test
    void testIsFolderExplanationEnabledWithNullItemGroup() {
        // Should return true (default) for null
        assertTrue(ExplainErrorFolderProperty.isFolderExplanationEnabled(null));
    }

    @Test
    void testFindFolderProviderWithNullItemGroup() {
        // Should return null for null
        assertNull(ExplainErrorFolderProperty.findFolderProvider(null));
    }

    @Test
    void testSetEnableExplanationFalseNullifiesProvider() {
        // Setting a provider then disabling should clear the provider (documented side effect)
        ExplainErrorFolderProperty property = new ExplainErrorFolderProperty();
        property.setAiProvider(new OpenAIProvider(null, "gpt-4", Secret.fromString("key")));
        assertNotNull(property.getAiProvider(), "Provider should be set before disabling");

        property.setEnableExplanation(false);

        assertNull(property.getAiProvider(),
                "setEnableExplanation(false) should clear the provider to ensure fallback to global");
        assertFalse(property.isEnableExplanation());
    }

    @Test
    void testFindFolderProvider_threeDeepHierarchy_findsGrandparentProvider(JenkinsRule jenkins) throws Exception {
        // Grandparent has provider; parent and child do not — verifies recursion walks up two levels
        Folder grandparent = jenkins.jenkins.createProject(Folder.class, "grandparent-folder");
        ExplainErrorFolderProperty grandparentProp = new ExplainErrorFolderProperty();
        grandparentProp.setAiProvider(new OpenAIProvider(null, "gpt-4", Secret.fromString("gp-key")));
        grandparent.addProperty(grandparentProp);

        Folder parent = grandparent.createProject(Folder.class, "parent-folder-3");
        Folder child = parent.createProject(Folder.class, "child-folder-3");

        BaseAIProvider foundProvider = ExplainErrorFolderProperty.findFolderProvider(child);
        assertNotNull(foundProvider, "Should walk up two levels to find grandparent's provider");
        assertEquals("gpt-4", foundProvider.getModel());
    }

    @Test
    void testFolderPropertySupportsAzureOpenAiProvider(JenkinsRule jenkins) throws Exception {
        Folder folder = jenkins.jenkins.createProject(Folder.class, "azure-folder");
        ExplainErrorFolderProperty property = new ExplainErrorFolderProperty();
        property.setEnableExplanation(true);
        property.setAiProvider(new AzureOpenAIProvider(
                "https://my-resource.openai.azure.com",
                "gpt-4o-enterprise",
                "2025-01-01-preview",
                "azure-openai-key",
                null));

        folder.addProperty(property);

        ExplainErrorFolderProperty retrieved = folder.getProperties().get(ExplainErrorFolderProperty.class);
        assertNotNull(retrieved);
        assertInstanceOf(AzureOpenAIProvider.class, retrieved.getAiProvider());
        AzureOpenAIProvider azure = (AzureOpenAIProvider) retrieved.getAiProvider();
        assertEquals("gpt-4o-enterprise", azure.getDeployment());
        assertEquals("azure-openai-key", azure.getCredentialsId());
    }

    @Test
    void descriptorListsQuotaWindowOptions(JenkinsRule jenkins) {
        ExplainErrorFolderProperty.DescriptorImpl descriptor =
                jenkins.jenkins.getDescriptorByType(ExplainErrorFolderProperty.DescriptorImpl.class);

        ListBoxModel items = descriptor.doFillQuotaWindowItems();

        assertEquals(2, items.size());
        assertEquals("Hourly", items.get(0).name);
        assertEquals(QuotaWindow.HOURLY.name(), items.get(0).value);
        assertEquals("Daily", items.get(1).name);
        assertEquals(QuotaWindow.DAILY.name(), items.get(1).value);
    }

    @Test
    void testFindFolderPromptSettingsWithNearestFolderOverride(JenkinsRule jenkins) throws Exception {
        Folder parentFolder = jenkins.jenkins.createProject(Folder.class, "prompt-parent-folder");
        ExplainErrorFolderProperty parentProperty = new ExplainErrorFolderProperty();
        parentProperty.setLanguage("French");
        parentProperty.setCustomContext("Parent folder context");
        parentProperty.setTemperature(0.6);
        parentFolder.addProperty(parentProperty);

        Folder childFolder = parentFolder.createProject(Folder.class, "prompt-child-folder");
        ExplainErrorFolderProperty childProperty = new ExplainErrorFolderProperty();
        childProperty.setLanguage("German");
        childProperty.setCustomContext("Child folder context");
        childProperty.setTemperature(0.2);
        childFolder.addProperty(childProperty);

        assertEquals("German", ExplainErrorFolderProperty.findFolderLanguage(childFolder));
        assertEquals("Child folder context", ExplainErrorFolderProperty.findFolderCustomContext(childFolder));
        assertEquals(0.2, ExplainErrorFolderProperty.findFolderTemperature(childFolder));
    }

    @Test
    void testFindFolderPromptSettingsFallsBackToParentFolder(JenkinsRule jenkins) throws Exception {
        Folder parentFolder = jenkins.jenkins.createProject(Folder.class, "prompt-fallback-parent");
        ExplainErrorFolderProperty parentProperty = new ExplainErrorFolderProperty();
        parentProperty.setLanguage("Italian");
        parentProperty.setCustomContext("Parent fallback context");
        parentProperty.setTemperature(0.5);
        parentFolder.addProperty(parentProperty);

        Folder childFolder = parentFolder.createProject(Folder.class, "prompt-fallback-child");

        assertEquals("Italian", ExplainErrorFolderProperty.findFolderLanguage(childFolder));
        assertEquals("Parent fallback context", ExplainErrorFolderProperty.findFolderCustomContext(childFolder));
        assertEquals(0.5, ExplainErrorFolderProperty.findFolderTemperature(childFolder));
    }

    @Test
    void testFolderConfigurationUiExposesPromptSettings() throws IOException {
        String jelly = resourceText("/io/jenkins/plugins/explain_error/ExplainErrorFolderProperty/config.jelly");

        assertTrue(jelly.contains("field=\"customContext\""));
        assertTrue(jelly.contains("field=\"language\""));
        assertTrue(jelly.contains("field=\"temperature\""));
    }

    private String resourceText(String path) throws IOException {
        try (var stream = getClass().getResourceAsStream(path)) {
            assertNotNull(stream, "Resource should exist: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
