package io.jenkins.plugins.explain_error;

import dev.langchain4j.model.output.structured.Description;
import java.util.List;

public record JenkinsLogAnalysis(
    @Description("A summary of what caused the error")
    String errorSummary,

    @Description("Specific actionable steps to resolve the issue")
    List<String> resolutionSteps,

    @Description("Relevant best practices to prevent similar issues in the future")
    List<String> bestPractices,

    @Description("The first 40 words of the error line to verify location")
    String errorSignature
) {
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
