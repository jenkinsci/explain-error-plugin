package io.jenkins.plugins.explain_error.autofix;

import io.jenkins.plugins.explain_error.autofix.scm.ScmRepo;
import io.jenkins.plugins.explain_error.autofix.scm.ScmType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScmRepoTest {

    // -----------------------------------------------------------------------
    // ScmRepo.parse() — happy paths
    // -----------------------------------------------------------------------

    @Test
    void parse_githubSsh_detectsGitHub() {
        ScmRepo repo = ScmRepo.parse("git@github.com:acme/my-repo.git", "tok");
        assertEquals(ScmType.GITHUB, repo.scmType());
        assertEquals("https://api.github.com", repo.baseUrl());
        assertEquals("acme", repo.owner());
        assertEquals("my-repo", repo.repoName());
    }

    @Test
    void parse_githubHttps_detectsGitHub() {
        ScmRepo repo = ScmRepo.parse("https://github.com/acme/my-repo.git", "tok");
        assertEquals(ScmType.GITHUB, repo.scmType());
        assertEquals("acme", repo.owner());
        assertEquals("my-repo", repo.repoName());
    }

    @Test
    void parse_gitlabSsh_detectsGitLab() {
        ScmRepo repo = ScmRepo.parse("git@gitlab.com:acme/my-repo.git", "tok");
        assertEquals(ScmType.GITLAB, repo.scmType());
        assertEquals("https://gitlab.com/api/v4", repo.baseUrl());
    }

    @Test
    void parse_bitbucketHttps_detectsBitbucket() {
        ScmRepo repo = ScmRepo.parse("https://bitbucket.org/acme/my-repo.git", "tok");
        assertEquals(ScmType.BITBUCKET, repo.scmType());
        assertEquals("https://api.bitbucket.org/2.0", repo.baseUrl());
    }

    @Test
    void parse_unknownHost_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> ScmRepo.parse("git@git.company.com:acme/my-repo.git", "tok"));
    }

    @Test
    void parse_nullUrl_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> ScmRepo.parse(null, "tok"));
    }

    @Test
    void parse_blankUrl_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> ScmRepo.parse("   ", "tok"));
    }

    // -----------------------------------------------------------------------
    // ScmRepo.parseWithOverride() — happy paths
    // -----------------------------------------------------------------------

    @Test
    void parseWithOverride_sshUrl_extractsOwnerAndRepo() {
        ScmRepo repo = ScmRepo.parseWithOverride(
                "git@git.company.com:acme/my-repo.git", "tok",
                ScmType.GITLAB, "https://git.company.com/api/v4");
        assertEquals(ScmType.GITLAB, repo.scmType());
        assertEquals("https://git.company.com/api/v4", repo.baseUrl());
        assertEquals("acme", repo.owner());
        assertEquals("my-repo", repo.repoName());
    }

    @Test
    void parseWithOverride_httpsUrl_extractsOwnerAndRepo() {
        ScmRepo repo = ScmRepo.parseWithOverride(
                "https://github.enterprise.acme.com/acme/my-repo.git", "tok",
                ScmType.GITHUB, "https://github.enterprise.acme.com/api/v3");
        assertEquals(ScmType.GITHUB, repo.scmType());
        assertEquals("acme", repo.owner());
        assertEquals("my-repo", repo.repoName());
    }

    @Test
    void parseWithOverride_malformedUrl_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> ScmRepo.parseWithOverride("not-a-url", "tok",
                        ScmType.GITHUB, "https://api.github.com"));
    }

    @Test
    void parseWithOverride_nullUrl_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> ScmRepo.parseWithOverride(null, "tok",
                        ScmType.GITLAB, "https://gitlab.com/api/v4"));
    }

    // -----------------------------------------------------------------------
    // Bitbucket Server URL parsing
    // -----------------------------------------------------------------------

    @Test
    void parse_bitbucketServerSsh_detectsServerType() {
        ScmRepo repo = ScmRepo.parse("ssh://git@bitbucket.company.com:7999/PROJ/my-repo.git", "tok");
        assertEquals(ScmType.BITBUCKET_SERVER, repo.scmType());
        assertEquals("https://bitbucket.company.com/rest/api/1.0", repo.baseUrl());
        assertEquals("PROJ", repo.owner());
        assertEquals("my-repo", repo.repoName());
    }

    @Test
    void parse_bitbucketServerHttps_detectsServerType() {
        ScmRepo repo = ScmRepo.parse("https://bitbucket.company.com/scm/PROJ/my-repo.git", "tok");
        assertEquals(ScmType.BITBUCKET_SERVER, repo.scmType());
        assertEquals("https://bitbucket.company.com/rest/api/1.0", repo.baseUrl());
        assertEquals("PROJ", repo.owner());
        assertEquals("my-repo", repo.repoName());
    }

    @Test
    void parse_bitbucketServerSsh_withoutDotGit_detectsServerType() {
        ScmRepo repo = ScmRepo.parse("ssh://git@bitbucket.company.com:7999/TEAM/service", "tok");
        assertEquals(ScmType.BITBUCKET_SERVER, repo.scmType());
        assertEquals("TEAM", repo.owner());
        assertEquals("service", repo.repoName());
    }

    @Test
    void parse_bitbucketServerHttps_withoutDotGit_detectsServerType() {
        ScmRepo repo = ScmRepo.parse("https://bitbucket.company.com/scm/TEAM/service", "tok");
        assertEquals(ScmType.BITBUCKET_SERVER, repo.scmType());
        assertEquals("TEAM", repo.owner());
        assertEquals("service", repo.repoName());
    }

    @Test
    void parseWithOverride_bitbucketServerSshUrl_extractsProjectAndRepo() {
        ScmRepo repo = ScmRepo.parseWithOverride(
                "ssh://git@bitbucket.company.com:7999/PROJ/my-repo.git", "tok",
                ScmType.BITBUCKET_SERVER, "https://bitbucket.company.com/rest/api/1.0");
        assertEquals(ScmType.BITBUCKET_SERVER, repo.scmType());
        assertEquals("https://bitbucket.company.com/rest/api/1.0", repo.baseUrl());
        assertEquals("PROJ", repo.owner());
        assertEquals("my-repo", repo.repoName());
    }

    @Test
    void parseWithOverride_bitbucketServerHttpsUrl_extractsProjectAndRepo() {
        ScmRepo repo = ScmRepo.parseWithOverride(
                "https://bitbucket.company.com/scm/PROJ/my-repo.git", "tok",
                ScmType.BITBUCKET_SERVER, "https://bitbucket.company.com/rest/api/1.0");
        assertEquals(ScmType.BITBUCKET_SERVER, repo.scmType());
        assertEquals("PROJ", repo.owner());
        assertEquals("my-repo", repo.repoName());
    }

    // -----------------------------------------------------------------------
    // ScmRepo.withBaseUrl()
    // -----------------------------------------------------------------------

    @Test
    void withBaseUrl_returnsNewRepoWithUpdatedBaseUrl() {
        ScmRepo repo = ScmRepo.parse("git@github.com:acme/my-repo.git", "tok");
        ScmRepo updated = repo.withBaseUrl("https://github.enterprise.acme.com/api/v3");
        assertEquals("https://github.enterprise.acme.com/api/v3", updated.baseUrl());
        // All other fields unchanged
        assertEquals(repo.scmType(), updated.scmType());
        assertEquals(repo.owner(), updated.owner());
        assertEquals(repo.repoName(), updated.repoName());
    }

    // -----------------------------------------------------------------------
    // ScmRepo.toString() — token must be redacted
    // -----------------------------------------------------------------------

    @Test
    void toString_redactsToken() {
        ScmRepo repo = ScmRepo.parse("git@github.com:acme/my-repo.git", "super-secret-token");
        String str = repo.toString();
        assertFalse(str.contains("super-secret-token"), "Token must not appear in toString()");
        assertTrue(str.contains("[REDACTED]"), "toString() must contain [REDACTED]");
    }
}
