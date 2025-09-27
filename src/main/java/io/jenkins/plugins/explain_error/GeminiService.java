package io.jenkins.plugins.explain_error;

import java.util.logging.Level;
import java.util.logging.Logger;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.service.AiServices;

/**
 * Google Gemini-specific implementation of the AI service using LangChain4j.
 */
public class GeminiService extends BaseAIService {

    protected static final Logger LOGGER = Logger.getLogger(GeminiService.class.getName());

    public GeminiService(GlobalConfigurationImpl config) {
        super(config);
    }

    @Override
    protected Assistant createAssistant() {
        ChatModel model = GoogleAiGeminiChatModel.builder()
            .apiKey(config.getApiKey().getPlainText())
            .modelName(config.getModel())
            .temperature(0.3)
            .logRequests(LOGGER.getLevel() == Level.FINE)
            .logResponses(LOGGER.getLevel() == Level.FINE)
            .build();
        return AiServices.create(Assistant.class, model);
    }
}
