package io.jenkins.plugins.explain_error;

public class ExplanationException extends Exception {
    private final String level;
    private final boolean requestAttempted;

    public ExplanationException(String level, String message) {
        super(message);
        this.level = level;
        this.requestAttempted = false;
    }

    public ExplanationException(String level, String message, Throwable cause) {
        super(message, cause);
        this.level = level;
        this.requestAttempted = false;
    }

    public ExplanationException(String level, String message, boolean requestAttempted) {
        super(message);
        this.level = level;
        this.requestAttempted = requestAttempted;
    }

    public ExplanationException(String level, String message, Throwable cause, boolean requestAttempted) {
        super(message, cause);
        this.level = level;
        this.requestAttempted = requestAttempted;
    }

    public String getLevel() {
        return level;
    }

    public boolean wasRequestAttempted() {
        return requestAttempted;
    }
}
