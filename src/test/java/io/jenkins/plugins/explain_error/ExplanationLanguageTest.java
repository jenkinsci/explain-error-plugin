package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import io.jenkins.plugins.explain_error.provider.FakeAIProvider;
import java.util.List;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The words around an explanation follow the language the explanation was asked for in.
 */
@WithJenkins
class ExplanationLanguageTest {

    private static final JenkinsLogAnalysis ANALYSIS = new JenkinsLogAnalysis(
            "锁文件不同步", List.of("运行 npm install"), List.of("提交锁文件"), "npm error code EUSAGE");

    @Test
    void anExplanationAskedForInChineseIsLabelledInChinese(JenkinsRule jenkins) throws Exception {
        FakeAIProvider provider = new FakeAIProvider();
        provider.setAnswer(ANALYSIS);
        GlobalConfigurationImpl.get().setAiProvider(provider);
        GlobalConfigurationImpl.get().setLanguage("中文");
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(jenkins.createFreeStyleProject());

        ErrorExplanationAction action = new ErrorExplainer().explainErrorText("line 1\nline 2", "", build);

        assertTrue(action.getExplanation().startsWith("摘要: 锁文件不同步\n\n失败片段: "), action.getExplanation());
        assertTrue(action.getExplanation().contains("\n解决步骤:\n- 运行 npm install\n"), action.getExplanation());

        HtmlPage page = jenkins.createWebClient().getPage(build, "error-explanation");
        assertEquals("如何修复", page.querySelector(".eep-heading").getTextContent().trim());
        assertEquals(List.of("失败位置", "最佳实践"), page.querySelectorAll(".eep-details > summary").stream()
                .map(node -> node.getTextContent().trim()).toList());
        assertTrue(page.querySelector(".eep-meta").getTextContent().contains("已分析 2 行日志"),
                page.querySelector(".eep-meta").getTextContent());
    }

    @Test
    void theDefaultStaysEnglish(JenkinsRule jenkins) throws Exception {
        FakeAIProvider provider = new FakeAIProvider();
        provider.setAnswer(ANALYSIS);
        GlobalConfigurationImpl.get().setAiProvider(provider);
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(jenkins.createFreeStyleProject());

        ErrorExplanationAction action = new ErrorExplainer().explainErrorText("line 1\nline 2", "", build);

        assertTrue(action.getExplanation().startsWith("Summary: "), action.getExplanation());
        HtmlPage page = jenkins.createWebClient().getPage(build, "error-explanation");
        assertEquals("How to fix it", page.querySelector(".eep-heading").getTextContent().trim());
        assertTrue(page.querySelector(".eep-meta").getTextContent().contains("2 log lines analysed"));
    }

    @Test
    void explanationsStoredWithoutALanguageKeepEnglishLabels(JenkinsRule jenkins) throws Exception {
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(jenkins.createFreeStyleProject());
        ErrorExplanationAction stored = new ErrorExplanationAction("text", "", "line 1", "Ollama");
        stored.setStructuredData(ANALYSIS);
        build.addAction(stored);
        build.save();

        HtmlPage page = jenkins.createWebClient().getPage(build, "error-explanation");

        assertEquals("How to fix it", page.querySelector(".eep-heading").getTextContent().trim());
    }
}
