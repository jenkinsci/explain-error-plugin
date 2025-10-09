package io.jenkins.plugins.explain_error;

import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import org.apache.commons.lang3.StringUtils;

/**
 * Base class for AI service implementations using LangChain4j.
 * Provides common functionality for different AI providers.
 */
public abstract class BaseAIService {

    protected static final Logger LOGGER = Logger.getLogger(BaseAIService.class.getName());

    protected final GlobalConfigurationImpl config;

    public BaseAIService(GlobalConfigurationImpl config) {
        this.config = config;
    }

    interface Assistant {
        String chat(String message);
    }

    /**
     * Explain error logs using the configured AI provider.
     * @param errorLogs the error logs to explain
     * @return the AI explanation
     * @throws IOException if there's a communication error
     */
    public String explainError(String errorLogs) throws IOException {
        Assistant assistant;

        if (StringUtils.isBlank(errorLogs)) {
            return "No error logs provided for explanation.";
        }

        try {
           assistant = createAssistant();
        } catch (Exception e) {
            return "Unable to create assistant api-key or model is invalid.";
        }

        // Use PromptTemplate for dynamic prompt creation
        PromptTemplate promptTemplate = PromptTemplate.from(
            "You are an expert Jenkins administrator and software engineer. "
            + "Please analyze the following Jenkins build error logs and provide a clear, "
            + "actionable explanation of what went wrong and how to fix it:\n\n"
            + "ERROR LOGS:\n"
            + "{{errorLogs}}\n\n" + "Please provide:\n"
            + "1. A summary of what caused the error\n"
            + "2. Specific steps to resolve the issue\n"
            + "3. Any relevant best practices to prevent similar issues\n\n"
            + "Keep your response concise and focused on actionable solutions. "
            + "Use plain text formatting only - no markdown, bold text, italic text, or special symbols for formatting."
        );

        Map<String, Object> variables = new HashMap<>();
        variables.put("errorLogs", errorLogs);
        Prompt prompt = promptTemplate.apply(variables);

        try {
            return assistant.chat(prompt.text());
        } catch (Exception e) {
            LOGGER.severe("AI API request failed: " + e.getMessage());
            return "Failed to communicate with AI service: " + e.getMessage();
        }
    }

    /**
     * Determines the base URL to use for the AI service.
     * Returns the custom URL if provided and not empty, otherwise returns null
     * to let the service use its default endpoint.
     * 
     * @param providerName the name of the provider (for logging purposes)
     * @return the base URL to use, or null to use the default
     */
    protected String determineBaseUrl(String providerName) {
        String baseUrl = (config.getApiUrl() != null && !config.getApiUrl().trim().isEmpty()) 
            ? config.getApiUrl() 
            : null;
        
        if (baseUrl != null) {
            LOGGER.info("Using custom " + providerName + " API URL: " + baseUrl);
        }
        
        return baseUrl;
    }

    protected abstract Assistant createAssistant();
}
