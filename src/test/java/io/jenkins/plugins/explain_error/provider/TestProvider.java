package io.jenkins.plugins.explain_error.provider;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.util.Secret;
import io.jenkins.plugins.explain_error.JenkinsLogAnalysis;

import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

public class TestProvider extends OpenAIProvider {

    private boolean throwError = false;
    private JenkinsLogAnalysis answerMessage = new JenkinsLogAnalysis(
        "Request was successful", null, null, null);
    private int callCount = 0;
    private String providerName = "Test";
    
    // Captured parameters from last analyzeLogs call
    private String lastErrorLogs;
    private String lastLanguage;
    private String lastCustomContext;

    @DataBoundConstructor
    public TestProvider() {
        super("https://localhost:1234", "test-model", Secret.fromString("test-api-key"));
    }

    @Override
    public Assistant createAssistant() {
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
                callCount++;
                return answerMessage;
            }
        };
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

    public int getCallCount() {
        return callCount;
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
