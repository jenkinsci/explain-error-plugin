package io.jenkins.plugins.explain_error;

/**
 * Enum representing the supported AI providers.
 */
public enum AIProvider {
    OPENAI("OpenAI", "gpt-3.5-turbo"),
    GEMINI("Google Gemini", "gemini-1.5-flash");

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
