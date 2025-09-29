package io.jenkins.plugins.explain_error;

import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;

/**
 * Ollama-specific implementation of the AI service using LangChain4j.
 */
public class OllamaService extends BaseAIService {

    protected static final Logger LOGGER = Logger.getLogger(OllamaService.class.getName());

    public OllamaService(GlobalConfigurationImpl config) {
        super(config);
    }

    @Override
    protected Assistant createAssistant() {
        ChatModel model = OllamaChatModel.builder()
            .baseUrl(config.getApiUrl())
            .modelName(config.getModel())
            .temperature(0.3)
            .timeout(Duration.ofSeconds(180))
            .logRequests(LOGGER.getLevel() == Level.FINE)
            .logResponses(LOGGER.getLevel() == Level.FINE)
            .build();
        return AiServices.create(Assistant.class, model);
    }
}
