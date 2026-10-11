package io.jenkins.plugins.explain_error.autofix;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Run;
import jenkins.model.RunAction2;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Jenkins {@link RunAction2} that stores auto-fix results and provides
 * a UI page under the build's action sidebar.
 */
public class AutoFixAction implements RunAction2 {

    /** Pull request pages on GitHub, GitLab and Bitbucket: the repository, then what marks a pull request. */
    private static final Pattern PULL_REQUEST_URL =
            Pattern.compile("(https?://.+?)(/pull/|/-/merge_requests/|/pull-requests/)\\d+/?");
    private static final Pattern BITBUCKET_SERVER_REPOSITORY = Pattern.compile(".+/projects/[^/]+/repos/[^/]+");

    private final AutoFixStatus status;
    private final String prUrl;       // nullable
    private final String branchName;  // nullable
    private final String message;
    private final String prTitle;     // nullable
    private final long timestamp;
    private final String scmType;     // "GitHub", "GitLab", "Bitbucket", nullable

    // What the AI proposed, kept when no pull request carries it. All nullable.
    private String suggestedTitle;
    private String suggestedExplanation;
    private String suggestedConfidence;
    private String suggestedFixType;
    private List<ProposedChange> proposedChanges;

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
    // The proposed fix
    // -------------------------------------------------------------------------

    /**
     * Keeps what the AI proposed, so that a fix that did not become a pull request can still
     * be read and applied by hand.
     */
    public void setSuggestion(@CheckForNull FixSuggestion suggestion) {
        if (suggestion == null) {
            return;
        }
        this.suggestedTitle = blankToNull(suggestion.title());
        this.suggestedExplanation = blankToNull(suggestion.explanation());
        this.suggestedConfidence = blankToNull(suggestion.confidence());
        this.suggestedFixType = blankToNull(suggestion.fixType());
        List<ProposedChange> changes = new ArrayList<>();
        if (suggestion.changes() != null) {
            for (FixSuggestion.FileChange change : suggestion.changes()) {
                if (change != null && change.filePath() != null) {
                    changes.add(new ProposedChange(
                            change.filePath(), change.action(), change.description(), change.unifiedDiff()));
                }
            }
        }
        this.proposedChanges = changes.isEmpty() ? null : changes;
    }

    /** Whether there is anything from the AI to show: its reasoning, or changes. */
    public boolean hasSuggestion() {
        return suggestedExplanation != null || hasProposedChanges();
    }

    public boolean hasProposedChanges() {
        return proposedChanges != null && !proposedChanges.isEmpty();
    }

    @NonNull
    public List<ProposedChange> getProposedChanges() {
        return proposedChanges != null ? List.copyOf(proposedChanges) : List.of();
    }

    @CheckForNull
    public String getSuggestedTitle() {
        return suggestedTitle;
    }

    @CheckForNull
    public String getSuggestedExplanation() {
        return suggestedExplanation;
    }

    @CheckForNull
    public String getSuggestedConfidence() {
        return suggestedConfidence;
    }

    @CheckForNull
    public String getSuggestedFixType() {
        return suggestedFixType;
    }

    @CheckForNull
    private static String blankToNull(@CheckForNull String value) {
        return value == null || value.isBlank() ? null : value.strip();
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
     * Returns the page of the fix branch on the SCM host, worked out from the pull request URL,
     * or {@code null} when the URL is not one of a pull request on a known host.
     */
    @CheckForNull
    public String getBranchUrl() {
        if (prUrl == null || branchName == null || branchName.isBlank()) {
            return null;
        }
        Matcher pullRequest = PULL_REQUEST_URL.matcher(prUrl);
        if (!pullRequest.matches()) {
            return null;
        }
        String repository = pullRequest.group(1);
        return switch (pullRequest.group(2)) {
            case "/pull/" -> repository + "/tree/" + branchName;
            case "/-/merge_requests/" -> repository + "/-/tree/" + branchName;
            // Bitbucket Cloud. Bitbucket Server keeps branches elsewhere, so it gets no link.
            default -> BITBUCKET_SERVER_REPOSITORY.matcher(repository).matches()
                    ? null : repository + "/branch/" + branchName;
        };
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

    /**
     * One file the AI proposed to change.
     */
    public static final class ProposedChange {

        private final String filePath;
        private final String action;       // "modify" or "create", nullable
        private final String description;  // nullable
        private final String unifiedDiff;  // nullable

        ProposedChange(@NonNull String filePath, @CheckForNull String action, @CheckForNull String description,
                       @CheckForNull String unifiedDiff) {
            this.filePath = filePath;
            this.action = action;
            this.description = description;
            this.unifiedDiff = unifiedDiff;
        }

        @NonNull
        public String getFilePath() {
            return filePath;
        }

        @CheckForNull
        public String getAction() {
            return action;
        }

        @CheckForNull
        public String getDescription() {
            return description;
        }

        @NonNull
        public String getUnifiedDiff() {
            return unifiedDiff != null ? unifiedDiff : "";
        }

        /**
         * The diff as a patch for this file: models often leave the two file lines out, and
         * {@code git apply} needs them.
         */
        @NonNull
        public String getPatch() {
            String diff = getUnifiedDiff().strip();
            if (diff.isEmpty()) {
                return "";
            }
            if (!diff.startsWith("--- ") && !diff.startsWith("diff ")) {
                String from = "create".equals(action) ? "/dev/null" : "a/" + filePath;
                diff = "--- " + from + "\n+++ b/" + filePath + "\n" + diff;
            }
            return diff + "\n";
        }

        /** The lines of the diff, each with what it is, for the page to colour. */
        @NonNull
        public List<DiffLine> getDiffLines() {
            List<DiffLine> lines = new ArrayList<>();
            for (String line : getUnifiedDiff().strip().split("\r?\n", -1)) {
                lines.add(new DiffLine(line));
            }
            return lines;
        }
    }

    /**
     * A line of a unified diff.
     */
    public static final class DiffLine {

        private final String text;

        DiffLine(@NonNull String text) {
            this.text = text;
        }

        @NonNull
        public String getText() {
            return text;
        }

        /** "file", "hunk", "add", "del" or "context". */
        @NonNull
        public String getKind() {
            if (text.startsWith("+++ ") || text.startsWith("--- ")) {
                return "file";
            }
            if (text.startsWith("@@")) {
                return "hunk";
            }
            if (text.startsWith("+")) {
                return "add";
            }
            if (text.startsWith("-")) {
                return "del";
            }
            return "context";
        }
    }
}
