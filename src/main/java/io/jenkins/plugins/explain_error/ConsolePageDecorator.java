package io.jenkins.plugins.explain_error;

import hudson.Extension;
import hudson.model.PageDecorator;
import hudson.util.Secret;

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
}
