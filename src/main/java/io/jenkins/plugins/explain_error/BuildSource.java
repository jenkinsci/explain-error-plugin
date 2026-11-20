package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.Revision;
import hudson.plugins.git.util.BuildData;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;

/**
 * Where a build's code came from, as far as Jenkins knows: the repository, branch and commit it checked out, and
 * the pull request it was built for. A coding agent needs these to look at the same code.
 *
 * @param repository   the remote URL of the repository
 * @param branch       the branch: the source branch of a pull request, or else the branch built
 * @param commit       the commit built
 * @param changeUrl    the pull request, merge request or change built, if any
 * @param changeTarget the branch a pull request goes into
 */
record BuildSource(@CheckForNull String repository, @CheckForNull String branch, @CheckForNull String commit,
                   @CheckForNull String changeUrl, @CheckForNull String changeTarget) {

    private static final Logger LOGGER = Logger.getLogger(BuildSource.class.getName());

    /** What Git puts before a branch name: the remote's refs, or a local ref. */
    private static final Pattern REMOTE_PREFIX =
            Pattern.compile("^(?:refs/remotes/[^/]+/|remotes/[^/]+/|refs/heads/|origin/)");

    /**
     * Reads the variables Jenkins gives the build: those of the Git plugin ({@code GIT_URL}, {@code GIT_COMMIT},
     * {@code GIT_BRANCH}) and of a multibranch project ({@code BRANCH_NAME}, {@code CHANGE_URL}...). What they do
     * not say is taken from the last checkout the Git plugin recorded, when it is installed.
     */
    @NonNull
    static BuildSource of(@NonNull Run<?, ?> run) {
        Map<String, String> env;
        try {
            env = run.getEnvironment(TaskListener.NULL);
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Could not read the environment of " + run.getExternalizableId(), e);
            env = Map.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            env = Map.of();
        }
        String repository = blankToNull(env.get("GIT_URL"));
        String commit = blankToNull(env.get("GIT_COMMIT"));
        String branch = firstOf(env.get("CHANGE_BRANCH"), env.get("GIT_LOCAL_BRANCH"), env.get("BRANCH_NAME"),
                withoutRemote(env.get("GIT_BRANCH")));
        if ((repository == null || commit == null || branch == null) && Jenkins.get().getPlugin("git") != null) {
            BuildSource checkout = GitCheckout.of(run);
            if (checkout != null) {
                repository = firstOf(repository, checkout.repository());
                commit = firstOf(commit, checkout.commit());
                branch = firstOf(branch, checkout.branch());
            }
        }
        return new BuildSource(repository, branch, commit, blankToNull(env.get("CHANGE_URL")),
                blankToNull(env.get("CHANGE_TARGET")));
    }

    /** Whether nothing is known of where the code came from. */
    boolean isEmpty() {
        return repository == null && branch == null && commit == null && changeUrl == null;
    }

    @CheckForNull
    private static String firstOf(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @CheckForNull
    private static String blankToNull(@CheckForNull String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** {@code origin/main}, {@code refs/remotes/origin/main} and {@code refs/heads/main} are the branch main. */
    @CheckForNull
    static String withoutRemote(@CheckForNull String branch) {
        if (branch == null) {
            return null;
        }
        return REMOTE_PREFIX.matcher(branch).replaceFirst("");
    }

    /** The Git plugin's record of a checkout. Only loaded when the Git plugin is installed. */
    private static final class GitCheckout {

        private GitCheckout() {
        }

        /** The last checkout recorded: in a Pipeline, the job's own code comes after its libraries. */
        @CheckForNull
        static BuildSource of(Run<?, ?> run) {
            List<BuildData> checkouts = run.getActions(BuildData.class);
            if (checkouts.isEmpty()) {
                return null;
            }
            BuildData last = checkouts.get(checkouts.size() - 1);
            String repository = last.getRemoteUrls().stream().findFirst().orElse(null);
            Revision revision = last.getLastBuiltRevision();
            String commit = revision != null ? revision.getSha1String() : null;
            String branch = revision != null
                    ? revision.getBranches().stream().map(Branch::getName).findFirst().map(BuildSource::withoutRemote)
                            .orElse(null)
                    : null;
            return new BuildSource(repository, branch, commit, null, null);
        }
    }
}
