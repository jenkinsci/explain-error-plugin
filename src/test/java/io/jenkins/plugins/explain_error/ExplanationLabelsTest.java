package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExplanationLabelsTest {

    private Locale jvmDefault;

    @BeforeEach
    void rememberDefaultLocale() {
        jvmDefault = Locale.getDefault();
    }

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(jvmDefault);
    }

    @Test
    void englishIsTheDefault() {
        for (String language : new String[] {null, "", "  ", "English", "english", "en", "Klingon"}) {
            assertEquals("How to fix it", ExplanationLabels.forLanguage(language).getHowToFix(), "for " + language);
        }
    }

    @Test
    void aLanguageIsFoundByItsNameInEnglishInItselfOrByItsTag() {
        for (String language : new String[] {"中文", "Chinese", "chinese", " 简体中文 ", "zh", "zh-CN", "zh_CN",
                "Simplified Chinese", "Chinese (Simplified)", "中文（简体）"}) {
            assertEquals("如何修复", ExplanationLabels.forLanguage(language).getHowToFix(), "for " + language);
        }
        for (String language : new String[] {"繁體中文", "Traditional Chinese", "zh-TW", "zh-Hant", "zh-HK"}) {
            assertEquals("如何修復", ExplanationLabels.forLanguage(language).getHowToFix(), "for " + language);
        }
        for (String language : new String[] {"日本語", "Japanese", "ja"}) {
            assertEquals("修正方法", ExplanationLabels.forLanguage(language).getHowToFix(), "for " + language);
        }
        for (String language : new String[] {"Español", "español", "Spanish", "es", "Spanish (Mexico)"}) {
            assertEquals("Cómo solucionarlo", ExplanationLabels.forLanguage(language).getHowToFix(), "for " + language);
        }
        for (String language : new String[] {"Français", "French", "fr"}) {
            assertEquals("Comment corriger", ExplanationLabels.forLanguage(language).getHowToFix(), "for " + language);
        }
        for (String language : new String[] {"Deutsch", "German", "de"}) {
            assertEquals("So lässt es sich beheben", ExplanationLabels.forLanguage(language).getHowToFix(),
                    "for " + language);
        }
    }

    @Test
    void theJvmLocaleDoesNotLeakIntoEnglishExplanations() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE);

        assertEquals("Summary", ExplanationLabels.forLanguage("English").getSummary());
        assertEquals("Summary", ExplanationLabels.forLanguage(null).getSummary());
        assertEquals("Resumen", ExplanationLabels.forLanguage("Spanish").getSummary());
    }

    @Test
    void everyLanguageHasEveryLabel() {
        for (String language : new String[] {"English", "中文", "繁體中文", "日本語", "Español", "Français", "Deutsch"}) {
            ExplanationLabels labels = ExplanationLabels.forLanguage(language);
            for (String label : List.of(labels.getSummary(), labels.getFailedSnippet(), labels.getResolutionSteps(),
                    labels.getBestPractices(), labels.getHowToFix(), labels.getWhereItFailed(),
                    labels.getBestPracticesHeading())) {
                assertEquals(false, label.isBlank(), "a blank label for " + language);
            }
        }
    }

    @Test
    void lineCountsAreFormattedIntoTheSentence() {
        assertEquals("104 log lines analysed", ExplanationLabels.forLanguage(null).linesAnalysed(104));
        assertEquals("已分析 104 行日志", ExplanationLabels.forLanguage("中文").linesAnalysed(104));
        assertEquals("1234 lignes de journal analysées", ExplanationLabels.forLanguage("French").linesAnalysed(1234));
    }

    @Test
    void plainTextUsesTheLabelsOfItsLanguage() {
        JenkinsLogAnalysis analysis = new JenkinsLogAnalysis(
                "锁文件不同步", List.of("运行 npm install"), List.of("提交锁文件"), "npm error code EUSAGE");

        assertEquals("摘要: 锁文件不同步\n\n失败片段: npm error code EUSAGE\n\n解决步骤:\n- 运行 npm install\n\n"
                + "最佳实践:\n- 提交锁文件\n", analysis.toText(ExplanationLabels.forLanguage("中文")));
        assertEquals("Summary: 锁文件不同步\n\nFailed Snippet: npm error code EUSAGE\n\nResolution Steps:\n"
                + "- 运行 npm install\n\nBest Practices:\n- 提交锁文件\n", analysis.toString());
    }
}
