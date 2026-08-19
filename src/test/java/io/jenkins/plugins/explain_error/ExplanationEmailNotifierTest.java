package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Cause;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.User;
import hudson.tasks.Mailer;
import jakarta.mail.internet.InternetAddress;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests for {@link ExplanationEmailNotifier}.
 */
@WithJenkins
class ExplanationEmailNotifierTest {

    @Test
    void emailOnFailureIsDisabledByDefault(JenkinsRule jenkins) {
        assertFalse(GlobalConfigurationImpl.get().isEnableEmailOnFailure(),
                "Email on failure must be disabled by default");
    }

    @Test
    void collectsTriggeringUserAddress(JenkinsRule jenkins) throws Exception {
        User alice = User.getById("alice", true);
        alice.addProperty(new Mailer.UserProperty("alice@example.com"));

        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new hudson.tasks.Shell("exit 1"));
        FreeStyleBuild build = (FreeStyleBuild) jenkins.assertBuildStatus(
                Result.FAILURE,
                project.scheduleBuild2(0, new Cause.UserIdCause("alice")).get());

        Set<String> addresses = new ExplanationEmailNotifier().collectRecipients(build).stream()
                .map(InternetAddress::getAddress)
                .collect(Collectors.toSet());

        assertTrue(addresses.contains("alice@example.com"),
                "Triggering user's email should be a recipient");
    }

    @Test
    void skipsUsersWithoutResolvableAddress(JenkinsRule jenkins) throws Exception {
        User bob = User.getById("bob", true);

        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new hudson.tasks.Shell("exit 1"));
        FreeStyleBuild build = (FreeStyleBuild) jenkins.assertBuildStatus(
                Result.FAILURE,
                project.scheduleBuild2(0, new Cause.UserIdCause("bob")).get());

        Set<InternetAddress> addresses = new ExplanationEmailNotifier().collectRecipients(build);

        assertEquals(0, addresses.size(),
                "A user without a resolvable email address must not be a recipient");
    }

    @Test
    void includesFixedConfiguredRecipients(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl.get().setEmailRecipients("team@example.com, lead@example.com");

        FreeStyleProject project = jenkins.createFreeStyleProject();
        project.getBuildersList().add(new hudson.tasks.Shell("exit 1"));
        FreeStyleBuild build = (FreeStyleBuild) jenkins.assertBuildStatus(
                Result.FAILURE, project.scheduleBuild2(0).get());

        Set<String> addresses = new ExplanationEmailNotifier().collectRecipients(build).stream()
                .map(InternetAddress::getAddress)
                .collect(Collectors.toSet());

        assertTrue(addresses.contains("team@example.com"), "Fixed recipient should be included");
        assertTrue(addresses.contains("lead@example.com"), "Fixed recipient should be included");
    }
}
