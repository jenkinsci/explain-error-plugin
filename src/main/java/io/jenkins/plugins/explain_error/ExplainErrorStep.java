package io.jenkins.plugins.explain_error;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.explain_error.autofix.AutoFixOrchestrator;
import io.jenkins.plugins.explain_error.autofix.AutoFixResult;
import io.jenkins.plugins.explain_error.autofix.AutoFixStatus;
import io.jenkins.plugins.explain_error.provider.BaseAIProvider;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Pipeline step to explain errors using AI.
 */
public class ExplainErrorStep extends Step {

    private String logPattern;
    private int maxLines;
    private String language;
    private String customContext;
    private Double temperature;
    private boolean collectDownstreamLogs;
    private String downstreamJobPattern;
    private boolean includeWorkspaceContext = false;
    private String workspaceContextPaths = WorkspaceContextCollector.DEFAULT_PATHS;
    private int workspaceContextMaxBytes = WorkspaceContextCollector.DEFAULT_MAX_BYTES;

    // Auto-fix fields
    private boolean autoFix = false;
    private String autoFixCredentialsId = "";
    private String autoFixRemoteUrl = "";
    private String autoFixScmType = "";
    private String autoFixGithubEnterpriseUrl = "";
    private String autoFixGitlabUrl = "";
    private String autoFixBitbucketUrl = "";
    private String autoFixAllowedPaths = "pom.xml,build.gradle,build.gradle.kts,*.properties,*.yml,*.yaml,Jenkinsfile,Dockerfile,package.json,requirements.txt,go.mod";
    private boolean autoFixDraftPr = false;
    private int autoFixTimeoutSeconds = 120;
    private String autoFixPrTemplate = "";

    @DataBoundConstructor
    public ExplainErrorStep() {
        this.logPattern = "";
        this.maxLines = 100;
        this.language = "";
        this.customContext = "";
        this.collectDownstreamLogs = false;
        this.downstreamJobPattern = "";
    }

    public String getLogPattern() {
        return logPattern;
    }

    @DataBoundSetter
    public void setLogPattern(String logPattern) {
        this.logPattern = logPattern != null ? logPattern : "";
    }

    public int getMaxLines() {
        return maxLines;
    }

    @DataBoundSetter
    public void setMaxLines(int maxLines) {
        this.maxLines = maxLines > 0 ? maxLines : 100;
    }

    public String getLanguage() {
        return language;
    }

    @DataBoundSetter
    public void setLanguage(String language) {
        this.language = language != null ? language : "";
    }

    public String getCustomContext() {
        return customContext;
    }

    @DataBoundSetter
    public void setCustomContext(String customContext) {
        this.customContext = customContext != null ? customContext : "";
    }

    public Double getTemperature() {
        return temperature;
    }

    @DataBoundSetter
    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public boolean isCollectDownstreamLogs() {
        return collectDownstreamLogs;
    }

    @DataBoundSetter
    public void setCollectDownstreamLogs(boolean collectDownstreamLogs) {
        this.collectDownstreamLogs = collectDownstreamLogs;
    }

    public String getDownstreamJobPattern() {
        return downstreamJobPattern;
    }

    @DataBoundSetter
    public void setDownstreamJobPattern(String downstreamJobPattern) {
        this.downstreamJobPattern = downstreamJobPattern != null ? downstreamJobPattern : "";
    }

    public boolean isIncludeWorkspaceContext() {
        return includeWorkspaceContext;
    }

    @DataBoundSetter
    public void setIncludeWorkspaceContext(boolean includeWorkspaceContext) {
        this.includeWorkspaceContext = includeWorkspaceContext;
    }

    public String getWorkspaceContextPaths() {
        return workspaceContextPaths;
    }

    @DataBoundSetter
    public void setWorkspaceContextPaths(String workspaceContextPaths) {
        this.workspaceContextPaths = workspaceContextPaths != null ? workspaceContextPaths
                : WorkspaceContextCollector.DEFAULT_PATHS;
    }

    public int getWorkspaceContextMaxBytes() {
        return workspaceContextMaxBytes;
    }

    @DataBoundSetter
    public void setWorkspaceContextMaxBytes(int workspaceContextMaxBytes) {
        this.workspaceContextMaxBytes = workspaceContextMaxBytes > 0
                ? workspaceContextMaxBytes : WorkspaceContextCollector.DEFAULT_MAX_BYTES;
    }

