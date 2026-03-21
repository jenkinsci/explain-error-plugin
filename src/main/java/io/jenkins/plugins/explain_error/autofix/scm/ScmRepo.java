package io.jenkins.plugins.explain_error.autofix.scm;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record ScmRepo(ScmType scmType, String baseUrl, String owner, String repoName, String token) {

    // SSH: git@github.com:owner/repo.git
    private static final Pattern SSH_PATTERN =
            Pattern.compile("git@([^:]+):([^/]+)/(.+?)(?:\\.git)?$");

    // HTTPS: https://github.com/owner/repo.git or http://...
    private static final Pattern HTTPS_PATTERN =
            Pattern.compile("https?://(?:[^@]+@)?([^/]+)/([^/]+)/(.+?)(?:\\.git)?$");

    /**
     * Parses a remote URL (SSH or HTTPS) and detects the SCM type, owner, and repo name.
     *
     * @param remoteUrl the remote URL (SSH or HTTPS format)
     * @param token     the authentication token (plaintext)
     * @return a populated ScmRepo
     * @throws IllegalArgumentException if the URL cannot be parsed or the SCM type cannot be determined
     */
    public static ScmRepo parse(String remoteUrl, String token) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            throw new IllegalArgumentException("Remote URL must not be null or blank");
        }

        String url = remoteUrl.trim();
        String host;
        String owner;
        String repoName;

        Matcher sshMatcher = SSH_PATTERN.matcher(url);
        Matcher httpsMatcher = HTTPS_PATTERN.matcher(url);

        if (sshMatcher.matches()) {
            host = sshMatcher.group(1).toLowerCase();
            owner = sshMatcher.group(2);
            repoName = sshMatcher.group(3);
        } else if (httpsMatcher.matches()) {
            host = httpsMatcher.group(1).toLowerCase();
            owner = httpsMatcher.group(2);
            repoName = httpsMatcher.group(3);
        } else {
            throw new IllegalArgumentException("Cannot parse remote URL: " + remoteUrl);
        }

        ScmType scmType;
        String baseUrl;

        if (host.contains("github.com")) {
            scmType = ScmType.GITHUB;
            baseUrl = "https://api.github.com";
        } else if (host.contains("gitlab.com")) {
            scmType = ScmType.GITLAB;
            baseUrl = "https://gitlab.com/api/v4";
        } else if (host.contains("bitbucket.org")) {
            scmType = ScmType.BITBUCKET;
            baseUrl = "https://api.bitbucket.org/2.0";
        } else {
            throw new IllegalArgumentException(
                    "Cannot detect SCM type from host '" + host + "' in URL: " + remoteUrl);
        }

        return new ScmRepo(scmType, baseUrl, owner, repoName, token);
    }

    /**
     * Returns a new ScmRepo with the baseUrl overridden (for enterprise instances).
     *
     * @param baseUrl the new API base URL
     * @return a new ScmRepo with the updated baseUrl
     */
    public ScmRepo withBaseUrl(String baseUrl) {
        return new ScmRepo(this.scmType, baseUrl, this.owner, this.repoName, this.token);
    }
}
