package io.jenkins.plugins.explain_error;

import java.util.logging.Level;
import java.util.logging.Logger;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

/**
 * OpenAI-specific implementation of the AI service using LangChain4j.
 */
public class OpenAIService extends BaseAIService {

    protected static final Logger LOGGER = Logger.getLogger(OpenAIService.class.getName());

    public OpenAIService(GlobalConfigurationImpl config) {
        super(config);
    }

    @Override
    protected Assistant createAssistant() {
        // Determine base URL - use custom URL if provided, otherwise use default
        String baseUrl = (config.getApiUrl() != null && !config.getApiUrl().trim().isEmpty()) 
            ? config.getApiUrl() 
            : null;
        
        if (baseUrl != null) {
            LOGGER.info("Using custom OpenAI API URL: " + baseUrl);
        }
        
        ChatModel model = OpenAiChatModel.builder()
            .baseUrl(baseUrl) // Will use default if null
            .apiKey(config.getApiKey().getPlainText())
            .modelName(config.getModel())
            .temperature(0.3)
            .logRequests(LOGGER.getLevel() == Level.FINE)
            .logResponses(LOGGER.getLevel() == Level.FINE)
            .build();
            
        return AiServices.create(Assistant.class, model);
    }
}
