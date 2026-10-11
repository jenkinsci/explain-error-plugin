package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Api;
import hudson.model.Run;
import java.util.ArrayList;
import java.util.List;
import jenkins.model.RunAction2;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

/**
 * Build action to store and display error explanations.
 */
@ExportedBean(defaultVisibility = 999)
public class ErrorExplanationAction implements RunAction2 {

    /** Longest summary shown whole on the build's own page. */
    static final int PREVIEW_LENGTH = 320;

    /** Titles are asked for at 70 characters; anything much longer is not a title. */
    static final int MAX_TITLE_LENGTH = 90;

    private final String explanation;
    private final String urlString;
    private final transient String originalErrorLogs;
    private final int inputLogLineCount;
    private final long timestamp;
    private String providerName = "Unknown";
    private String providerModel = "Unknown";
    private transient Run<?, ?> run;

    // Structured fields of the AI analysis; null for explanations stored by older plugin versions
    private String errorSummary;
    private List<String> resolutionSteps;
    private List<String> bestPractices;
    private String errorSignature;
    private String title;
    /** The language the explanation was asked for in; null means English, as on older builds. */
    private String language;

    public ErrorExplanationAction(String explanation, String urlString, String originalErrorLogs, String providerName) {
        this(explanation, urlString, originalErrorLogs, providerName, null,
                ErrorExplainer.countLines(originalErrorLogs));
    }

    public ErrorExplanationAction(String explanation, String urlString, String originalErrorLogs,
                                  String providerName, String providerModel, int inputLogLineCount) {
        this.explanation = explanation;
        this.originalErrorLogs = originalErrorLogs;
        this.timestamp = System.currentTimeMillis();
        this.providerName = providerName;
        this.providerModel = providerModel;
        this.urlString = urlString;
        this.inputLogLineCount = Math.max(0, inputLogLineCount);
    }

    public Object readResolve() {
        if (providerName == null) {
            providerName = "Unknown";
        }
        if (providerModel == null) {
            providerModel = "Unknown";
        }
        return this;
    }

    @Override
    public String getIconFileName() {
        return "symbol-sparkles-outline plugin-ionicons-api";
    }

    @Override
    public String getDisplayName() {
        return "AI Error Explanation";
    }

    @Override
    public String getUrlName() {
        return "error-explanation";
    }

    public Api getApi() {
        return new Api(this);
    }

    @Exported
    public String getExplanation() {
        return explanation;
    }

    public String getOriginalErrorLogs() {
        return originalErrorLogs;
    }

    @Exported(visibility = 1)
    public String getErrorSummary() {
        return errorSummary;
    }

    @Exported(visibility = 1)
    public List<String> getResolutionSteps() {
        return resolutionSteps;
    }

    @Exported(visibility = 1)
    public List<String> getBestPractices() {
        return bestPractices;
    }

    @Exported(visibility = 1)
    public String getErrorSignature() {
        return errorSignature;
    }

    void setLanguage(@CheckForNull String language) {
        this.language = language == null || language.isBlank() ? null : language.strip();
    }

    /** The headings around the explanation, in the explanation's own language. */
    @NonNull
    public ExplanationLabels getLabels() {
        return ExplanationLabels.forLanguage(language);
    }

    /** A one-line title naming the root cause; null when the provider did not return one. */
    @Exported(visibility = 1)
    @CheckForNull
    public String getTitle() {
        return title;
    }

    /** The heading for this explanation: its title when there is one, with the provider's name. */
    public String getHeading() {
        return (title != null ? title : getDisplayName()) + " (" + providerName + ")";
    }

    /**
     * Whether the analysis is available field by field. Explanations stored by older plugin
     * versions, and free-text answers, only have {@link #getExplanation()}.
     */
    public boolean isStructured() {
        return errorSummary != null && !errorSummary.isBlank();
    }

