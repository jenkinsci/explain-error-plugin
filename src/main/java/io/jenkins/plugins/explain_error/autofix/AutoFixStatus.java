package io.jenkins.plugins.explain_error.autofix;

public enum AutoFixStatus {
    CREATED,
    FAILED,
    NOT_APPLICABLE,
    SKIPPED_LOW_CONFIDENCE,
    SKIPPED_PATH_NOT_ALLOWED,
    TIMED_OUT
}
