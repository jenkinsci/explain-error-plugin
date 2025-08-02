package io.jenkins.plugins.explain_error;

import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.ProxyConfiguration;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Logger;
import org.apache.commons.lang3.StringUtils;

/**
 * Base class for AI service implementations.
 * Provides common functionality for different AI providers.
 */
public abstract class BaseAIService {
    
    protected static final Logger LOGGER = Logger.getLogger(BaseAIService.class.getName());
    protected static final ObjectMapper MAPPER = new ObjectMapper();
    
    protected final GlobalConfigurationImpl config;
    
    public BaseAIService(GlobalConfigurationImpl config) {
        this.config = config;
    }
    
    /**
     * Explain error logs using the configured AI provider.
     * @param errorLogs the error logs to explain
     * @return the AI explanation
     * @throws IOException if there's a communication error
     */
    public String explainError(String errorLogs) throws IOException {
        if (StringUtils.isBlank(errorLogs)) {
            return "No error logs provided for explanation.";
        }

        String prompt = buildPrompt(errorLogs);
        String requestBody = buildRequestBody(prompt);
        
        try {
            URI apiUri = URI.create(getApiUrl());
            
            // Use Jenkins' ProxyConfiguration.newHttpRequestBuilder() to get a properly 
            // configured HttpRequest that respects Jenkins proxy settings
            HttpRequest.Builder requestBuilder = ProxyConfiguration.newHttpRequestBuilder(apiUri);
            
            // Build the HTTP request with proper proxy configuration
            HttpRequest request = buildHttpRequest(requestBuilder, requestBody);

            // Create HttpClient with timeout configuration
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

            // Execute the request
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body();

            LOGGER.fine("Response body length: " + responseBody.length());
            LOGGER.fine("Response body preview: " + responseBody.substring(0, Math.min(500, responseBody.length())));

            if (response.statusCode() != 200) {
                LOGGER.severe("AI API request failed with status " + response.statusCode() + ": " + responseBody);
                return "Failed to get explanation from AI service. Status: " + response.statusCode() 
                    + ". Please check your API configuration and key.";
            }

            return parseResponse(responseBody);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.severe("AI API request was interrupted: " + e.getMessage());
            return "Request was interrupted: " + e.getMessage();
        } catch (Exception e) {
            LOGGER.severe("AI API request failed: " + e.getMessage());
            return "Failed to communicate with AI service: " + e.getMessage();
        }
    }
    
    /**
     * Build the prompt for the AI service.
     */
    protected String buildPrompt(String errorLogs) {
        return "You are an expert Jenkins administrator and software engineer. "
                + "Please analyze the following Jenkins build error logs and provide a clear, "
                + "actionable explanation of what went wrong and how to fix it:\n\n"
                + "ERROR LOGS:\n"
                + errorLogs + "\n\n" + "Please provide:\n"
                + "1. A summary of what caused the error\n"
                + "2. Specific steps to resolve the issue\n"
                + "3. Any relevant best practices to prevent similar issues\n\n"
                + "Keep your response concise and focused on actionable solutions.";
    }
    
    /**
     * Get the API URL, potentially with model substitution for providers that need it.
     */
    protected String getApiUrl() {
        String url = config.getApiUrl();
        if (url.contains("{model}")) {
            url = url.replace("{model}", config.getModel());
        }
        return url;
    }
    
    /**
     * Build the HTTP request for the specific AI provider.
     */
    protected abstract HttpRequest buildHttpRequest(HttpRequest.Builder requestBuilder, String requestBody);
    
    /**
     * Build the request body for the specific AI provider.
     */
    protected abstract String buildRequestBody(String prompt) throws IOException;
    
    /**
     * Parse the response from the specific AI provider.
     */
    protected abstract String parseResponse(String responseBody) throws IOException;
}
