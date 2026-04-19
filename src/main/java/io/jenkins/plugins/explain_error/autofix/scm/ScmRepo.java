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

    // Bitbucket Server HTTPS clone URL: https://host/scm/PROJECT/repo.git
    private static final Pattern BB_SERVER_HTTPS_PATTERN =
            Pattern.compile("https?://[^/]+/scm/([^/]+)/(.+?)(?:\\.git)?$");

    // Bitbucket Server SSH clone URL: ssh://git@host:7999/PROJECT/repo.git
    private static final Pattern BB_SERVER_SSH_PATTERN =
            Pattern.compile("ssh://[^@]+@[^:/]+(?::\\d+)?/([^/]+)/(.+?)(?:\\.git)?$");

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

        Matcher bbServerSshMatcher = BB_SERVER_SSH_PATTERN.matcher(url);
        Matcher bbServerHttpsMatcher = BB_SERVER_HTTPS_PATTERN.matcher(url);
        Matcher sshMatcher = SSH_PATTERN.matcher(url);
        Matcher httpsMatcher = HTTPS_PATTERN.matcher(url);

        // Bitbucket Server SSH: ssh://git@host:7999/PROJECT/repo.git
        if (bbServerSshMatcher.matches()) {
            String baseUrl = extractBitbucketServerBaseUrl(url) + "/rest/api/1.0";
            return new ScmRepo(ScmType.BITBUCKET_SERVER, baseUrl,
                    bbServerSshMatcher.group(1), bbServerSshMatcher.group(2), token);
        }

        // Bitbucket Server HTTPS: https://host/scm/PROJECT/repo.git
        if (bbServerHttpsMatcher.matches()) {
            String baseUrl = extractBitbucketServerBaseUrl(url) + "/rest/api/1.0";
            return new ScmRepo(ScmType.BITBUCKET_SERVER, baseUrl,
                    bbServerHttpsMatcher.group(1), bbServerHttpsMatcher.group(2), token);
        }

        // Standard SSH: git@github.com:owner/repo.git
        if (sshMatcher.matches()) {
            String host = sshMatcher.group(1).toLowerCase();
            String owner = sshMatcher.group(2);
            String repoName = sshMatcher.group(3);
            return new ScmRepo(detectType(host, remoteUrl), detectBaseUrl(host), owner, repoName, token);
        }

        // Standard HTTPS: https://github.com/owner/repo.git
        if (httpsMatcher.matches()) {
            String host = httpsMatcher.group(1).toLowerCase();
            String owner = httpsMatcher.group(2);
            String repoName = httpsMatcher.group(3);
            return new ScmRepo(detectType(host, remoteUrl), detectBaseUrl(host), owner, repoName, token);
        }

        throw new IllegalArgumentException("Cannot parse remote URL: " + remoteUrl);
    }

    private static ScmType detectType(String host, String remoteUrl) {
        if (host.contains("github.com")) return ScmType.GITHUB;
        if (host.contains("gitlab.com")) return ScmType.GITLAB;
        if (host.contains("bitbucket.org")) return ScmType.BITBUCKET;
        throw new IllegalArgumentException(
                "Cannot detect SCM type from host '" + host + "' in URL: " + remoteUrl);
    }

    private static String detectBaseUrl(String host) {
        if (host.contains("github.com")) return "https://api.github.com";
        if (host.contains("gitlab.com")) return "https://gitlab.com/api/v4";
        return "https://api.bitbucket.org/2.0";
    }

    /**
     * Parses owner/repoName from a remote URL and constructs a ScmRepo with an explicit
     * ScmType and baseUrl. Used when the hostname is not a known public service (self-hosted
     * instances) and the caller already knows the SCM type via {@code scmTypeOverride}.
     *
     * @param remoteUrl the remote URL (SSH or HTTPS format) — used to extract owner/repoName
     * @param token     the authentication token (plaintext)
     * @param scmType   the SCM type to use (bypasses host-based auto-detection)
     * @param baseUrl   the API base URL to use
     * @return a populated ScmRepo with the overridden type and baseUrl
     * @throws IllegalArgumentException if the URL cannot be parsed
     */
    public static ScmRepo parseWithOverride(String remoteUrl, String token, ScmType scmType, String baseUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            throw new IllegalArgumentException("Remote URL must not be null or blank");
        }
        String url = remoteUrl.trim();
        Matcher bbServerSshMatcher = BB_SERVER_SSH_PATTERN.matcher(url);
        Matcher bbServerHttpsMatcher = BB_SERVER_HTTPS_PATTERN.matcher(url);
        Matcher sshMatcher = SSH_PATTERN.matcher(url);
        Matcher httpsMatcher = HTTPS_PATTERN.matcher(url);
        String owner;
        String repoName;
        if (bbServerSshMatcher.matches()) {
            owner = bbServerSshMatcher.group(1);
            repoName = bbServerSshMatcher.group(2);
        } else if (bbServerHttpsMatcher.matches()) {
            owner = bbServerHttpsMatcher.group(1);
            repoName = bbServerHttpsMatcher.group(2);
        } else if (sshMatcher.matches()) {
            owner = sshMatcher.group(2);
            repoName = sshMatcher.group(3);
        } else if (httpsMatcher.matches()) {
            owner = httpsMatcher.group(2);
            repoName = httpsMatcher.group(3);
        } else {
            throw new IllegalArgumentException("Cannot parse owner/repo from remote URL: " + remoteUrl);
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

    /**
     * Overrides the record-generated toString() to redact the token so it is never
     * accidentally printed in build logs or exception stack traces.
     */
    @Override
    public String toString() {
        return "ScmRepo[scmType=" + scmType + ", baseUrl=" + baseUrl
                + ", owner=" + owner + ", repoName=" + repoName + ", token=[REDACTED]]";
    }

    // -------------------------------------------------------------------------
    // Private URL parsing helpers
    // -------------------------------------------------------------------------

    private static String extractBitbucketServerBaseUrl(String url) {
        if (url.startsWith("ssh://")) {
            // ssh://git@host:7999/PROJECT/repo.git → https://host
            java.util.regex.Matcher m = Pattern.compile("ssh://[^@]+@([^:/]+)").matcher(url);
            return m.find() ? "https://" + m.group(1) : "";
        }
        // https://host/scm/PROJECT/repo.git → https://host
        java.util.regex.Matcher m = Pattern.compile("(https?://[^/]+)").matcher(url);
        return m.find() ? m.group(1) : "";
    }
}
