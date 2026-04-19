package io.jenkins.plugins.explain_error.autofix.scm;

public class ScmClientFactory {

    private ScmClientFactory() {}

    public static ScmApiClient create(ScmRepo repo) {
        return switch (repo.scmType()) {
            case GITHUB -> new GitHubApiClient(repo);
            case GITLAB -> new GitLabApiClient(repo);
            case BITBUCKET -> new BitbucketApiClient(repo);
            case BITBUCKET_SERVER -> new BitbucketServerApiClient(repo);
        };
    }
}
