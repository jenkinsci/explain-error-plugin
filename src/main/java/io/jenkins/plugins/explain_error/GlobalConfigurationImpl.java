package io.jenkins.plugins.explain_error;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.jenkinsci.Symbol;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * Global configuration for the plugin.
 */
@Extension
@Symbol("explainError")
public class GlobalConfigurationImpl extends GlobalConfiguration {

    private Secret apiKey;
    private AIProvider provider = AIProvider.OPENAI;
    private String apiUrl;
    private String model;
    private boolean enableExplanation = true;

    public GlobalConfigurationImpl() {
        load();
    }

    /**
     * Get the singleton instance of GlobalConfigurationImpl.
     * @return the GlobalConfigurationImpl instance
     */
    public static GlobalConfigurationImpl get() {
        return Jenkins.get().getDescriptorByType(GlobalConfigurationImpl.class);
    }

    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) throws Descriptor.FormException {
        try {
            // Validate required fields before binding
            if (json.has("enableExplanation")) {
                this.enableExplanation = json.getBoolean("enableExplanation");
            }
            
            if (json.has("provider")) {
                String providerStr = json.getString("provider");
                try {
                    this.provider = AIProvider.valueOf(providerStr);
                } catch (IllegalArgumentException e) {
                    throw new Descriptor.FormException("Invalid provider: " + providerStr, "provider");
                }
            }
            
            if (json.has("apiKey")) {
                String apiKeyStr = json.getString("apiKey");
                this.apiKey = Secret.fromString(apiKeyStr);
            }
            
            if (json.has("apiUrl")) {
                this.apiUrl = json.getString("apiUrl");
            }
            
            if (json.has("model")) {
                this.model = json.getString("model");
            }
            
            save();
            return true;
        } catch (Exception e) {
            Logger.getLogger(GlobalConfigurationImpl.class.getName()).log(Level.SEVERE, "Configuration failed", e);
            throw new Descriptor.FormException("Configuration failed: " + e.getMessage(), e, "");
        }
    }

    // Getters and setters
    public Secret getApiKey() {
        return apiKey;
    }

    @DataBoundSetter
    public void setApiKey(Secret apiKey) {
        this.apiKey = apiKey;
    }

    public AIProvider getProvider() {
        return provider != null ? provider : AIProvider.OPENAI;
    }

    @DataBoundSetter
    public void setProvider(AIProvider provider) {
        this.provider = provider;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    /**
     * Get the raw configured API URL without defaults, used for validation.
     */
    public String getRawApiUrl() {
        return apiUrl;
    }

    @DataBoundSetter
    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getModel() {
        return model;
    }

    /**
     * Get the raw configured model without defaults, used for validation.
     */
    public String getRawModel() {
        return model;
    }

    @DataBoundSetter
    public void setModel(String model) {
        this.model = model;
    }

    public boolean isEnableExplanation() {
        return enableExplanation;
    }

    @DataBoundSetter
    public void setEnableExplanation(boolean enableExplanation) {
        this.enableExplanation = enableExplanation;
    }

    @Override
    public String getDisplayName() {
        return "Explain Error Plugin Configuration";
    }

    /**
     * Get all available AI providers for the dropdown.
     */
    public AIProvider[] getProviderValues() {
        return AIProvider.values();
    }

    /**
     * Populate the provider dropdown items for the UI.
     */
    @RequirePOST
    public ListBoxModel doFillProviderItems() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);

        ListBoxModel model = new ListBoxModel();
        AIProvider currentProvider = getProvider(); // Get the current provider

        for (AIProvider p : AIProvider.values()) {
            model.add(new ListBoxModel.Option(
                p.getDisplayName(),          // display name
                p.name(),                    // actual value
                p == currentProvider         // is selected
            ));
        }

        return model;
}

    /**
     * Method to test the AI API configuration.
     * This is called when the "Test Configuration" button is clicked.
     */
    @RequirePOST
    public FormValidation doTestConfiguration(@QueryParameter("apiKey") String apiKey,
                                                @QueryParameter("provider") String provider,
                                                @QueryParameter("apiUrl") String apiUrl,
                                                @QueryParameter("model") String model) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);

        // Validate only the provided parameters
        Secret testApiKeySecret = (apiKey != null) ? Secret.fromString(apiKey) : null;
        AIProvider testProvider = null;
        if (provider != null && !provider.isEmpty()) {
            try {
                testProvider = AIProvider.valueOf(provider);
            } catch (IllegalArgumentException e) {
                return FormValidation.error("Invalid provider: " + provider);
            }
        }
        String testApiUrl = apiUrl != null ? apiUrl : "";
        String testModel = model != null ? model : "";

        try {
            GlobalConfigurationImpl tempConfig = new GlobalConfigurationImpl();
            tempConfig.setApiKey(testApiKeySecret);
            if (testProvider != null) {
                tempConfig.setProvider(testProvider);
            }
            tempConfig.setApiUrl(testApiUrl);
            tempConfig.setModel(testModel);

            AIService aiService = new AIService(tempConfig);
            String testResponse = aiService.explainError("Test configuration call - please respond with 'Configuration test successful'");

            if (testResponse != null && testResponse.contains("Configuration test successful")) {
                return FormValidation.ok("Configuration test successful! API connection is working properly.");
            } else if (testResponse != null && testResponse.contains("AI API Error:")) {
                return FormValidation.error("" + testResponse);
            } else if (testResponse != null && testResponse.contains("Failed to get explanation from AI service")) {
                return FormValidation.error("" + testResponse);
            } else {
                return FormValidation.ok("API connection established, but got unexpected response: " + testResponse);
            }

        } catch (IOException e) {
            Logger.getLogger(GlobalConfigurationImpl.class.getName()).log(Level.WARNING, "API test failed", e);
            return FormValidation.error("Connection failed: " + e.getMessage() + ". Please check your API URL and network connection.");
        } catch (Exception e) {
            Logger.getLogger(GlobalConfigurationImpl.class.getName()).log(Level.WARNING, "Configuration test failed", e);
            return FormValidation.error("Test failed: " + e.getMessage());
        }
    }
}
