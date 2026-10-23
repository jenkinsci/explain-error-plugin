package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import com.google.common.annotations.VisibleForTesting;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.Run;
import jenkins.model.RunAction2;
import jenkins.model.Jenkins;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.PatternSyntaxException;
import io.jenkins.plugins.explain_error.provider.BaseAIProvider;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.springframework.security.access.AccessDeniedException;

/**
 * Action to add "Explain Error" functionality to console output pages.
 * This action needs to be manually added to builds.
 */
public class ConsoleExplainErrorAction implements RunAction2 {

    private static final Logger LOGGER = Logger.getLogger(ConsoleExplainErrorAction.class.getName());

    /**
     * Log lines a new explanation reads when the request does not say, unless the global Max Log Lines is
     * higher: see {@link #defaultMaxLines()}.
     */
    static final int DEFAULT_MAX_LINES = 200;

    /**
     * The most log lines a request may ask for, unless the global Max Log Lines is higher: every line is sent
     * to the provider.
     */
    static final int MAX_LINES_LIMIT = 1000;

    static final String ALREADY_PENDING = "An explanation of this build is already being generated."
            + " It will be shown when it is ready.";

    private transient Run<?, ?> run;
    private String urlString;

    public ConsoleExplainErrorAction(Run<?, ?> run) {
        this.run = run;
    }

    @Override
    public void onAttached(Run<?, ?> r) {
        this.run = r;
    }

    @Override
    public void onLoad(Run<?, ?> r) {
        this.run = r;
    }

    @Override
    public String getIconFileName() {
        return null; // No icon in sidebar - this is for AJAX functionality only
    }

    @Override
    public String getDisplayName() {
        return null; // No display name in sidebar
    }

    @Override
    public String getUrlName() {
        return "console-explain-error";
    }

    /**
     * AJAX endpoint to explain error from console output.
     * Called via JavaScript from the console output page.
     */
    @RequirePOST
    public void doExplainConsoleError(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        long startTimeNanos = System.nanoTime();
        try {
            run.checkPermission(Item.READ);

            // Folder settings count: a folder can turn explanation off, or on with a provider of its own
            ErrorExplainer switchCheck = new ErrorExplainer();
            if (!switchCheck.isExplanationEnabled(run)) {
                BaseAIProvider provider = switchCheck.providerFor(run);
                recordUsage(UsageEvent.Result.DISABLED,
                        provider != null ? provider.getProviderName() : null,
                        provider != null ? provider.getModel() : null,
                        startTimeNanos,
                        0);
                writeJsonResponse(rsp, "warning", "Unknown", ErrorExplainer.TURNED_OFF);
                return;
            }

            // Check if user wants to force a new explanation
            boolean forceNew = "true".equals(req.getParameter("forceNew"));

            // Check if an explanation already exists
            ErrorExplanationAction existingAction = run.getAction(ErrorExplanationAction.class);
            if (!forceNew && existingAction != null && existingAction.hasValidExplanation()) {
                recordUsage(UsageEvent.Result.CACHE_HIT, existingAction.getProviderName(),
                        existingAction.getProviderModel(), startTimeNanos, existingAction.getInputLogLineCount());
                // Return existing explanation with a flag indicating it's cached
                writeJsonResponse(rsp, "success", existingAction.getProviderName(),
                        createCachedResponse(existingAction.getExplanation()), existingAction.getTitle());
                return;
            }

            // A new explanation calls the provider, which costs money, and replaces the one the build has
            run.checkPermission(Item.BUILD);
            if (!PendingExplanations.tryAdd(run)) {
                BaseAIProvider provider = switchCheck.providerFor(run);
                writeJsonResponse(rsp, "warning", provider != null ? provider.getProviderName() : "Unknown",
                        ALREADY_PENDING);
                return;
            }
            try {
                int maxLines = maxLines(req.getParameter("maxLines"));

                // Fetch the last N lines of the log
                PipelineLogExtractor logExtractor = pageExtractor(run, maxLines);
                List<String> logLines = logExtractor.getFailedStepLog();
                this.urlString = logExtractor.getUrl();

                String errorText = String.join("\n", logLines);

                ErrorExplainer explainer = new ErrorExplainer();
                try {
                    ErrorExplanationAction action = explainer.explainErrorText(errorText, urlString, run);
                    writeJsonResponse(rsp, "success", action.getProviderName(), action.getExplanation(),
                            action.getTitle());
                } catch (ExplanationException ee) {
                    writeJsonResponse(rsp, ee.getLevel(), explainer.getProviderName(), ee.getMessage(), null,
                            ProviderFailure.describe(ee));
                }
            } finally {
                PendingExplanations.remove(run);
            }
        } catch (AccessDeniedException e) {
            rsp.setStatus(403);
            writeJsonResponse(rsp, "error", "Unknown", "Error: " + e.getMessage());
        } catch (Exception e) {
            LOGGER.severe("=== EXPLAIN ERROR REQUEST FAILED ===");
            LOGGER.severe("Error explaining console error: " + e.getMessage());
            writeJsonResponse(rsp, "error", "Unknown", "Error: " + e.getMessage());
        }
    }

