package io.jenkins.plugins.explain_error;

/**
 * Enum representing the supported AI providers.
 * @deprecated in favour of {@link io.jenkins.plugins.explain_error.provider.BaseAIProvider}
 */
@Deprecated
public enum AIProvider {
    OPENAI("OpenAI", "gpt-4"),
    GEMINI("Google Gemini", "gemini-2.0-flash"),
    OLLAMA("Ollama", "gemma3:1b"),
    LANGGRAPH("LangGraph Platform", "my-agent");

    private final String displayName;
    private final String defaultModel;

    AIProvider(String displayName, String defaultModel) {
        this.displayName = displayName;
        this.defaultModel = defaultModel;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDefaultModel() {
        return defaultModel;
    }
}
