package io.jenkins.plugins.explain_error.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.output.OutputParsingException;
import hudson.util.Secret;
import io.jenkins.plugins.explain_error.JenkinsLogAnalysis;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AnalysisSchemaTest {

    /** Takes a JSON schema like Gemini does, and answers with {@code reply}. */
    private static final class SchemaModel implements ChatModel {
        private final String reply;
        private ChatRequest request;

        SchemaModel(String reply) {
            this.reply = reply;
        }

        @Override
        public Set<Capability> supportedCapabilities() {
            return Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA);
        }

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            request = chatRequest;
            return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
        }
    }

    @Test
    void schemaRequiresTheSummary() {
        SchemaModel model = new SchemaModel("""
                {"title": "Missing BUILD_VERSION", "errorSummary": "BUILD_VERSION is not defined.",
                 "resolutionSteps": ["Define BUILD_VERSION"], "bestPractices": [], "errorSignature": "x"}""");

        JenkinsLogAnalysis analysis = AiServices.create(BaseAIProvider.Assistant.class, model)
                .analyzeLogs("logs", "English", "");

        JsonObjectSchema schema = (JsonObjectSchema) model.request.parameters().responseFormat().jsonSchema()
                .rootElement();
        assertEquals(List.of("errorSummary"), schema.required());
        assertEquals("BUILD_VERSION is not defined.", analysis.errorSummary());
    }

    @Test
    void aTitleAloneIsNotAnAnswer() {
        BaseAIProvider.Assistant assistant = AiServices.create(BaseAIProvider.Assistant.class,
                new SchemaModel("{\"title\": \"Missing BUILD_VERSION\"}"));

        assertThrows(OutputParsingException.class, () -> assistant.analyzeLogs("logs", "English", ""));
    }

    @Test
    void geminiIsSentTheSchema() {
        ChatModel gemini = new GeminiProvider(null, "gemini-2.5-flash", Secret.fromString("key"))
                .createChatModel(null, null, null);

        assertTrue(gemini.supportedCapabilities().contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA));
    }
}
