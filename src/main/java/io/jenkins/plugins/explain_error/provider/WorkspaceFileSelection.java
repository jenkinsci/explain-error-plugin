package io.jenkins.plugins.explain_error.provider;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.langchain4j.model.output.structured.Description;
import java.util.List;
import java.util.Objects;

/** The workspace-relative paths an AI selection call suggests, most useful first. */
public record WorkspaceFileSelection(
        @JsonProperty(required = true)
        @Description("Workspace-relative file paths that would help explain the failure, "
                + "most useful first; at most five")
        List<String> paths) {

    public WorkspaceFileSelection {
        paths = paths == null
                ? List.of()
                : List.copyOf(paths.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(path -> !path.isEmpty())
                        .toList());
    }
}
