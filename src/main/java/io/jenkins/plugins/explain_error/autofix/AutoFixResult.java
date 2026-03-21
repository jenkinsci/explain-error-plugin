package io.jenkins.plugins.explain_error.autofix;

public class AutoFixResult {

    private final AutoFixStatus status;
    private final String prUrl;
    private final String branchName;
    private final String message;

    private AutoFixResult(AutoFixStatus status, String prUrl, String branchName, String message) {
        this.status = status;
        this.prUrl = prUrl;
        this.branchName = branchName;
        this.message = message;
    }

    public AutoFixStatus getStatus() {
        return status;
    }

    public String getPrUrl() {
        return prUrl;
    }

    public String getBranchName() {
        return branchName;
    }

    public String getMessage() {
        return message;
    }

    public static AutoFixResult created(String prUrl, String branchName) {
        return new AutoFixResult(
                AutoFixStatus.CREATED,
                prUrl,
                branchName,
                "Pull request created successfully: " + prUrl);
    }

    public static AutoFixResult failed(String message) {
        return new AutoFixResult(AutoFixStatus.FAILED, null, null, message);
    }

    public static AutoFixResult notApplicable(String message) {
        return new AutoFixResult(AutoFixStatus.NOT_APPLICABLE, null, null, message);
    }

    public static AutoFixResult skippedLowConfidence() {
        return new AutoFixResult(
                AutoFixStatus.SKIPPED_LOW_CONFIDENCE,
                null,
                null,
                "Skipped: AI confidence level was too low to apply automatic fix.");
    }

    public static AutoFixResult skippedPathNotAllowed(String filePath) {
        return new AutoFixResult(
                AutoFixStatus.SKIPPED_PATH_NOT_ALLOWED,
                null,
                null,
                "Skipped: file path is not allowed for automatic modification: " + filePath);
    }

    public static AutoFixResult timedOut() {
        return new AutoFixResult(
                AutoFixStatus.TIMED_OUT, null, null, "Timed out while attempting to apply automatic fix.");
    }

    @Override
    public String toString() {
        return "AutoFixResult{status=" + status + ", prUrl='" + prUrl + "', branchName='" + branchName
                + "', message='" + message + "'}";
    }
}
