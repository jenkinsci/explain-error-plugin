package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Run;
import java.io.IOException;
import java.util.List;
import jenkins.model.Jenkins;

/**
 * A failed build, written up for a coding agent such as Claude Code or Codex: what failed, where the code came
 * from, the log of the steps that failed and the explanation the build already has, with secrets masked. The
 * agent works in a checkout of the repository, where it can read the code and run the command that failed, so no
 * AI provider is needed in Jenkins for this.
 */
final class AgentContext {

    private AgentContext() {
    }

    /**
     * @param run      the failed build
     * @param maxLines the most log lines to take, shared among the steps that failed
     * @return the text to paste into the agent
     */
    @NonNull
    static String of(@NonNull Run<?, ?> run, int maxLines) throws IOException {
        StringBuilder text = new StringBuilder();
        text.append("The Jenkins build ").append(run.getFullDisplayName()).append(" failed. Find the cause and fix it.")
                .append("\n\n");

        String buildUrl = absoluteUrl(run);
        item(text, "Build", buildUrl);
        item(text, "Result", String.valueOf(run.getResult()));
        BuildSource source = BuildSource.of(run);
        item(text, "Repository", source.repository());
        item(text, "Branch", source.branch());
        item(text, "Commit", source.commit());
        if (source.changeUrl() != null) {
            item(text, "Pull request", source.changeUrl()
                    + (source.changeTarget() != null ? " (into " + source.changeTarget() + ")" : ""));
        }

        ErrorExplanationAction explanation = run.getAction(ErrorExplanationAction.class);
        if (explanation != null && explanation.hasValidExplanation()) {
            text.append("\n## What an AI explanation of the failure says (it may be wrong)\n\n");
            appendExplanation(text, explanation);
        }

        PipelineLogExtractor extractor = ConsoleExplainErrorAction.pageExtractor(run, maxLines);
        String log = String.join("\n", extractor.getFailedStepLog());
        text.append("\n## Log of the steps that failed\n\n");
        fenced(text, log);

        text.append("\nWork in a checkout of the repository at that commit if you can. Reproduce the failure with the"
                + " command that failed, fix the cause rather than the symptom, and say what you changed and how you"
                + " checked it.");
        if (buildUrl != null) {
            text.append(" The whole log is at ").append(buildUrl).append("consoleText.");
        }
        text.append('\n');

        LogRedactor redactor = LogRedactor.fromConfiguration();
        String masked = redactor.redact(text.toString());
        if (redactor.count() > 0) {
            masked += redactor.count() + (redactor.count() == 1 ? " secret was" : " secrets were")
                    + " replaced with " + LogRedactor.MASK + ".\n";
        }
        return masked;
    }

    private static void item(StringBuilder text, String name, @CheckForNull String value) {
        if (value != null && !value.isBlank()) {
            text.append("- ").append(name).append(": ").append(value).append('\n');
        }
    }

    private static void appendExplanation(StringBuilder text, ErrorExplanationAction explanation) {
        if (!explanation.isStructured()) {
            text.append(explanation.getExplanation().strip()).append('\n');
            return;
        }
        if (explanation.getTitle() != null) {
            text.append("**").append(explanation.getTitle()).append("**\n\n");
        }
        if (explanation.getErrorSummary() != null) {
            text.append(explanation.getErrorSummary().strip()).append("\n\n");
        }
        List<String> steps = explanation.getResolutionSteps();
        if (steps != null && !steps.isEmpty()) {
            text.append("Suggested steps:\n");
            for (int i = 0; i < steps.size(); i++) {
                text.append(i + 1).append(". ").append(steps.get(i).strip()).append('\n');
            }
            text.append('\n');
        }
        if (explanation.getErrorSignature() != null && !explanation.getErrorSignature().isBlank()) {
            text.append("Where it failed:\n\n");
            fenced(text, explanation.getErrorSignature());
        }
    }

    /** A code block that no run of backticks in {@code content} can close early. */
    private static void fenced(StringBuilder text, String content) {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < content.length(); i++) {
            run = content.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        String fence = "`".repeat(Math.max(3, longest + 1));
        text.append(fence).append("text\n").append(content.stripTrailing()).append('\n').append(fence).append('\n');
    }

    /** The build's address, from the configured root URL or the request; {@code null} when neither is known. */
    @CheckForNull
    private static String absoluteUrl(Run<?, ?> run) {
        String root = Jenkins.get().getRootUrl();
        return root != null ? root + run.getUrl() : null;
    }
}
