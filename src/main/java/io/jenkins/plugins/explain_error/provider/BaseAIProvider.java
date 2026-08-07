package io.jenkins.plugins.explain_error.provider;

import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.logging.Logger;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.ProxyConfiguration;
import hudson.ExtensionPoint;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.TaskListener;
import hudson.security.AccessControlled;
import hudson.util.FormValidation;
import hudson.util.Secret;
import io.jenkins.plugins.explain_error.ExplanationException;
import io.jenkins.plugins.explain_error.JenkinsLogAnalysis;
import jenkins.model.Jenkins;
import org.springframework.security.core.Authentication;

public abstract class BaseAIProvider extends AbstractDescribableImpl<BaseAIProvider> implements ExtensionPoint {

    private static final Logger LOGGER = Logger.getLogger(BaseAIProvider.class.getName());
    public static final String SYSTEM_PROMPT = """
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
                        """;
    public static final String USER_PROMPT_TEMPLATE = """
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
                        """;

    protected String url;
    protected String model;

    public BaseAIProvider(String url, String model) {
        this.url = url;
        this.model = model;
    }

    /**
     * The single assistant factory providers implement. It receives the full
     * request context — credentials scope and temperature — so that a provider
     * cannot accidentally drop a parameter by overriding the wrong overload
     * (the narrower {@code createAssistant} variants are final and delegate
     * here). Providers without item-scoped credentials simply ignore
     * {@code item}/{@code authentication}.
     *
     * @param item           the item defining the credentials scope, or {@code null}
     * @param authentication the authentication for credentials lookup, or {@code null}
     * @param temperature    the temperature to use, or {@code null} for the provider default
     * @return the assistant
     */
    public abstract Assistant createAssistant(@CheckForNull Item item,
                                              @CheckForNull Authentication authentication,
                                              @CheckForNull Double temperature);

    public final Assistant createAssistant() {
        return createAssistant(null, null, null);
    }

