package io.jenkins.plugins.explain_error;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.util.Secret;
import io.jenkins.plugins.explain_error.provider.BaseAIProvider;
import io.jenkins.plugins.explain_error.provider.OpenAIProvider;
import io.jenkins.plugins.explain_error.provider.GeminiProvider;
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
}
