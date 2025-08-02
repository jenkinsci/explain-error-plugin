package io.jenkins.plugins.explain_error;

/**
 * Enum representing the supported AI providers.
 */
public enum AIProvider {
    OPENAI("OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-3.5-turbo"),
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent", "gemini-1.5-flash");

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

    public String getDefaultApiUrl() {
        return defaultApiUrl;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
