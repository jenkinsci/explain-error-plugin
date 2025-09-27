package io.jenkins.plugins.explain_error;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

/**
 * OpenAI-specific implementation of the AI service using LangChain4j.
 */
public class OpenAIService extends BaseAIService {

    public OpenAIService(GlobalConfigurationImpl config) {
        super(config);
    }

    @Override
    protected Assistant createAssistant() {
        ChatModel model = OpenAiChatModel.builder()
            .apiKey(config.getApiKey().getPlainText())
            .modelName(config.getModel())
            .temperature(0.3)
            .logRequests(true) // Optional: for debugging
            .logResponses(true) // Optional: for debugging
            .build();
        return AiServices.create(Assistant.class, model);
    }
}
