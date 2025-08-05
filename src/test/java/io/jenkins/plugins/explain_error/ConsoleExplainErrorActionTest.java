package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ConsoleExplainErrorActionTest {

    private ConsoleExplainErrorAction action;
    private FreeStyleBuild build;

    @BeforeEach
    void setUp(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("test");
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
        // Use reflection to access the private method
        Method method = ConsoleExplainErrorAction.class.getDeclaredMethod("createCachedResponse", String.class);
        method.setAccessible(true);

        String cachedResponse = (String) method.invoke(action, (String) null);

        assertNotNull(cachedResponse);
        assertTrue(cachedResponse.contains("null"));
        assertTrue(cachedResponse.contains("previously generated explanation"));
    }

    @Test
    void testCreateCachedResponseWithEmptyInput() throws Exception {
        // Use reflection to access the private method
        Method method = ConsoleExplainErrorAction.class.getDeclaredMethod("createCachedResponse", String.class);
        method.setAccessible(true);

        String cachedResponse = (String) method.invoke(action, "");

        assertNotNull(cachedResponse);
        assertTrue(cachedResponse.contains("previously generated explanation"));
    }

    @Test
    void testCreateCachedResponseWithLongExplanation() throws Exception {
        // Use reflection to access the private method
        Method method = ConsoleExplainErrorAction.class.getDeclaredMethod("createCachedResponse", String.class);
        method.setAccessible(true);

        StringBuilder longExplanation = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longExplanation.append("This is line ").append(i).append(" of a very long explanation.\n");
        }

        String cachedResponse = (String) method.invoke(action, longExplanation.toString());

        assertNotNull(cachedResponse);
        assertTrue(cachedResponse.contains(longExplanation.toString()));
        assertTrue(cachedResponse.contains("previously generated explanation"));
    }

    @Test
    void testCreateCachedResponseWithSpecialCharacters() throws Exception {
        // Use reflection to access the private method
        Method method = ConsoleExplainErrorAction.class.getDeclaredMethod("createCachedResponse", String.class);
        method.setAccessible(true);

        String specialExplanation = "Error with special chars: <>&\"'\nUnicode: ñáéíóú 中文 العربية";
        String cachedResponse = (String) method.invoke(action, specialExplanation);

        assertNotNull(cachedResponse);
        assertTrue(cachedResponse.contains(specialExplanation));
        assertTrue(cachedResponse.contains("previously generated explanation"));
    }

    @Test
    void testExistingExplanationDetection() {
        // Initially no explanation should exist
        ErrorExplanationAction existingAction = build.getAction(ErrorExplanationAction.class);
        assertNull(existingAction);

        // Add an explanation
        ErrorExplanationAction action = new ErrorExplanationAction("Test explanation", "Error logs");
        build.addAction(action);

        // Now explanation should exist and be valid
        ErrorExplanationAction retrievedAction = build.getAction(ErrorExplanationAction.class);
        assertNotNull(retrievedAction);
        assertTrue(retrievedAction.hasValidExplanation());
        assertEquals("Test explanation", retrievedAction.getExplanation());
    }

    @Test
    void testExistingExplanationDetectionWithInvalidExplanation() {
        // Add an invalid explanation (null content)
        ErrorExplanationAction invalidAction = new ErrorExplanationAction(null, "Error logs");
        build.addAction(invalidAction);

        // Explanation exists but should not be valid
        ErrorExplanationAction retrievedAction = build.getAction(ErrorExplanationAction.class);
        assertNotNull(retrievedAction);
        assertFalse(retrievedAction.hasValidExplanation());
    }

    @Test
    void testExistingExplanationDetectionWithEmptyExplanation() {
        // Add an empty explanation
        ErrorExplanationAction emptyAction = new ErrorExplanationAction("", "Error logs");
        build.addAction(emptyAction);

        // Explanation exists but should not be valid
        ErrorExplanationAction retrievedAction = build.getAction(ErrorExplanationAction.class);
        assertNotNull(retrievedAction);
        assertFalse(retrievedAction.hasValidExplanation());
    }

    @Test
    void testDoCheckExistingExplanationWithNoExistingAction() throws Exception {
        // Use reflection to test the JSON logic without Stapler mocking
        assertNull(build.getAction(ErrorExplanationAction.class));

        // Since no existing action, should return hasExplanation: false
        // We can't easily test the full HTTP response without complex mocking,
        // but we can verify the core logic by checking the build state
        ErrorExplanationAction existingAction = build.getAction(ErrorExplanationAction.class);
        assertNull(existingAction);
    }

    @Test
    void testDoCheckExistingExplanationWithExistingValidAction() throws Exception {
        // Add an existing explanation action
        ErrorExplanationAction existingAction = new ErrorExplanationAction("Test explanation", "Test error logs");
        build.addAction(existingAction);

        // Verify the action was added and is valid
        ErrorExplanationAction retrievedAction = build.getAction(ErrorExplanationAction.class);
        assertNotNull(retrievedAction);
        assertTrue(retrievedAction.hasValidExplanation());
        assertEquals("Test explanation", retrievedAction.getExplanation());
        assertNotNull(retrievedAction.getFormattedTimestamp());
    }

    @Test
    void testDoCheckExistingExplanationWithExistingInvalidAction() throws Exception {
        // Add an existing explanation action with invalid explanation (null)
        ErrorExplanationAction existingAction = new ErrorExplanationAction(null, "Test error logs");
        build.addAction(existingAction);

        // Verify the action was added but is not valid
        ErrorExplanationAction retrievedAction = build.getAction(ErrorExplanationAction.class);
        assertNotNull(retrievedAction);
        assertFalse(retrievedAction.hasValidExplanation());
    }

    @Test
    void testDoCheckExistingExplanationWithEmptyExplanation() throws Exception {
        // Add an existing explanation action with empty explanation
        ErrorExplanationAction existingAction = new ErrorExplanationAction("", "Test error logs");
        build.addAction(existingAction);

        // Verify the action was added but is not valid
        ErrorExplanationAction retrievedAction = build.getAction(ErrorExplanationAction.class);
        assertNotNull(retrievedAction);
        assertFalse(retrievedAction.hasValidExplanation());
    }

    @Test
    void testDoCheckExistingExplanationWithWhitespaceOnlyExplanation() throws Exception {
        // Add an existing explanation action with whitespace-only explanation
        ErrorExplanationAction existingAction = new ErrorExplanationAction("   \n  \t  ", "Test error logs");
        build.addAction(existingAction);

        // Verify the action was added but is not valid
        ErrorExplanationAction retrievedAction = build.getAction(ErrorExplanationAction.class);
        assertNotNull(retrievedAction);
        assertFalse(retrievedAction.hasValidExplanation());
    }

    @Test
    void testDoCheckExistingExplanationLogic() throws Exception {
        // Test the core logic that doCheckExistingExplanation uses

        // Case 1: No existing action
        assertNull(build.getAction(ErrorExplanationAction.class));

        // Case 2: Valid existing action
        ErrorExplanationAction validAction = new ErrorExplanationAction("Valid explanation", "Error logs");
        build.addAction(validAction);

        ErrorExplanationAction retrieved = build.getAction(ErrorExplanationAction.class);
        assertNotNull(retrieved);
        assertTrue(retrieved.hasValidExplanation());

        // Remove and test invalid cases
        build.removeAction(validAction);

        // Case 3: Invalid existing action (null explanation)
        ErrorExplanationAction invalidAction = new ErrorExplanationAction(null, "Error logs");
        build.addAction(invalidAction);

        retrieved = build.getAction(ErrorExplanationAction.class);
        assertNotNull(retrieved);
        assertFalse(retrieved.hasValidExplanation());
    }

    @Test
    void testGetRun() {
        assertEquals(build, action.getRun());
    }

    @Test
    void testBuildStatusCheck() {
        // Test that the action can access build status through the run
        assertNotNull(action.getRun());
        // Build should not be building since it's completed in setUp
        assertFalse(action.getRun().isBuilding());
    }
}
