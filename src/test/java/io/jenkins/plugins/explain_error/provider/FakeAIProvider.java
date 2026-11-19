package io.jenkins.plugins.explain_error.provider;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.util.Secret;
import io.jenkins.plugins.explain_error.JenkinsLogAnalysis;
import java.util.List;

import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

public class FakeAIProvider extends OpenAIProvider {

    private boolean throwError = false;
    private JenkinsLogAnalysis answerMessage = new JenkinsLogAnalysis(
        "Request was successful", null, null, null);
    private int callCount = 0;
    private String providerName = "Test";
    
    // Captured parameters from last analyzeLogs call
    private String lastErrorLogs;
    private String lastLanguage;
    private String lastCustomContext;
    private Double lastTemperature;

    private List<String> selectedWorkspaceFiles = List.of();
    private boolean throwOnSelection = false;
    private String lastSelectionErrorLogs;
    private int selectionCallCount;

    @DataBoundConstructor
    public FakeAIProvider() {
        super("https://localhost:1234", "test-model", Secret.fromString("test-api-key"));
    }

    @Override
    public Assistant createAssistant(@CheckForNull hudson.model.Item item,
                                     @CheckForNull org.springframework.security.core.Authentication authentication,
                                     @CheckForNull Double temperature) {
        return new Assistant() {
            @Override
            public JenkinsLogAnalysis analyzeLogs(String errorLogs, String language, String customContext) {
                if (throwError) {
                    throw new RuntimeException("Request failed.");
                }
                // Capture parameters for test verification
                lastErrorLogs = errorLogs;
                lastLanguage = language;
                lastCustomContext = customContext;
                lastTemperature = temperature;
                countCall();
                return answerMessage;
            }
        };
    }

    @Override
    public WorkspaceFileSelector createWorkspaceFileSelector(@CheckForNull hudson.model.Item item,
                                                             @CheckForNull org.springframework.security.core.Authentication authentication) {
        return errorLogs -> {
            lastSelectionErrorLogs = errorLogs;
            countSelectionCall();
            if (throwOnSelection) {
                throw new RuntimeException("Selection failed.");
            }
            return new WorkspaceFileSelection(selectedWorkspaceFiles);
        };
    }

    public void setSelectedWorkspaceFiles(List<String> selectedWorkspaceFiles) {
        this.selectedWorkspaceFiles = selectedWorkspaceFiles == null ? List.of() : List.copyOf(selectedWorkspaceFiles);
    }

    public void setThrowOnSelection(boolean throwOnSelection) {
        this.throwOnSelection = throwOnSelection;
    }

    public synchronized int getSelectionCallCount() {
        return selectionCallCount;
    }

    private synchronized void countSelectionCall() {
        selectionCallCount++;
    }

    public String getLastSelectionErrorLogs() {
        return lastSelectionErrorLogs;
    }

    public void setThrowError(boolean throwError) {
        this.throwError = throwError;
    }

    public void setApiKey(Secret apiKey) {
        this.apiKey = apiKey;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public void setAnswerMessage(String answerMessage) {
        this.answerMessage = new JenkinsLogAnalysis(answerMessage, null, null, null);
    }

    public void setAnswer(JenkinsLogAnalysis answer) {
        this.answerMessage = answer;
    }

    public synchronized int getCallCount() {
        return callCount;
    }

    /** Several automatic explanations can be with the provider at once, so the count must not lose any. */
    private synchronized void countCall() {
        callCount++;
    }
    
    public String getLastErrorLogs() {
        return lastErrorLogs;
    }
    
    public String getLastLanguage() {
        return lastLanguage;
    }
    
    public String getLastCustomContext() {
        return lastCustomContext;
    }

    public Double getLastTemperature() {
        return lastTemperature;
    }

    public void setProviderName(String providerName) {
        this.providerName = providerName;
    }

    @Override
    public String getProviderName() {
        return providerName;
    }

    @Extension
    @Symbol("test")
    public static class DescriptorImpl extends BaseProviderDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Test";
        }

        public String getDefaultModel() {
            return "test-model";
        }
    }
}
