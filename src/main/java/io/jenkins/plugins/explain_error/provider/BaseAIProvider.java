package io.jenkins.plugins.explain_error.provider;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.logging.Logger;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.ExtensionPoint;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import io.jenkins.plugins.explain_error.ExplanationException;
import io.jenkins.plugins.explain_error.JenkinsLogAnalysis;

public abstract class BaseAIProvider extends AbstractDescribableImpl<BaseAIProvider> implements ExtensionPoint {

    private static final Logger LOGGER = Logger.getLogger(BaseAIProvider.class.getName());

    protected String url;
    protected String model;

    public BaseAIProvider(String url, String model) {
        this.url = url;
        this.model = model;
    }

    public abstract Assistant createAssistant();

    public abstract boolean isNotValid(@CheckForNull TaskListener listener);

    public String getUrl() {
        return url;
    }

    public String getModel() {
        return model;
    }

    /**
     * Explain error logs using the configured AI provider.
     * @param errorLogs the error logs to explain
     * @return the AI explanation
     * @throws ExplanationException if there's a communication error
     */
    public final String explainError(String errorLogs, TaskListener listener) throws ExplanationException {
        return explainError(errorLogs, listener, null, null);
    }

    /**
     * Explain error logs using the configured AI provider.
     * @param errorLogs the error logs to explain
     * @param language the preferred response language
     * @return the AI explanation
     * @throws ExplanationException if there's a communication error
     */
    public final String explainError(String errorLogs, TaskListener listener, String language) throws ExplanationException {
        return explainError(errorLogs, listener, language, null);
    }

    /**
     * Explain error logs using the configured AI provider.
     * @param errorLogs the error logs to explain
     * @param listener the task listener for logging
     * @param language the preferred response language
     * @param customContext additional custom context/instructions for the AI
     * @return the AI explanation
     * @throws ExplanationException if there's a communication error
     */
    public final String explainError(String errorLogs, TaskListener listener, String language, String customContext) throws ExplanationException {
        Assistant assistant;

        if (StringUtils.isBlank(errorLogs)) {
            throw new ExplanationException("warning", "No error logs provided for explanation.");
        }

        if (isNotValid(listener)) {
            throw new ExplanationException("error", "The provider is not properly configured.");
        }

        try {
            assistant = createAssistant();
        } catch (Exception e) {
            throw new ExplanationException("error", "Failed to create assistant", e);
        }

        String responseLanguage = StringUtils.isBlank(language) ? "English" : language.trim();
        String additionalContext = StringUtils.isBlank(customContext)
            ? "" 
            : "\n\nIMPORTANT - ADDITIONAL INSTRUCTIONS (You MUST address these in your response):\n" + customContext.trim();
        
        LOGGER.fine("Explaining error with language: " + responseLanguage + ", customContext length: " + additionalContext.length());

        try {
            return assistant.analyzeLogs(errorLogs, responseLanguage, additionalContext).toString();
        } catch (Exception e) {
            LOGGER.severe("AI API request failed: " + e.getMessage());
            throw new ExplanationException("error", "API request failed: " + e.getMessage(), e);
        }
    }

    @Override
    public BaseProviderDescriptor getDescriptor() {
        return (BaseProviderDescriptor) super.getDescriptor();
    }

