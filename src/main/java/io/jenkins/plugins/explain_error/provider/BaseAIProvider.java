package io.jenkins.plugins.explain_error.provider;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.logging.Logger;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.ExtensionPoint;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import io.jenkins.plugins.explain_error.ExplanationException;
import io.jenkins.plugins.explain_error.JenkinsLogAnalysis;

public abstract class BaseAIProvider extends AbstractDescribableImpl<BaseAIProvider> implements ExtensionPoint {

    private static final Logger LOGGER = Logger.getLogger(BaseAIProvider.class.getName());

    protected String url;
    protected String model;

    public BaseAIProvider(String url, String model) {
        this.url = url;
        this.model = model;
    }

    public abstract Assistant createAssistant();

    public abstract boolean isNotValid(@CheckForNull TaskListener listener);

    public String getUrl() {
        return url;
    }

    public String getModel() {
        return model;
    }

    /**
     * Explain error logs using the configured AI provider.
     * @param errorLogs the error logs to explain
     * @return the AI explanation
     * @throws ExplanationException if there's a communication error
     */
    public final String explainError(String errorLogs, TaskListener listener) throws ExplanationException {
        return explainError(errorLogs, listener, null, null);
    }

    /**
     * Explain error logs using the configured AI provider.
     * @param errorLogs the error logs to explain
     * @param language the preferred response language
     * @return the AI explanation
     * @throws ExplanationException if there's a communication error
     */
    public final String explainError(String errorLogs, TaskListener listener, String language) throws ExplanationException {
        return explainError(errorLogs, listener, language, null);
    }

    /**
     * Explain error logs using the configured AI provider.
     * @param errorLogs the error logs to explain
     * @param listener the task listener for logging
     * @param language the preferred response language
     * @param customContext additional custom context/instructions for the AI
     * @return the AI explanation
     * @throws ExplanationException if there's a communication error
     */
    public final String explainError(String errorLogs, TaskListener listener, String language, String customContext) throws ExplanationException {
        Assistant assistant;

        if (StringUtils.isBlank(errorLogs)) {
            throw new ExplanationException("warning", "No error logs provided for explanation.");
        }

        if (isNotValid(listener)) {
            throw new ExplanationException("error", "The provider is not properly configured.");
        }

        try {
            assistant = createAssistant();
        } catch (Exception e) {
            throw new ExplanationException("error", "Failed to create assistant", e);
        }

        String responseLanguage = StringUtils.isBlank(language) ? "English" : language.trim();
        String additionalContext = StringUtils.isBlank(customContext)
            ? "" 
            : "\n\nIMPORTANT - ADDITIONAL INSTRUCTIONS (You MUST address these in your response):\n" + customContext.trim();
        
        LOGGER.fine("Explaining error with language: " + responseLanguage + ", customContext length: " + additionalContext.length());

        try {
            return assistant.analyzeLogs(errorLogs, responseLanguage, additionalContext).toString();
        } catch (Exception e) {
            LOGGER.severe("AI API request failed: " + e.getMessage());
            throw new ExplanationException("error", "API request failed: " + e.getMessage(), e);
        }
    }

    @Override
    public BaseProviderDescriptor getDescriptor() {
        return (BaseProviderDescriptor) super.getDescriptor();
    }

    public interface Assistant {
        @SystemMessage("""
            You are an expert Jenkins administrator and software engineer.
            You MUST follow ALL instructions provided by the user, including any additional context or requirements.
            When additional instructions are provided, you MUST incorporate them into your analysis fields,
            especially in errorSummary and resolutionSteps.
            """)
        @UserMessage("""
            Analyze the following Jenkins build error logs and provide a clear, actionable explanation.
            
            CRITICAL: You MUST respond ONLY in {{language}}. ALL text in your response must be in {{language}}.
            This includes: error summaries, resolution steps, best practices, and any other text.
            {{customContext}}
            
            ERROR LOGS:
            {{errorLogs}}
            
            Remember: Your ENTIRE response must be in {{language}}, including all field values.
            If additional instructions were provided above, you MUST address them in your errorSummary or resolutionSteps.
            """)
        JenkinsLogAnalysis analyzeLogs(@V("errorLogs") String errorLogs, @V("language") String language, @V("customContext") String customContext);
    }

    public String getProviderName() {
        return getDescriptor().getDisplayName();
    }

    public abstract static class BaseProviderDescriptor extends Descriptor<BaseAIProvider> {
        public abstract String getDefaultModel();

        @POST
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        public FormValidation doCheckUrl(@QueryParameter String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.ok();
            }
            try {
                URI uri = new URL(value).toURI();
                String scheme = uri.getScheme();
                if (uri.getHost() == null) {
                    return FormValidation.error("url is not well formed.");
                }
                if (!"http".equals(scheme) && !"https".equals(scheme)) {
                    return FormValidation.error("URL must use http or https");
                }
            } catch (MalformedURLException | URISyntaxException e) {
                return FormValidation.error(e, "URL is not well formed.");
            }
            return FormValidation.ok();
        }
    }
}
