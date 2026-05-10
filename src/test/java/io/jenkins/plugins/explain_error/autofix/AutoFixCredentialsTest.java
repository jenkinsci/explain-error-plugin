package io.jenkins.plugins.explain_error.autofix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.util.Secret;
import java.io.IOException;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class AutoFixCredentialsTest {

    @Test
    void resolveScmToken_supportsSecretTextCredentials(JenkinsRule jenkins) throws Exception {
        addStringCredential("secret-text-pat", "secret-text-token");
        FreeStyleBuild run = createRun(jenkins);

        String token = new AutoFixOrchestrator().resolveScmToken("secret-text-pat", run);

        assertEquals("secret-text-token", token);
    }

    @Test
    void resolveScmToken_supportsUsernamePasswordCredentials(JenkinsRule jenkins) throws Exception {
        addUsernamePasswordCredential("username-password-pat", "gitlab-user", "username-password-token");
        FreeStyleBuild run = createRun(jenkins);

        String token = new AutoFixOrchestrator().resolveScmToken("username-password-pat", run);

        assertEquals("username-password-token", token);
    }

    @Test
    void resolveScmToken_missingCredential_returnsNull(JenkinsRule jenkins) throws Exception {
        FreeStyleBuild run = createRun(jenkins);

        String token = new AutoFixOrchestrator().resolveScmToken("missing-pat", run);

        assertNull(token);
    }

    private FreeStyleBuild createRun(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject();
        return jenkins.buildAndAssertSuccess(project);
    }

    private void addStringCredential(String id, String secret) throws IOException {
        StringCredentialsImpl credentials = new StringCredentialsImpl(
                CredentialsScope.GLOBAL,
                id,
                "test credential",
                Secret.fromString(secret));
        SystemCredentialsProvider.getInstance().getCredentials().add(credentials);
        SystemCredentialsProvider.getInstance().save();
    }

    private void addUsernamePasswordCredential(String id, String username, String password) throws Exception {
        UsernamePasswordCredentialsImpl credentials = new UsernamePasswordCredentialsImpl(
                CredentialsScope.GLOBAL,
                id,
                "test credential",
                username,
                password);
        SystemCredentialsProvider.getInstance().getCredentials().add(credentials);
        SystemCredentialsProvider.getInstance().save();
    }
}
