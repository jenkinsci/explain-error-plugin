package io.jenkins.plugins.explain_error;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Service class for communicating with AI APIs.
 * Factory that creates appropriate AI service implementation based on the configured provider.
 */
public class AIService {

    private static final Logger LOGGER = Logger.getLogger(AIService.class.getName());
    
    private final BaseAIService delegate;

    public AIService(GlobalConfigurationImpl config) {
        this.delegate = createServiceForProvider(config);
    }
    
    /**
     * Create the appropriate AI service implementation based on the provider.
     */
    private BaseAIService createServiceForProvider(GlobalConfigurationImpl config) {
        AIProvider provider = config.getProvider();
        
        switch (provider) {
            case OPENAI:
                return new OpenAIService(config);
            case GEMINI:
                return new GeminiService(config);
            default:
                LOGGER.warning("Unknown AI provider: " + provider + ". Defaulting to OpenAI.");
                return new OpenAIService(config);
        }
    }

    /**
     * Explain error logs using the configured AI provider.
     * @param errorLogs the error logs to explain
     * @return the AI explanation
     * @throws IOException if there's a communication error
     */
    public String explainError(String errorLogs) throws IOException {
        return delegate.explainError(errorLogs);
    }
}