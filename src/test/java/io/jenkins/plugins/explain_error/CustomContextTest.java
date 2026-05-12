package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.Result;
import io.jenkins.plugins.explain_error.provider.TestProvider;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class CustomContextTest {

    @Test
    void testGlobalCustomContext(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        TestProvider provider = new TestProvider();
        config.setAiProvider(provider);
        config.setEnableExplanation(true);
        config.setCustomContext("Global custom instructions");

        // Create a test pipeline job
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-global-context");

        String pipelineScript = "node {\n"
                + "    explainError()\n"
                + "}";

        job.setDefinition(new CpsFlowDefinition(pipelineScript, true));

        // Run the job
        WorkflowRun run = jenkins.assertBuildStatus(hudson.model.Result.SUCCESS, job.scheduleBuild2(0));
        ErrorExplanationAction action = run.getAction(ErrorExplanationAction.class);
        assertNotNull(action);
        
        // Verify custom context was actually passed to the AI provider
        assertNotNull(provider.getLastCustomContext(), "Custom context should have been passed to AI provider");
        assertEquals("\n\nIMPORTANT - ADDITIONAL INSTRUCTIONS (You MUST address these in your response):\nGlobal custom instructions", 
                     provider.getLastCustomContext(), 
                     "Global custom context should be passed to AI provider with proper formatting");
    }

    @Test
    void testStepLevelCustomContextOverridesGlobal(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        TestProvider provider = new TestProvider();
        config.setAiProvider(provider);
        config.setEnableExplanation(true);
        config.setCustomContext("Global custom instructions");

        // Create a test pipeline job
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-step-context");

        String pipelineScript = "node {\n"
                + "    explainError(customContext: 'Step-level custom context')\n"
                + "}";

        job.setDefinition(new CpsFlowDefinition(pipelineScript, true));

        // Run the job
        WorkflowRun run = jenkins.assertBuildStatus(hudson.model.Result.SUCCESS, job.scheduleBuild2(0));
        ErrorExplanationAction action = run.getAction(ErrorExplanationAction.class);
        assertNotNull(action);
        
        // Verify step-level custom context overrides global
        assertNotNull(provider.getLastCustomContext(), "Custom context should have been passed to AI provider");
        assertEquals("\n\nIMPORTANT - ADDITIONAL INSTRUCTIONS (You MUST address these in your response):\nStep-level custom context", 
                     provider.getLastCustomContext(), 
                     "Step-level custom context should override global context");
    }

    @Test
    void testCustomContextWithOtherParameters(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        TestProvider provider = new TestProvider();
        config.setAiProvider(provider);
        config.setEnableExplanation(true);

        // Create a test pipeline job
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-combined-params");

        String pipelineScript = "node {\n"
                + "    explainError(\n"
                + "        maxLines: 200,\n"
                + "        language: 'Spanish',\n"
                + "        customContext: 'This is a payment service. Check PCI compliance.'\n"
                + "    )\n"
                + "}";

        job.setDefinition(new CpsFlowDefinition(pipelineScript, true));

        // Run the job
        WorkflowRun run = jenkins.assertBuildStatus(hudson.model.Result.SUCCESS, job.scheduleBuild2(0));
        ErrorExplanationAction action = run.getAction(ErrorExplanationAction.class);
        assertNotNull(action);
        
        // Verify all parameters were passed correctly
        assertEquals("Spanish", provider.getLastLanguage(), "Language parameter should be passed to AI provider");
        assertEquals("\n\nIMPORTANT - ADDITIONAL INSTRUCTIONS (You MUST address these in your response):\nThis is a payment service. Check PCI compliance.", 
                     provider.getLastCustomContext(), 
                     "Custom context should be passed with other parameters");
    }

    @Test
    void testWorkspaceContextIsAddedToCustomContext(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        TestProvider provider = new TestProvider();
        config.setAiProvider(provider);
        config.setEnableExplanation(true);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-workspace-context");
        String pipelineScript = "node {\n"
                + "    writeFile file: 'pom.xml', text: '<project>workspace-context</project>'\n"
                + "    writeFile file: '.env', text: 'TOKEN=secret'\n"
                + "    explainError(\n"
                + "        includeWorkspaceContext: true,\n"
                + "        workspaceContextPaths: 'pom.xml,.env',\n"
                + "        workspaceContextMaxBytes: 10000\n"
                + "    )\n"
                + "}";

        job.setDefinition(new CpsFlowDefinition(pipelineScript, true));

        WorkflowRun run = jenkins.assertBuildStatus(hudson.model.Result.SUCCESS, job.scheduleBuild2(0));
        ErrorExplanationAction action = run.getAction(ErrorExplanationAction.class);
        assertNotNull(action);

        assertNotNull(provider.getLastCustomContext(), "Workspace context should be passed to AI provider");
        assertTrue(provider.getLastCustomContext().contains("WORKSPACE CONTEXT"));
        assertTrue(provider.getLastCustomContext().contains("### pom.xml"));
        assertTrue(provider.getLastCustomContext().contains("<project>workspace-context</project>"));
        assertFalse(provider.getLastCustomContext().contains("TOKEN=secret"));
    }

    @Test
    void testGlobalLanguageAndTemperature(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        TestProvider provider = new TestProvider();
        config.setAiProvider(provider);
        config.setEnableExplanation(true);
        config.setLanguage("French");
        config.setTemperature(0.8);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-global-language-temperature");
        job.setDefinition(new CpsFlowDefinition("node { explainError() }", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        assertNotNull(run.getAction(ErrorExplanationAction.class));
        assertEquals("French", provider.getLastLanguage());
        assertEquals(0.8, provider.getLastTemperature());
    }

    @Test
    void testFolderSettingsOverrideGlobalForPipelineStep(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        TestProvider provider = new TestProvider();
        config.setAiProvider(provider);
        config.setEnableExplanation(true);
        config.setLanguage("English");
        config.setCustomContext("Global context");
        config.setTemperature(0.1);

        Folder folder = jenkins.jenkins.createProject(Folder.class, "pipeline-folder-settings");
        ExplainErrorFolderProperty folderProperty = new ExplainErrorFolderProperty();
        folderProperty.setLanguage("German");
        folderProperty.setCustomContext("Folder context");
        folderProperty.setTemperature(0.6);
        folder.addProperty(folderProperty);

        WorkflowJob job = folder.createProject(WorkflowJob.class, "test-folder-settings");
        job.setDefinition(new CpsFlowDefinition("node { explainError() }", true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        assertNotNull(run.getAction(ErrorExplanationAction.class));
        assertEquals("German", provider.getLastLanguage());
        assertEquals("\n\nIMPORTANT - ADDITIONAL INSTRUCTIONS (You MUST address these in your response):\nFolder context",
                provider.getLastCustomContext());
        assertEquals(0.6, provider.getLastTemperature());
    }

    @Test
    void testStepSettingsOverrideFolderAndGlobal(JenkinsRule jenkins) throws Exception {
        GlobalConfigurationImpl config = GlobalConfigurationImpl.get();
        TestProvider provider = new TestProvider();
        config.setAiProvider(provider);
        config.setEnableExplanation(true);
        config.setLanguage("English");
        config.setCustomContext("Global context");
        config.setTemperature(0.1);

        Folder folder = jenkins.jenkins.createProject(Folder.class, "pipeline-step-override");
        ExplainErrorFolderProperty folderProperty = new ExplainErrorFolderProperty();
        folderProperty.setLanguage("German");
        folderProperty.setCustomContext("Folder context");
        folderProperty.setTemperature(0.6);
        folder.addProperty(folderProperty);

        WorkflowJob job = folder.createProject(WorkflowJob.class, "test-step-override");
        job.setDefinition(new CpsFlowDefinition(
                "node { explainError(language: 'Spanish', customContext: 'Step context', temperature: 0.2) }",
                true));

        WorkflowRun run = jenkins.assertBuildStatus(Result.SUCCESS, job.scheduleBuild2(0));
        assertNotNull(run.getAction(ErrorExplanationAction.class));
        assertEquals("Spanish", provider.getLastLanguage());
        assertEquals("\n\nIMPORTANT - ADDITIONAL INSTRUCTIONS (You MUST address these in your response):\nStep context",
                provider.getLastCustomContext());
        assertEquals(0.2, provider.getLastTemperature());
    }
}
