package io.jenkins.plugins.explain_error.autofix;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Run;
import jenkins.model.RunAction2;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * A Jenkins {@link RunAction2} that stores auto-fix results and provides
 * a UI page under the build's action sidebar.
 */
public class AutoFixAction implements RunAction2 {

    private final AutoFixStatus status;
    private final String prUrl;       // nullable
    private final String branchName;  // nullable
    private final String message;
    private final String prTitle;     // nullable
    private final long timestamp;
    private final String scmType;     // "GitHub", "GitLab", "Bitbucket", nullable

    private transient Run<?, ?> run;

    /**
     * Creates an AutoFixAction with all fields.
     *
     * @param status     the result status
     * @param prUrl      the URL of the created PR, or null
     * @param branchName the fix branch name, or null
     * @param message    human-readable status message
     * @param prTitle    the PR title, or null
     * @param timestamp  epoch millis when this action was created
     * @param scmType    "GITHUB", "GITLAB", "BITBUCKET", or null
     */
    public AutoFixAction(AutoFixStatus status, String prUrl, String branchName,
                         String message, String prTitle, long timestamp, String scmType) {
        this.status = status;
        this.prUrl = prUrl;
        this.branchName = branchName;
        this.message = message;
        this.prTitle = prTitle;
        this.timestamp = timestamp;
        this.scmType = scmType;
    }

    @Override
    @NonNull
    public String getIconFileName() {
        return "symbol-git-pull-request-outline plugin-ionicons-api";
    }

    @Override
    @NonNull
    public String getDisplayName() {
        return "AI Auto-Fix";
    }

    @Override
    @NonNull
    public String getUrlName() {
        return "auto-fix";
    }

    @Override
    public void onAttached(Run<?, ?> r) {
        this.run = r;
    }

    @Override
    public void onLoad(Run<?, ?> r) {
        this.run = r;
    }

    /**
     * Used for XStream deserialization backward compatibility.
     * All fields are final and serialized; no migration needed.
     */
    protected Object readResolve() {
        return this;
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    @NonNull
    public AutoFixStatus getStatus() {
        return status;
    }

    @CheckForNull
    public String getPrUrl() {
        return prUrl;
    }

    @CheckForNull
    public String getBranchName() {
        return branchName;
    }

    @NonNull
    public String getMessage() {
        return message;
    }

    @CheckForNull
    public String getPrTitle() {
        return prTitle;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @CheckForNull
    public String getScmType() {
        return scmType;
    }

    @CheckForNull
    public Run<?, ?> getRun() {
        return run;
    }

    // -------------------------------------------------------------------------
    // Convenience methods
    // -------------------------------------------------------------------------

    /**
     * Returns true when this action represents a successfully created PR.
     */
    public boolean hasCreatedPr() {
        return status == AutoFixStatus.CREATED;
    }

    /**
     * Returns a human-readable label for the current status.
     */
    @NonNull
    public String getStatusDisplayName() {
        if (status == null) {
            return "Unknown";
        }
        return switch (status) {
            case CREATED -> "PR Created";
            case FAILED -> "Failed";
            case NOT_APPLICABLE -> "Not Applicable";
            case SKIPPED_LOW_CONFIDENCE -> "Skipped (Low Confidence)";
            case SKIPPED_PATH_NOT_ALLOWED -> "Skipped (Path Not Allowed)";
            case TIMED_OUT -> "Timed Out";
        };
    }

    /**
     * Returns a formatted date/time string for the action timestamp.
     */
    @NonNull
    public String getFormattedTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");
        return sdf.format(new Date(timestamp));
    }
}
