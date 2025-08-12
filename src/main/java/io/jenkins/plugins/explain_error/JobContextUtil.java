package io.jenkins.plugins.explain_error;

import hudson.model.Run;

/**
 * Utility class for creating job context information for logging.
 * This helps identify which specific job encountered an error when multiple jobs 
 * are running concurrently and using the AI service.
 */
public class JobContextUtil {

    /**
     * Create a standardized job context string for logging.
     * Format: [JobName #BuildNumber]
     * 
     * @param run the build run
     * @return formatted job context string
     */
    public static String createJobContext(Run<?, ?> run) {
        if (run == null) {
            return "[Unknown Job]";
        }
        
        String jobName = run.getParent().getFullName();
        int buildNumber = run.getNumber();
        
        return String.format("[%s #%d]", jobName, buildNumber);
    }

    /**
     * Create a job context string with additional details for logging.
     * Format: [JobName #BuildNumber - DisplayName]
     * 
     * @param run the build run
     * @return formatted detailed job context string
     */
    public static String createDetailedJobContext(Run<?, ?> run) {
        if (run == null) {
            return "[Unknown Job]";
        }
        
        String jobName = run.getParent().getFullName();
        int buildNumber = run.getNumber();
        String displayName = run.getDisplayName();
        
        return String.format("[%s #%d - %s]", jobName, buildNumber, displayName);
    }
}