    /**
     * Shows, as plain text, what the Explain Error button would send to the AI provider for this build: the
     * log lines, with secrets masked. Nothing is sent and no quota is used. Anyone who can read the build's
     * log can read this.
     */
    public void doPreview(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        run.checkPermission(Item.READ);
        int maxLines = maxLines(req.getParameter("maxLines"));
        String log = String.join("\n", pageExtractor(run, maxLines).getFailedStepLog());
        LogRedactor redactor = LogRedactor.fromConfiguration();
        String sent = redactor.redact(log);
        int masked = redactor.count();
        rsp.setContentType("text/plain;charset=UTF-8");
        PrintWriter writer = rsp.getWriter();
        writer.println("# What the Explain Error button would send to the AI provider for this build.");
        writer.println("# " + ErrorExplainer.countLines(sent) + " log lines. "
                + (masked > 0 ? ErrorExplainer.describeRedaction(masked)
                        : redactor.isActive() ? "Nothing to mask was found." : "Masking is turned off."));
        if (masked > 0 && GlobalConfigurationImpl.get().isBlockWhenRedacted()) {
            writer.println("# Nothing would be sent: the configuration says not to send a log in which"
                    + " something was masked.");
        }
        writer.println("# The custom context configured for the job is sent with it, masked the same way.");
        writer.println();
        writer.print(sent);
        writer.flush();
    }

    /**
     * The number of log lines asked for, within {@link #MAX_LINES_LIMIT}; the default when the request does
     * not say or says something that is not a number.
     */
    static int maxLines(@CheckForNull String requested) {
        if (requested == null) {
            return defaultMaxLines();
        }
        int limit = Math.max(MAX_LINES_LIMIT, GlobalConfigurationImpl.get().getAutoExplainMaxLogLines());
        try {
            return Math.min(Math.max(1, Integer.parseInt(requested.trim())), limit);
        } catch (NumberFormatException e) {
            return defaultMaxLines();
        }
    }

    /**
     * Log lines an explanation asked for on the console or Stages page reads: {@link #DEFAULT_MAX_LINES}, or
     * the global Max Log Lines when that is higher, so that a model with a large context gets as much as an
     * automatic explanation does.
     */
    static int defaultMaxLines() {
        return Math.max(DEFAULT_MAX_LINES, GlobalConfigurationImpl.get().getAutoExplainMaxLogLines());
    }

    /**
     * The log extractor for an explanation asked for on the console or Stages page, with the global log
     * pattern, as an automatic explanation has.
     */
    static PipelineLogExtractor pageExtractor(Run<?, ?> run, int maxLines) {
        PipelineLogExtractor extractor = new PipelineLogExtractor(run, maxLines, Jenkins.getAuthentication2(),
                false, null);
        try {
            extractor.setLogPattern(PipelineLogExtractor.compileLogPattern(
                    GlobalConfigurationImpl.get().getLogPattern()));
        } catch (PatternSyntaxException e) {
            LOGGER.fine("The global log pattern is not a valid regular expression, so it is not used: "
                    + e.getDescription());
        }
        return extractor;
    }