    public boolean isAutoFix() {
        return autoFix;
    }

    @DataBoundSetter
    public void setAutoFix(boolean autoFix) {
        this.autoFix = autoFix;
    }

    public String getAutoFixCredentialsId() {
        return autoFixCredentialsId;
    }

    @DataBoundSetter
    public void setAutoFixCredentialsId(String autoFixCredentialsId) {
        this.autoFixCredentialsId = autoFixCredentialsId != null ? autoFixCredentialsId : "";
    }

    public String getAutoFixRemoteUrl() {
        return autoFixRemoteUrl;
    }

    @DataBoundSetter
    public void setAutoFixRemoteUrl(String autoFixRemoteUrl) {
        this.autoFixRemoteUrl = autoFixRemoteUrl != null ? autoFixRemoteUrl : "";
    }

    public String getAutoFixScmType() {
        return autoFixScmType;
    }

    @DataBoundSetter
    public void setAutoFixScmType(String autoFixScmType) {
        this.autoFixScmType = autoFixScmType != null ? autoFixScmType : "";
    }

    public String getAutoFixGithubEnterpriseUrl() {
        return autoFixGithubEnterpriseUrl;
    }

    @DataBoundSetter
    public void setAutoFixGithubEnterpriseUrl(String autoFixGithubEnterpriseUrl) {
        this.autoFixGithubEnterpriseUrl = autoFixGithubEnterpriseUrl != null ? autoFixGithubEnterpriseUrl : "";
    }

    public String getAutoFixGitlabUrl() {
        return autoFixGitlabUrl;
    }

    @DataBoundSetter
    public void setAutoFixGitlabUrl(String autoFixGitlabUrl) {
        this.autoFixGitlabUrl = autoFixGitlabUrl != null ? autoFixGitlabUrl : "";
    }

    public String getAutoFixBitbucketUrl() {
        return autoFixBitbucketUrl;
    }

    @DataBoundSetter
    public void setAutoFixBitbucketUrl(String autoFixBitbucketUrl) {
        this.autoFixBitbucketUrl = autoFixBitbucketUrl != null ? autoFixBitbucketUrl : "";
    }

    public String getAutoFixAllowedPaths() {
        return autoFixAllowedPaths;
    }

    @DataBoundSetter
    public void setAutoFixAllowedPaths(String autoFixAllowedPaths) {
        this.autoFixAllowedPaths = autoFixAllowedPaths != null ? autoFixAllowedPaths
                : "pom.xml,build.gradle,build.gradle.kts,*.properties,*.yml,*.yaml,Jenkinsfile,Dockerfile,package.json,requirements.txt,go.mod";
    }

    public boolean isAutoFixDraftPr() {
        return autoFixDraftPr;
    }

    @DataBoundSetter
    public void setAutoFixDraftPr(boolean autoFixDraftPr) {
        this.autoFixDraftPr = autoFixDraftPr;
    }

    public int getAutoFixTimeoutSeconds() {
        return autoFixTimeoutSeconds;
    }

    @DataBoundSetter
    public void setAutoFixTimeoutSeconds(int autoFixTimeoutSeconds) {
        this.autoFixTimeoutSeconds = autoFixTimeoutSeconds > 0 ? autoFixTimeoutSeconds : 60;
    }

    public String getAutoFixPrTemplate() {
        return autoFixPrTemplate;
    }

