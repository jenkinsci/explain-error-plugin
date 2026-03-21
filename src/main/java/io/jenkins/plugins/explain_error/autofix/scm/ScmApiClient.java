package io.jenkins.plugins.explain_error.autofix.scm;

import java.io.IOException;
import java.util.Map;

public interface ScmApiClient {

    /**
     * Returns the default branch name (e.g., "main", "master").
     *
     * @return the default branch name
     * @throws IOException if the API call fails
     */
    String getDefaultBranch() throws IOException;

    /**
     * Creates a new branch from the given base branch.
     *
     * @param branchName the name of the new branch
     * @param fromBranch the branch to create from
     * @throws IOException if the API call fails
     */
    void createBranch(String branchName, String fromBranch) throws IOException;

    /**
     * Returns the current file content as a UTF-8 string, or null if the file does not exist.
     *
     * @param filePath the file path relative to repository root
     * @param branch   the branch to read from
     * @return file content as a string, or null if not found
     * @throws IOException if the API call fails for a reason other than 404
     */
    String getFileContent(String filePath, String branch) throws IOException;

    /**
     * Atomically commits multiple file changes to the given branch.
     *
     * @param branchName   the branch to commit to
     * @param commitMessage the commit message
     * @param fileContents  map of filePath to new complete file content (not diffs)
     * @throws IOException if the API call fails
     */
    void commitFiles(String branchName, String commitMessage, Map<String, String> fileContents) throws IOException;

    /**
     * Creates a pull request and returns the created PR.
     *
     * @param title      the pull request title
     * @param body       the pull request description body
     * @param headBranch the source branch
     * @param baseBranch the target branch
     * @param draft      whether to create as a draft PR
     * @return the created PullRequest
     * @throws IOException if the API call fails
     */
    PullRequest createPullRequest(String title, String body, String headBranch, String baseBranch, boolean draft)
            throws IOException;

    /**
     * Deletes a branch (for cleanup/rollback).
     *
     * @param branchName the branch to delete
     * @throws IOException if the API call fails
     */
    void deleteBranch(String branchName) throws IOException;

    /**
     * Validates that the token has write access to the repository.
     *
     * @throws IOException with a clear message if the token does not have push/write access
     */
    void validateWriteAccess() throws IOException;
}
