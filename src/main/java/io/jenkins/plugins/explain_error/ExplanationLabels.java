package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * The fixed words around an explanation ("Summary", "How to fix it", ...), in the language the
 * explanation itself is written in.
 *
 * <p>The language setting is free text ("中文", "Chinese", "zh-CN", "Español"), so it is matched
 * against the names and tags of the languages there are labels for. Anything else gets English,
 * which is also what the labels were before they could be translated.
 */
public final class ExplanationLabels {

    private static final String BUNDLE = ExplanationLabels.class.getName();
    /** Never fall back to the JVM's own locale: an English explanation gets English labels. */
    private static final ResourceBundle.Control NO_FALLBACK =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    /** Languages with a bundle, besides English. */
    private static final List<Locale> TRANSLATED = List.of(
            Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE, Locale.JAPANESE,
            Locale.forLanguageTag("es"), Locale.FRENCH, Locale.GERMAN);

    /** Names a display-name lookup cannot give: the two Chinese scripts, as people write them. */
    private static final Map<String, Locale> ALIASES = Map.ofEntries(
            Map.entry("中文", Locale.SIMPLIFIED_CHINESE),
            Map.entry("简体中文", Locale.SIMPLIFIED_CHINESE),
            Map.entry("簡體中文", Locale.SIMPLIFIED_CHINESE),
            Map.entry("chinese", Locale.SIMPLIFIED_CHINESE),
            Map.entry("simplified chinese", Locale.SIMPLIFIED_CHINESE),
            Map.entry("zh", Locale.SIMPLIFIED_CHINESE),
            Map.entry("zh-hans", Locale.SIMPLIFIED_CHINESE),
            Map.entry("繁體中文", Locale.TRADITIONAL_CHINESE),
            Map.entry("繁体中文", Locale.TRADITIONAL_CHINESE),
            Map.entry("traditional chinese", Locale.TRADITIONAL_CHINESE),
            Map.entry("zh-hant", Locale.TRADITIONAL_CHINESE),
            Map.entry("zh-hk", Locale.TRADITIONAL_CHINESE));

    private final ResourceBundle bundle;

    private ExplanationLabels(Locale locale) {
        this.bundle = ResourceBundle.getBundle(BUNDLE, locale, ExplanationLabels.class.getClassLoader(), NO_FALLBACK);
    }

    /** Labels for a language setting; English for {@code null}, blank or a language without labels. */
    @NonNull
    public static ExplanationLabels forLanguage(@CheckForNull String language) {
        return new ExplanationLabels(localeFor(language));
    }

    @NonNull
    static Locale localeFor(@CheckForNull String language) {
        if (language == null || language.isBlank()) {
            return Locale.ROOT;
        }
        String name = language.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        Locale alias = ALIASES.get(name);
        if (alias != null) {
            return alias;
        }
        for (Locale locale : TRANSLATED) {
            if (name.equals(locale.toLanguageTag().toLowerCase(Locale.ROOT))
                    || name.equals(locale.getLanguage())
                    || names(locale, Locale.ENGLISH, name)
                    || names(locale, locale, name)) {
                return locale;
            }
        }
        return Locale.ROOT;
    }

    /** Whether {@code name} is how {@code in} calls the language of {@code locale}, alone or with a qualifier. */
    private static boolean names(Locale locale, Locale in, String name) {
        String display = locale.getDisplayLanguage(in).toLowerCase(Locale.ROOT);
        return name.equals(display)
                || name.startsWith(display + " ") || name.startsWith(display + "(") || name.startsWith(display + "（");
    }

    /** Label of the summary in the plain-text explanation. */
    public String getSummary() {
        return bundle.getString("summary");
    }

    /** Label of the log excerpt in the plain-text explanation. */
    public String getFailedSnippet() {
        return bundle.getString("failedSnippet");
    }

    /** Label of the steps in the plain-text explanation. */
    public String getResolutionSteps() {
        return bundle.getString("resolutionSteps");
    }

    /** Label of the best practices in the plain-text explanation. */
    public String getBestPractices() {
        return bundle.getString("bestPractices");
    }

    /** Heading of the best practices in the panel. */
    public String getBestPracticesHeading() {
        return bundle.getString("bestPracticesHeading");
    }

    /** Heading of the steps in the panel. */
    public String getHowToFix() {
        return bundle.getString("howToFix");
    }

    /** Heading of the log excerpt in the panel. */
    public String getWhereItFailed() {
        return bundle.getString("whereItFailed");
    }

    /** "n log lines analysed", for the line under the explanation. */
    public String linesAnalysed(int count) {
        // As text, so the number is not grouped the way one particular locale would group it
        return MessageFormat.format(bundle.getString("linesAnalysed"), String.valueOf(count));
    }

    /** "Generated on ...", for the line under the explanation where the time is not shown elsewhere. */
    public String generatedOn(String time) {
        return MessageFormat.format(bundle.getString("generatedOn"), time);
    }
}