    @DataBoundSetter
    public void setAutoFixPrTemplate(String autoFixPrTemplate) {
        this.autoFixPrTemplate = autoFixPrTemplate != null ? autoFixPrTemplate : "";
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new ExplainErrorStepExecution(context, this);
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, TaskListener.class);
        }

        @Override
        public String getFunctionName() {
            return "explainError";
        }

        @Override
        public String getDisplayName() {
            return "Explain Error with AI";
        }
    }

    private static class ExplainErrorStepExecution extends SynchronousNonBlockingStepExecution<String> {

        private static final long serialVersionUID = 1L;
        private final transient ExplainErrorStep step;

        ExplainErrorStepExecution(StepContext context, ExplainErrorStep step) {
            super(context);
            this.step = step;
        }

        @Override
        protected String run() throws Exception {
            Run<?, ?> run = getContext().get(Run.class);
            TaskListener listener = getContext().get(TaskListener.class);

            String workspaceContext = collectWorkspaceContext(listener);
            String effectiveCustomContext = mergeCustomContext(step.getCustomContext(), workspaceContext);

            ErrorExplainer explainer = new ErrorExplainer();
            String explanation = explainer.explainError(run, listener, step.getLogPattern(), step.getMaxLines(),
                    step.getLanguage(), effectiveCustomContext, step.isCollectDownstreamLogs(),
                    step.getDownstreamJobPattern(), Jenkins.getAuthentication2(), step.getTemperature());

            if (step.isAutoFix()) {
                String errorLogs = explainer.getLastErrorLogs();
                String autoFixLogs = appendWorkspaceContext(errorLogs, workspaceContext);
                BaseAIProvider provider = explainer.getResolvedProvider(run);

                if (errorLogs == null) {
                    listener.getLogger().println("[AutoFix] Skipped: no error logs available (explanation may have been disabled or skipped).");
                    return explanation;
                }
                if (provider == null) {
                    listener.getLogger().println("[AutoFix] Skipped: no AI provider configured.");
                    return explanation;
                }

                AutoFixOrchestrator orchestrator = new AutoFixOrchestrator();
                List<String> allowedPaths = Arrays.stream(step.getAutoFixAllowedPaths().split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());

                AutoFixResult fixResult = orchestrator.attemptAutoFix(
                        run,
                        autoFixLogs,
                        provider,
                        step.getAutoFixCredentialsId(),
                        step.getAutoFixRemoteUrl().isEmpty() ? null : step.getAutoFixRemoteUrl(),
                        step.getAutoFixScmType().isEmpty() ? null : step.getAutoFixScmType(),
                        step.getAutoFixGithubEnterpriseUrl().isEmpty() ? null : step.getAutoFixGithubEnterpriseUrl(),
                        step.getAutoFixGitlabUrl().isEmpty() ? null : step.getAutoFixGitlabUrl(),
                        step.getAutoFixBitbucketUrl().isEmpty() ? null : step.getAutoFixBitbucketUrl(),
                        allowedPaths,
                        step.isAutoFixDraftPr(),
                        step.getAutoFixTimeoutSeconds(),
                        listener,
                        step.getAutoFixPrTemplate().isEmpty() ? null : step.getAutoFixPrTemplate());
                listener.getLogger().println("[AutoFix] Status: " + fixResult.getStatus() + " - " + fixResult.getMessage());
                if (fixResult.getStatus() == AutoFixStatus.CREATED) {
                    listener.getLogger().println("[AutoFix] PR created: " + fixResult.getPrUrl());
                }
            }

            return explanation;
        }

        private String collectWorkspaceContext(TaskListener listener)
                throws java.io.IOException, InterruptedException {
            if (!step.isIncludeWorkspaceContext()) {
                return "";
            }
            FilePath workspace = getContext().get(FilePath.class);
            if (workspace == null) {
                listener.getLogger().println("[explain-error] Workspace context skipped: no workspace is available.");
                return "";
            }
            listener.getLogger().println("[explain-error] Collecting workspace context.");
            String context = new WorkspaceContextCollector().collect(
                    workspace,
                    step.getWorkspaceContextPaths(),
                    step.getWorkspaceContextMaxBytes(),
                    listener);
            if (context.isBlank()) {
                listener.getLogger().println("[explain-error] Workspace context is empty.");
            } else {
                listener.getLogger().println("[explain-error] Workspace context collected.");
            }
            return context;
        }

        private String mergeCustomContext(String customContext, String workspaceContext) {
            if (workspaceContext == null || workspaceContext.isBlank()) {
                return customContext;
            }
            if (customContext == null || customContext.isBlank()) {
                return workspaceContext;
            }
            return customContext.stripTrailing() + "\n\n" + workspaceContext;
        }

        private String appendWorkspaceContext(String errorLogs, String workspaceContext) {
            if (workspaceContext == null || workspaceContext.isBlank()) {
                return errorLogs;
            }
            return errorLogs + "\n\n" + workspaceContext;
        }
    }
}
