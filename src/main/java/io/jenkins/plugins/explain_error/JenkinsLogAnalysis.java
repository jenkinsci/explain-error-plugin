package io.jenkins.plugins.explain_error;

import dev.langchain4j.model.output.structured.Description;
import java.util.List;

public record JenkinsLogAnalysis(
    @Description("Error summary")
    String errorSummary,

    @Description("Resolution steps")
    List<String> resolutionSteps,

    @Description("Best practices")
    List<String> bestPractices,

    @Description("Error snippet")
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
        StringBuilder sb = new StringBuilder(4096);

        sb.append("Summary: ").append(errorSummary).append("\n");

        if (errorSignature != null) {
            sb.append("\n").append("Failed Snippet: ");
            sb.append(errorSignature).append("\n");
        }

        if (resolutionSteps != null && !resolutionSteps.isEmpty()) {
            sb.append("\n").append("Resolution Steps:\n");
            for (String step : resolutionSteps) {
                sb.append("- ").append(step).append("\n");
            }
        }

        if (bestPractices != null && !bestPractices.isEmpty()) {
            sb.append("\n").append("Best Practices:\n");
            for (String step : bestPractices) {
                sb.append("- ").append(step).append("\n");
            }
        }

        return sb.toString();
    }
}
