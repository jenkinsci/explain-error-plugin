package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ErrorExplanationActionTest {

    private ErrorExplanationAction action;
    private String testExplanation;
    private String testErrorLogs;

    @BeforeEach
    void setUp() {
        testExplanation = "This is a test explanation of the error";
        testErrorLogs = "ERROR: Build failed\nFAILED: Compilation error";
        action = new ErrorExplanationAction(testExplanation, testErrorLogs);
    }

    @Test
    void testConstructor() {
        assertNotNull(action);
        assertEquals(testExplanation, action.getExplanation());
        assertEquals(testErrorLogs, action.getOriginalErrorLogs());
        assertTrue(action.getTimestamp() > 0);
        assertTrue(action.getTimestamp() <= System.currentTimeMillis());
    }

    @Test
    void testGetIconFileName() {
        assertEquals("symbol-cube", action.getIconFileName());
    }

    @Test
    void testGetDisplayName() {
        assertEquals("AI Error Explanation", action.getDisplayName());
    }

    @Test
    void testGetUrlName() {
        assertEquals("error-explanation", action.getUrlName());
    }

    @Test
    void testGetExplanation() {
        assertEquals(testExplanation, action.getExplanation());
    }

    @Test
    void testGetOriginalErrorLogs() {
        assertEquals(testErrorLogs, action.getOriginalErrorLogs());
    }

    @Test
    void testGetTimestamp() {
        long timestamp = action.getTimestamp();
        assertTrue(timestamp > 0);
        assertTrue(timestamp <= System.currentTimeMillis());
    }

    @Test
    void testGetFormattedTimestamp() {
        String formatted = action.getFormattedTimestamp();
        assertNotNull(formatted);
        assertFalse(formatted.trim().isEmpty());
    }

    @Test
    void testRunAction2Interface(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("test");
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        
        // Test onAttached
        action.onAttached(build);
        
        // Test onLoad
        action.onLoad(build);
        
        // The action should now be associated with the build
        // This doesn't throw an exception, so the interface is properly implemented
        assertTrue(true);
    }

    @Test
    void testWithNullExplanation() {
        ErrorExplanationAction nullAction = new ErrorExplanationAction(null, testErrorLogs);
        assertNull(nullAction.getExplanation());
        assertEquals(testErrorLogs, nullAction.getOriginalErrorLogs());
    }

    @Test
    void testWithNullErrorLogs() {
        ErrorExplanationAction nullAction = new ErrorExplanationAction(testExplanation, null);
        assertEquals(testExplanation, nullAction.getExplanation());
        assertNull(nullAction.getOriginalErrorLogs());
    }

    @Test
    void testWithEmptyStrings() {
        ErrorExplanationAction emptyAction = new ErrorExplanationAction("", "");
        assertEquals("", emptyAction.getExplanation());
        assertEquals("", emptyAction.getOriginalErrorLogs());
    }

    @Test
    void testWithLongExplanation() {
        StringBuilder longExplanation = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            longExplanation.append("This is line ").append(i).append(" of a very long explanation.\n");
        }
        
        ErrorExplanationAction longAction = new ErrorExplanationAction(longExplanation.toString(), testErrorLogs);
        assertEquals(longExplanation.toString(), longAction.getExplanation());
    }

    @Test
    void testWithSpecialCharacters() {
        String specialExplanation = "Error with special chars: <>&\"'\nUnicode: ñáéíóú 中文 العربية";
        String specialErrorLogs = "ERROR: File 'test@#$%^&*().txt' not found";
        
        ErrorExplanationAction specialAction = new ErrorExplanationAction(specialExplanation, specialErrorLogs);
        assertEquals(specialExplanation, specialAction.getExplanation());
        assertEquals(specialErrorLogs, specialAction.getOriginalErrorLogs());
    }

    @Test
    void testTimestampConsistency() throws InterruptedException {
        long beforeCreation = System.currentTimeMillis();
        Thread.sleep(10); // Small delay to ensure timestamp difference
        
        ErrorExplanationAction timedAction = new ErrorExplanationAction("test", "test");
        
        Thread.sleep(10); // Small delay to ensure timestamp difference
        long afterCreation = System.currentTimeMillis();
        
        long actionTimestamp = timedAction.getTimestamp();
        assertTrue(actionTimestamp >= beforeCreation);
        assertTrue(actionTimestamp <= afterCreation);
    }
}
