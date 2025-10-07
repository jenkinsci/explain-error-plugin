package io.jenkins.plugins.explain_error;

/**
 * Enum representing the supported AI providers.
 */
public enum AIProvider {
    OPENAI("OpenAI", "gpt-4"),
    GEMINI("Google Gemini", "gemini-2.0-flash"),
    OLLAMA("Ollama", "gemma3:1b");

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

    @Override
    public String toString() {
        return displayName;
    }
}