    /** The summary as HTML for the panel: escaped, with backtick spans as {@code <code>}. */
    @NonNull
    public String getSummaryHtml() {
        return ExplanationMarkup.inline(errorSummary);
    }

    @NonNull
    public List<String> getResolutionStepsHtml() {
        return inlineAll(resolutionSteps);
    }

    @NonNull
    public List<String> getBestPracticesHtml() {
        return inlineAll(bestPractices);
    }

    /**
     * The summary for the build's own page: all of it when it is short, otherwise cut at the end
     * of a sentence, or failing that at a word. Escaped like {@link #getSummaryHtml()}.
     */
    @NonNull
    public String getSummaryPreviewHtml() {
        return ExplanationMarkup.inline(preview(errorSummary, PREVIEW_LENGTH));
    }

    @NonNull
    static String preview(@CheckForNull String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String paragraph = text.strip().replaceAll("\\s+", " ");
        if (paragraph.length() <= maxLength) {
            return paragraph;
        }
        String head = paragraph.substring(0, maxLength);
        int sentenceEnd = -1;
        for (String stop : new String[] {". ", "! ", "? ", "。", "！", "？"}) {
            sentenceEnd = Math.max(sentenceEnd, head.lastIndexOf(stop));
        }
        if (sentenceEnd >= maxLength / 2) {
            return head.substring(0, sentenceEnd + 1);
        }
        int space = head.lastIndexOf(' ');
        return (space >= maxLength / 2 ? head.substring(0, space) : head) + "\u2026";
    }

    /** Provider and model for the line under the explanation, leaving out what is not known. */
    @NonNull
    public String getProviderLabel() {
        List<String> parts = new ArrayList<>();
        for (String part : new String[] {providerName, providerModel}) {
            if (part != null && !part.isBlank() && !"Unknown".equals(part)) {
                parts.add(part);
            }
        }
        return String.join(" \u00b7 ", parts);
    }

    @NonNull
    private static List<String> inlineAll(@CheckForNull List<String> values) {
        List<String> html = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    html.add(ExplanationMarkup.inline(value));
                }
            }
        }
        return html;
    }

    /**
     * Stores the structured AI analysis alongside the rendered explanation.
     * Lists are copied so the persisted build XML never references immutable JDK collections.
     */
    void setStructuredData(@CheckForNull JenkinsLogAnalysis analysis) {
        if (analysis == null) {
            return;
        }
        this.errorSummary = analysis.errorSummary();
        this.resolutionSteps = copyOf(analysis.resolutionSteps());
        this.bestPractices = copyOf(analysis.bestPractices());
        this.errorSignature = analysis.errorSignature();
        this.title = TitleText.clean(analysis.title(), MAX_TITLE_LENGTH);
    }

    @CheckForNull
    private static List<String> copyOf(@CheckForNull List<String> values) {
        return values == null ? null : new ArrayList<>(values);
    }

    @Exported(visibility = 1)
    public long getTimestamp() {
        return timestamp;
    }

    @Exported(visibility = 1)
    public String getFormattedTimestamp() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z").format(new java.util.Date(timestamp));
    }

    @Exported(visibility = 1)
    public String getProviderName() {
        return providerName;
    }

    @Exported(visibility = 1)
    public String getProviderModel() {
        return providerModel;
    }

    @Exported(visibility = 1)
    public String getUrlString() {
        return urlString;
    }

    @Exported(visibility = 1)
    public int getInputLogLineCount() {
        return inputLogLineCount;
    }

    @Override
    public void onAttached(Run<?, ?> r) {
        this.run = r;
    }

    @Override
    public void onLoad(Run<?, ?> r) {
        this.run = r;
    }

    /**
     * Get the associated run.
     * @return the run this action is attached to
     */
    public Run<?, ?> getRun() {
        return run;
    }

    /**
     * Check if this action has a valid explanation.
     * @return true if explanation is not null, not empty, and not just whitespace
     */
    public boolean hasValidExplanation() {
        return explanation != null && !explanation.isBlank();
    }
}
