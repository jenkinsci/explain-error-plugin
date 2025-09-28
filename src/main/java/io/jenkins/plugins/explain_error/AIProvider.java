package io.jenkins.plugins.explain_error;

/**
 * Enum representing the supported AI providers.
 */
public enum AIProvider {
    OPENAI("OpenAI", "", "gpt-3.5-turbo"),
    GEMINI("Google Gemini", "", "gemini-1.5-flash"),
    OLLAMA("Ollama", "", "llama2");

    private final String displayName;
    private final String defaultApiUrl;
    private final String defaultModel;

    AIProvider(String displayName, String defaultApiUrl, String defaultModel) {
        this.displayName = displayName;
        this.defaultApiUrl = defaultApiUrl;
        this.defaultModel = defaultModel;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
