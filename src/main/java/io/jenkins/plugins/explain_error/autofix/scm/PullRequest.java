package io.jenkins.plugins.explain_error.autofix.scm;

public record PullRequest(
        int number,
        String url,
        String headBranch,
        String baseBranch) {}
