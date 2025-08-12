package io.jenkins.plugins.explain_error;

import hudson.Extension;
import hudson.model.PageDecorator;
import hudson.model.Run;
import hudson.util.Secret;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * Page decorator to add "Explain Error" functionality to console output pages.
 */
@Extension
public class ConsolePageDecorator extends PageDecorator {

    public ConsolePageDecorator() {
        super();
    }

    public boolean isExplainErrorEnabled() {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        
        // Must have explanation enabled and API key
        if (!config.isEnableExplanation() || Secret.toString(config.getApiKey()).isBlank()) {
            return false;
        }
        
        // If user has explicitly set an API URL, it must be valid
        String rawApiUrl = config.getRawApiUrl();
        if (rawApiUrl != null && rawApiUrl.trim().isEmpty()) {
            // User explicitly set empty string - invalid
            return false;
        }
        
        // If no API URL is set, defaults will be used - that's valid
        // If API URL is set to a non-empty value, that's also valid
        return true;
    }

    /**
     * Get job context for the current request if it's a console page.
     * @return job context string like "[JobName #BuildNumber]" or empty string if not applicable
     */
    public String getJobContext() {
        StaplerRequest2 request = Stapler.getCurrentRequest2();
        if (request == null) {
            return "";
        }
        
        // Get the current object from the request
        Object ancestor = request.findAncestorObject(Run.class);
        if (ancestor instanceof Run) {
            Run<?, ?> run = (Run<?, ?>) ancestor;
            return JobContextUtil.createJobContext(run);
        }
        
        return "";
    }
}