    /**
     * The single fix-assistant factory providers implement; the no-arg
     * {@code createFixAssistant()} is final and delegates here.
     *
     * @param item           the item defining the credentials scope, or {@code null}
     * @param authentication the authentication for credentials lookup, or {@code null}
     * @return the fix assistant
     */
    public abstract io.jenkins.plugins.explain_error.autofix.FixAssistant createFixAssistant(
            @CheckForNull Item item, @CheckForNull Authentication authentication);

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
        return explainError(errorLogs, listener, language, customContext, null, null);
    }

    /**
     * Explain error logs using the configured AI provider with item-scoped credentials context.
     * @param errorLogs the error logs to explain
     * @param listener the task listener for logging
     * @param language the preferred response language
     * @param customContext additional custom context/instructions for the AI
     * @param item the item defining credentials scope
     * @param authentication the authentication used for credentials lookup
     * @return the AI explanation
     * @throws ExplanationException if there's a communication error
     */
    public final String explainError(String errorLogs, TaskListener listener, String language, String customContext,
                                     @CheckForNull Item item, @CheckForNull Authentication authentication)
            throws ExplanationException {
        return explainError(errorLogs, listener, language, customContext, item, authentication, null);
    }

    /**
     * Explain error logs using the configured AI provider with item-scoped credentials context.
     * @param errorLogs the error logs to explain
     * @param listener the task listener for logging
     * @param language the preferred response language
     * @param customContext additional custom context/instructions for the AI
     * @param item the item defining credentials scope
     * @param authentication the authentication used for credentials lookup
     * @param temperature the temperature to use, or null to let provider defaults apply
     * @return the AI explanation
     * @throws ExplanationException if there's a communication error
     */
    public final String explainError(String errorLogs, TaskListener listener, String language, String customContext,
                                     @CheckForNull Item item, @CheckForNull Authentication authentication,
                                     @CheckForNull Double temperature)
            throws ExplanationException {
        Assistant assistant;

        if (StringUtils.isBlank(errorLogs)) {
            throw new ExplanationException("warning", "No error logs provided for explanation.");
        }

        if (isNotValid(listener, item, authentication)) {
            throw new ExplanationException("error", "The provider is not properly configured.");
        }

        try {
            assistant = createAssistant(item, authentication, temperature);
        } catch (Exception e) {
            throw new ExplanationException("error", "Failed to create assistant", e);
        }

        String responseLanguage = StringUtils.isBlank(language) ? "English" : language.trim();
        String additionalContext = StringUtils.isBlank(customContext)
            ? "" 
            : "\n\nIMPORTANT - ADDITIONAL INSTRUCTIONS (You MUST address these in your response):\n" + customContext.trim();
        
        LOGGER.fine("Explaining error with language: " + responseLanguage + ", customContext length: " + additionalContext.length()
                + ", temperature: " + (temperature != null ? temperature : "unset (provider default)"));

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
        @SystemMessage(SYSTEM_PROMPT)
        @UserMessage(USER_PROMPT_TEMPLATE)
        JenkinsLogAnalysis analyzeLogs(@V("errorLogs") String errorLogs, @V("language") String language, @V("customContext") String customContext);
    }

    public final Assistant createAssistant(@CheckForNull Item item, @CheckForNull Authentication authentication) {
        return createAssistant(item, authentication, null);
    }

    /**
     * Create an assistant with a specific temperature setting.
     * @param temperature the temperature value, or null to let provider defaults apply
     * @return the assistant
     */
    public final Assistant createAssistant(@CheckForNull Double temperature) {
        return createAssistant(null, null, temperature);
    }

    public final io.jenkins.plugins.explain_error.autofix.FixAssistant createFixAssistant() {
        return createFixAssistant(null, null);
    }

    public boolean isNotValid(@CheckForNull TaskListener listener, @CheckForNull Item item,
                              @CheckForNull Authentication authentication) {
        return isNotValid(listener);
    }

    protected final String buildSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    protected final String buildUserPrompt(String errorLogs, String language, String customContext) {
        return USER_PROMPT_TEMPLATE
            .replace("{{language}}", language)
            .replace("{{customContext}}", customContext)
            .replace("{{errorLogs}}", errorLogs);
    }

    protected final HttpClient.Builder newJenkinsHttpClientBuilder() {
        HttpClient.Builder builder = HttpClient.newBuilder();
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        ProxyConfiguration proxyConfiguration = jenkins != null ? jenkins.getProxy() : null;
        if (proxyConfiguration != null && proxyConfiguration.getName() != null) {
            builder.proxy(new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    String scheme = uri.getScheme();
                    if (scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                        // Honors the noProxyHost exclusions, same as Jenkins core.
                        return List.of(proxyConfiguration.createProxy(uri.getHost()));
                    }
                    return List.of(Proxy.NO_PROXY);
                }

                @Override
                public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                    // Ignore, same as Jenkins core's own proxy selector.
                }
            });
        }
        // Deliberately no Authenticator: Jenkins core attaches one when the proxy has
        // credentials, and since JDK-8326949 (fixed only in JDK 24) the JDK HttpClient
        // drops user-set Authorization headers whenever an Authenticator is present.
        // Proxy credentials are instead sent preemptively via Proxy-Authorization,
        // see getProxyAuthorizationHeader() and ProxyAwareHttpClient.
        // Follow redirects returned by AI gateways and proxies (e.g. http -> https
        // upgrades behind load balancers). Matches Jenkins core's own HttpClient
        // behaviour; the default is NEVER, which fails any 3xx response.
        return builder.followRedirects(HttpClient.Redirect.NORMAL);
    }

    /**
     * Preemptive {@code Proxy-Authorization} header value for the Jenkins proxy, or
     * {@code null} when no proxy credentials are configured.
     *
     * <p>Sent as a user-set header instead of relying on a JDK {@link java.net.Authenticator}:
     * with an Authenticator present, the JDK HttpClient drops user-set Authorization
     * headers (JDK-8326949, fixed only in JDK 24), which silently removes the API key
     * from AI requests on Jenkins instances with an authenticated proxy.
     */
    @CheckForNull
    protected final String getProxyAuthorizationHeader() {
        return proxyAuthorizationHeaderOrNull();
    }

    @CheckForNull
    static String proxyAuthorizationHeaderOrNull() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        ProxyConfiguration proxyConfiguration = jenkins != null ? jenkins.getProxy() : null;
        // Require a proxy host as well as a user name: newJenkinsHttpClientBuilder()
        // only routes through the proxy when a host is configured, and without one
        // this header would be sent to the origin server, leaking the credentials.
        if (proxyConfiguration == null
                || proxyConfiguration.getName() == null
                || proxyConfiguration.getUserName() == null) {
            return null;
        }
        Secret secretPassword = proxyConfiguration.getSecretPassword();
        String password = secretPassword == null ? "" : Secret.toString(secretPassword);
        String credentials = proxyConfiguration.getUserName() + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The endpoint URL this provider would call, used only to run connection
     * diagnostics when a configuration test fails. Providers with a fixed or
     * derivable default endpoint should override this to return it when no
     * explicit URL is configured.
     *
     * @return the effective endpoint URL, or {@code null} when unknown
     */
    @CheckForNull
    public String getEffectiveEndpointForDiagnostics() {
        return Util.fixEmptyAndTrim(url);
    }

    protected final HttpClientBuilder newLangChainHttpClientBuilder() {
        return new ProxyAwareHttpClientBuilder(
                new JdkHttpClientBuilder().httpClientBuilder(newJenkinsHttpClientBuilder()),
                getProxyAuthorizationHeader());
    }

    public String getProviderName() {
        return getDescriptor().getDisplayName();
    }

    public abstract static class BaseProviderDescriptor extends Descriptor<BaseAIProvider> {
        public abstract String getDefaultModel();

        /**
         * Check that the user has permission to configure the plugin.
         * When called from an item context (folder, job), checks {@link Item#CONFIGURE}.
         * When called from a global context, checks {@link Jenkins#ADMINISTER}.
         *
         * @param context the access-controlled context, or {@code null} for global context
         */
        protected static void checkConfigurePermission(@CheckForNull AccessControlled context) {
            if (context instanceof Item item) {
                item.checkPermission(Item.CONFIGURE);
            } else {
                Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            }
        }

        /**
         * Template for the "Test Configuration" button: checks the caller's
         * permission, sends a canned prompt through the fully-constructed
         * provider, and renders either the standard success message or the
         * failure response with connection diagnostics. Descriptors only
         * construct the provider from their form fields and delegate here.
         *
         * @param context  the item context of the form, or {@code null} for global configuration
         * @param provider the provider instance built from the submitted form values
         * @return the outcome to render next to the button
         */
        protected final FormValidation runConfigurationTest(@CheckForNull AccessControlled context,
                                                            BaseAIProvider provider) {
            checkConfigurePermission(context);
            try {
                provider.explainError("Send 'Configuration test successful' to me.", null);
                return FormValidation.ok("Configuration test successful! API connection is working properly.");
            } catch (ExplanationException e) {
                return testConfigurationFailed(provider, e);
            }
        }

        /**
         * Builds the failure response for a "Test Configuration" call:
         * the provider error plus a layer-by-layer connection diagnostics
         * report (proxy decision, DNS, TCP, HTTP probe, error cause chain)
         * so admins can see where the connection breaks.
         *
         * @param provider the provider instance the test was run against
         * @param failure  the exception the test failed with
         * @return a form validation error carrying the diagnostics report
         */
        protected final FormValidation testConfigurationFailed(BaseAIProvider provider, Exception failure) {
            String report = ConnectionDiagnostics.run(provider.getEffectiveEndpointForDiagnostics(), failure);
            String message = Util.fixEmptyAndTrim(failure.getMessage());
            return FormValidation.errorWithMarkup("<b>Configuration test failed:</b> "
                    + Util.escape(message != null ? message : failure.getClass().getSimpleName())
                    + "<pre style=\"white-space:pre-wrap;margin-top:6px;user-select:all\">"
                    + Util.escape(report) + "</pre>");
        }

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
                // Reject credentials embedded in the URL (e.g. https://user:pass@host/…).
                // Such values would be serialised to disk in plaintext; use the dedicated
                // Secret fields (clientSecret, appKey) for any credentials instead.
                if (uri.getUserInfo() != null) {
                    return FormValidation.error(
                            "Credentials must not be embedded in the URL. Use the dedicated secret fields instead.");
                }
            } catch (MalformedURLException | URISyntaxException e) {
                return FormValidation.error(e, "URL is not well formed.");
            }
            return FormValidation.ok();
        }
    }
}
