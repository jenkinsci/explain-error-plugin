package io.jenkins.plugins.explain_error.provider;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/** Asks a provider for workspace files that would help explain a failure. */
public interface WorkspaceFileSelector {

    String SYSTEM_PROMPT = """
            You choose files from a Jenkins workspace that would help diagnose a build failure.
            Return the workspace-relative paths of at most five files that the log names or strongly
            implies, most useful first: source files from stack frames, compiler errors, test failures
            or tracebacks, and the build or configuration file involved.

            Rules:
            - Only paths relative to the workspace.
            - Never an absolute path, a path containing "..", a glob pattern or a directory.
            - Never choose .env*, credentials*, secrets*, or anything under .git/, .gradle/, target/,
              build/, dist/ or node_modules/.
            - If no file would help, return an empty list.
            - Reply with JSON only: {"paths": ["..."]}
            """;

    String USER_PROMPT_TEMPLATE = """
            ERROR LOGS:
            {{errorLogs}}
            """;

    static String userPrompt(String errorLogs) {
        return USER_PROMPT_TEMPLATE.replace("{{errorLogs}}", errorLogs == null ? "" : errorLogs);
    }

    @SystemMessage(SYSTEM_PROMPT)
    @UserMessage(USER_PROMPT_TEMPLATE)
    WorkspaceFileSelection selectFiles(@V("errorLogs") String errorLogs);
}