    public interface Assistant {
        @SystemMessage("""
            You are an expert Jenkins administrator and software engineer.
            You MUST follow ALL instructions provided by the user, including any additional context or requirements.
            When additional instructions are provided, you MUST incorporate them into your analysis fields,
            especially in errorSummary and resolutionSteps.

            The error logs may contain sections from downstream (sub-job) builds, clearly delimited like this:
              ### Downstream Job: <job-name> #<build-number> ###
              Result: <result>
              --- LOG CONTENT ---
              ... (sub-job log lines, OR an "[AI explanation from sub-job]" block) ...
              ### END OF DOWNSTREAM JOB: <job-name> ###

            The "Result:" line can use several values. Treat them as follows:
            - "FAILURE"  — this sub-job genuinely failed and is usually the ROOT CAUSE of the overall failure.
            - "ABORTED (interrupted by fail-fast, not the root cause)" — this sub-job was still running
              when a sibling branch failed; it was aborted automatically by parallelsAlwaysFailFast() or
              parallel(failFast:true). It is NOT the root cause. Do NOT treat its logs as the primary error.
            - "ABORTED" — this sub-job was aborted for other reasons (for example: manual abort, timeout,
              upstream build abort, or infrastructure shutdown). It is not the special fail-fast case above.
              Explain why it was aborted if the logs make that clear, but do NOT assume it is the main root
              cause if other sub-jobs have Result: FAILURE.
            - "UNSTABLE" — this sub-job completed but ended in an unstable state (for example: test failures
              or quality gate issues). Treat this as an important problem to explain, especially if there is
              no Result: FAILURE in any sub-job, but be clear that the build is unstable rather than failed.
            - "UNAVAILABLE" — this sub-job's detailed logs or result are not accessible (for example: due to
              permissions). You cannot analyze its internals; briefly note that its details are unavailable.

            The log content of a downstream section may be either:
            - Raw log lines from the sub-job's failing step, OR
            - An "[AI explanation from sub-job]" block: a pre-computed AI analysis produced by the
              sub-job itself when it called explainError(). Treat this block as a high-quality,
              already-analysed summary of the sub-job's failure — do NOT re-analyse it from scratch.
              Instead, incorporate its key findings (root cause, resolution steps) into your own
              errorSummary and resolutionSteps for the parent job.

            When downstream sections are present:
            - Identify WHICH sub-job(s) have Result: FAILURE — those are the primary root cause(s).
            - If there are NO Result: FAILURE entries, look at Result: UNSTABLE or plain Result: ABORTED
              sections to infer the most likely cause, and explain that clearly.
            - State the full name and build number of important sub-jobs explicitly in errorSummary.
            - Focus root-cause analysis and resolutionSteps on the FAILURE sections when they exist.
            - Mention aborted sub-jobs briefly (for example: "Job X was aborted due to fail-fast" or
              "Job Y was manually aborted after a timeout") but do NOT treat their logs as the primary
              source of the error if a FAILURE section is present.
            - If multiple sub-jobs have Result: FAILURE, summarize each one separately.
            - Logs outside downstream sections belong to the parent (upstream) job.
            """)
        @UserMessage("""
            Analyze the following Jenkins build error logs and provide a clear, actionable explanation.

            CRITICAL: You MUST respond ONLY in {{language}}. ALL text in your response must be in {{language}}.
            This includes: error summaries, resolution steps, best practices, and any other text.
            {{customContext}}

            ERROR LOGS:
            {{errorLogs}}

            Remember: Your ENTIRE response must be in {{language}}, including all field values.
            If the logs contain "### Downstream Job: ..." sections:
            - Sub-jobs with Result: FAILURE are the primary ROOT CAUSE — identify them by name in errorSummary.
            - Sub-jobs with Result: ABORTED (interrupted by fail-fast, not the root cause) were killed by a sibling failure via fail-fast — do NOT treat them as the error source.
            - Sub-jobs with plain Result: ABORTED or Result: UNSTABLE indicate other types of problems (for example: manual aborts, timeouts, or test failures). Explain these issues,
              especially when there is no Result: FAILURE, but clearly describe how they differ from a hard failure.
            - Sub-jobs with Result: UNAVAILABLE cannot be analyzed in detail; briefly mention that their logs or results are not accessible.
            If additional instructions were provided above, you MUST address them in your errorSummary or resolutionSteps.
            """)
        JenkinsLogAnalysis analyzeLogs(@V("errorLogs") String errorLogs, @V("language") String language, @V("customContext") String customContext);
    }

    public String getProviderName() {
        return getDescriptor().getDisplayName();
    }

    public abstract static class BaseProviderDescriptor extends Descriptor<BaseAIProvider> {
        public abstract String getDefaultModel();

        @POST
        @SuppressWarnings("lgtm[jenkins/no-permission-check]")
        public FormValidation doCheckUrl(@QueryParameter String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.ok();
            }
            try {
                URI uri = new URL(value).toURI();
                String scheme = uri.getScheme();
                if (uri.getHost() == null) {
                    return FormValidation.error("url is not well formed.");
                }
                if (!"http".equals(scheme) && !"https".equals(scheme)) {
                    return FormValidation.error("URL must use http or https");
                }
            } catch (MalformedURLException | URISyntaxException e) {
                return FormValidation.error(e, "URL is not well formed.");
            }
            return FormValidation.ok();
        }
    }
}
