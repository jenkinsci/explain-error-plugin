package io.jenkins.plugins.explain_error;

import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.util.Set;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import io.jenkins.plugins.explain_error.GlobalConfigurationImpl;

/**
 * Pipeline step to explain errors using AI.
 */
public class ExplainErrorStep extends Step {

    private String logPattern;
    private int maxLines;

    @DataBoundConstructor
    public ExplainErrorStep() {
        this.logPattern = "";
        this.maxLines = 100;
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

    private static class ExplainErrorStepExecution extends SynchronousNonBlockingStepExecution<Void> {

        private static final long serialVersionUID = 1L;
        private final transient ExplainErrorStep step;

        ExplainErrorStepExecution(StepContext context, ExplainErrorStep step) {
            super(context);
            this.step = step;
        }

        @Override
        protected Void run() throws Exception {
            try {
                Run<?, ?> run = getContext().get(Run.class);
                TaskListener listener = getContext().get(TaskListener.class);
                String apiKey = GlobalConfigurationImpl.get().getApiKey() != null
                    ? GlobalConfigurationImpl.get().getApiKey().getPlainText()
                    : null;
                if (apiKey == null || apiKey.isEmpty()) {
                    listener.error("ERROR: API key is not configured");
                    return null;
                }
                String providerName = GlobalConfigurationImpl.get().getCurrentProviderDisplayName();
                if (providerName == null || providerName.isEmpty()) {
                    providerName = "PlaceholderProvider";
                }

                //Add console explain error action to build (if not already present)
                if (run.getAction(ConsoleExplainErrorAction.class) == null) {
                run.addOrReplaceAction(new ConsoleExplainErrorAction(run, providerName));
                }
                    if (GlobalConfigurationImpl.get().getApiKey() != null && !GlobalConfigurationImpl.get().getApiKey().getPlainText().isEmpty()) {
                        ErrorExplainer explainer = new ErrorExplainer();
                        explainer.explainError(run, listener, step.getLogPattern(), step.getMaxLines());
                        String explanation = explainer.getExplanation();
                        String originalErrorLogs = explainer.getOriginalErrorLogs();
                        if (explanation != null) {
                            if (run.getAction(ErrorExplanationAction.class) == null) {
                                //Call the NEW 3-argument constructor
                                run.addAction(new ErrorExplanationAction(explanation, originalErrorLogs, providerName));
                            }
                        }
                    }
            } catch (Exception e) {
                TaskListener listener = getContext().get(TaskListener.class);
                e.printStackTrace(listener.getLogger());
            }
            return null;
        }
    }
}