    /**
     * AJAX endpoint to check build status.
     * Returns JSON with buildingStatus to determine if button should be shown. 0 - SUCCESS, 1 - RUNNING, 2 - FINISHED and FAILURE
     * It also carries explanation, which says whether the build has one: "ready", "pending" (an automatic
     * one has been requested and has not come back yet) or "none".
     */
    @RequirePOST
    public void doCheckBuildStatus(StaplerRequest2 req, StaplerResponse2 rsp) {
        try {
            run.checkPermission(Item.READ);

            Integer buildingStatus = run.isBuilding() ? 1 : 0;

            if (buildingStatus == 0) {
                Result result = run.getResult();
                if (result == Result.SUCCESS) {
                    buildingStatus = 0;
                } else {
                    buildingStatus = 2;
                }
            }

            rsp.setContentType("application/json");
            rsp.setCharacterEncoding("UTF-8");
            PrintWriter writer = rsp.getWriter();

            JSONObject json = new JSONObject();
            json.put("buildingStatus", buildingStatus);
            json.put("explanation", getExplanationState());
            writer.write(json.toString());
            writer.flush();
        } catch (Exception e) {
            LOGGER.severe("Error checking build status: " + e.getMessage());
            rsp.setStatus(500);
        }
    }

    /**
     * Whether the build has an explanation: "ready", "pending" or "none".
     */
    public String getExplanationState() {
        ErrorExplanationAction existing = run.getAction(ErrorExplanationAction.class);
        if (existing != null && existing.hasValidExplanation()) {
            return "ready";
        }
        return isExplanationPending() ? "pending" : "none";
    }

    /**
     * Whether an automatic explanation of this build has been requested and has not come back yet.
     */
    public boolean isExplanationPending() {
        return PendingExplanations.contains(run);
    }

    private void writeJsonResponse(StaplerResponse2 rsp, String status, String providerName, String message) throws IOException {
        writeJsonResponse(rsp, status, providerName, message, null);
    }

    private void writeJsonResponse(StaplerResponse2 rsp, String status, String providerName, String message,
                                   @CheckForNull String title) throws IOException {
        writeJsonResponse(rsp, status, providerName, message, title, null);
    }

    /**
     * @param reason a failure in plain words when it is known; {@code message} is then its detail
     */
    private void writeJsonResponse(StaplerResponse2 rsp, String status, String providerName, String message,
                                   @CheckForNull String title, @CheckForNull String reason) throws IOException {
        rsp.setContentType("application/json");
        rsp.setCharacterEncoding("UTF-8");
        PrintWriter writer = rsp.getWriter();

        JSONObject json = new JSONObject();
        json.put("status", status);
        json.put("providerName", providerName);
        json.put("message", message);
        if (title != null) {
            json.put("title", title);
        }
        if (reason != null) {
            json.put("reason", reason);
        }
        json.put("url", urlString);
        writer.write(json.toString());
        writer.flush();
    }

    /**
     * Create a response indicating this is a cached result.
     * @param explanation The cached explanation
     * @return The response string with cached indicator
     */
    @VisibleForTesting
    String createCachedResponse(String explanation) {
        return explanation + "\n\n[Note: This is a previously generated explanation. Use the 'Generate New' option to create a new one.]";
    }

    public Run<?, ?> getRun() {
        return run;
    }

    private void recordUsage(UsageEvent.Result result, String providerName, String model,
                             long startTimeNanos, int inputLogLineCount) {
        UsageRecorders.get().record(new UsageEvent(
                System.currentTimeMillis(),
                UsageEvent.EntryPoint.CONSOLE_ACTION,
                result,
                providerName,
                model,
                Math.max(0L, (System.nanoTime() - startTimeNanos) / 1_000_000L),
                inputLogLineCount,
                false));
    }
}
