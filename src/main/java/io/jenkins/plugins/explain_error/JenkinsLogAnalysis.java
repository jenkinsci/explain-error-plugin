package io.jenkins.plugins.explain_error;

import dev.langchain4j.model.output.structured.Description;
import java.util.List;

public record JenkinsLogAnalysis(
    @Description("What failed and why, in one or two sentences")
    String errorSummary,

    @Description("At most four steps to fix it, the most likely fix first, one sentence each")
    List<String> resolutionSteps,

    @Description("At most two practices that would have prevented this failure; may be empty")
    List<String> bestPractices,

    @Description("The few log lines that show the failure, copied verbatim, at most ten lines")
    String errorSignature,
    @Description("Title for the failure, at most 70 characters: name the root cause, not the symptom."
            + " No trailing period.")
    String title
) {
    /** For callers and stored data from before the title existed. */
    public JenkinsLogAnalysis(String errorSummary, List<String> resolutionSteps, List<String> bestPractices,
                              String errorSignature) {
        this(errorSummary, resolutionSteps, bestPractices, errorSignature, null);
    }

    @Override
    public String toString() {
        return toText(ExplanationLabels.forLanguage(null));
    }

    /** The analysis as plain text, with its section labels in the language of {@code labels}. */
    public String toText(ExplanationLabels labels) {
        StringBuilder sb = new StringBuilder(4096);

        sb.append(labels.getSummary()).append(": ").append(errorSummary).append("\n");

        if (errorSignature != null) {
            sb.append("\n").append(labels.getFailedSnippet()).append(": ");
            sb.append(errorSignature).append("\n");
        }

        if (resolutionSteps != null && !resolutionSteps.isEmpty()) {
            sb.append("\n").append(labels.getResolutionSteps()).append(":\n");
            for (String step : resolutionSteps) {
                sb.append("- ").append(step).append("\n");
            }
        }

        if (bestPractices != null && !bestPractices.isEmpty()) {
            sb.append("\n").append(labels.getBestPractices()).append(":\n");
            for (String step : bestPractices) {
                sb.append("- ").append(step).append("\n");
            }
        }

        return sb.toString();
    }
}
