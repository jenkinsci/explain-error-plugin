package io.jenkins.plugins.explain_error;

import hudson.model.FreeStyleProject;
import hudson.model.Run;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Test class for JobContextUtil to ensure proper job context formatting.
 */
@WithJenkins
class JobContextUtilTest {

    @Test
    void testCreateJobContext(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("test-job");
        Run<?, ?> run = jenkins.buildAndAssertSuccess(project);

        String jobContext = JobContextUtil.createJobContext(run);
        
        assertNotNull(jobContext);
        assertTrue(jobContext.contains("test-job"));
        assertTrue(jobContext.contains("#1")); // First build number
        assertTrue(jobContext.startsWith("["));
        assertTrue(jobContext.endsWith("]"));
        
        // Should match format [JobName #BuildNumber]
        String expected = "[test-job #1]";
        assertEquals(expected, jobContext);
    }

    @Test
    void testCreateDetailedJobContext(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("detailed-job");
        Run<?, ?> run = jenkins.buildAndAssertSuccess(project);

        String detailedJobContext = JobContextUtil.createDetailedJobContext(run);
        
        assertNotNull(detailedJobContext);
        assertTrue(detailedJobContext.contains("detailed-job"));
        assertTrue(detailedJobContext.contains("#1"));
        assertTrue(detailedJobContext.contains(run.getDisplayName()));
        assertTrue(detailedJobContext.startsWith("["));
        assertTrue(detailedJobContext.endsWith("]"));
    }

    @Test 
    void testCreateJobContextWithNullRun() {
        String jobContext = JobContextUtil.createJobContext(null);
        assertEquals("[Unknown Job]", jobContext);
    }

    @Test
    void testCreateDetailedJobContextWithNullRun() {
        String detailedJobContext = JobContextUtil.createDetailedJobContext(null);
        assertEquals("[Unknown Job]", detailedJobContext);
    }

    @Test
    void testJobContextWithComplexJobName(JenkinsRule jenkins) throws Exception {
        // Create a job with a complex name (using dots and dashes which are allowed)
        FreeStyleProject project = jenkins.createFreeStyleProject("my.complex-job_name");
        Run<?, ?> run = jenkins.buildAndAssertSuccess(project);

        String jobContext = JobContextUtil.createJobContext(run);
        
        assertNotNull(jobContext);
        assertTrue(jobContext.contains("my.complex-job_name"));
        assertTrue(jobContext.contains("#1"));
        
        String expected = "[my.complex-job_name #1]";
        assertEquals(expected, jobContext);
    }
}
