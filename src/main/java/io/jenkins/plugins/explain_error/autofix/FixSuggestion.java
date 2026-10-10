package io.jenkins.plugins.explain_error.autofix;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FixSuggestion(
        boolean fixable,
        String explanation,
        String confidence,
        String fixType,
        List<FileChange> changes,
        String title) {

    /** For callers from before the title existed. */
    public FixSuggestion(boolean fixable, String explanation, String confidence, String fixType,
                         List<FileChange> changes) {
        this(fixable, explanation, confidence, fixType, changes, null);
    }


    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FileChange(
            String filePath,
            String action,
            String unifiedDiff,
            String description) {}
}
