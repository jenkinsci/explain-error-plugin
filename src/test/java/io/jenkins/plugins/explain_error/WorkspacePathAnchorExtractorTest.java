package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.explain_error.WorkspacePathAnchorExtractor.Anchor;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkspacePathAnchorExtractorTest {

    @Test
    void javaStackFramesMapToTheStandardSourceRoots() {
        List<String> anchors = extract("java.lang.RuntimeException: boom\n"
                + "\tat com.example.service.PaymentService.charge(PaymentService.java:42)\n");

        assertTrue(anchors.contains("src/main/java/com/example/service/PaymentService.java"), anchors.toString());
        assertTrue(anchors.contains("src/test/java/com/example/service/PaymentService.java"), anchors.toString());
        assertTrue(anchors.contains("com/example/service/PaymentService.java"), anchors.toString());
        assertEquals(1, anchors.stream()
                .filter("com/example/service/PaymentService.java"::equals).count(), anchors.toString());
    }

    @Test
    void framesWithAClassLoaderOrModulePrefixAreRead() {
        List<String> anchors = extract("\tat app//com.example.App.fail(App.java:7)\n"
                + "\tat my.app@1.0/com.example.Other.run(Other.java:9)\n");

        assertTrue(anchors.contains("src/main/java/com/example/App.java"), anchors.toString());
        assertTrue(anchors.contains("src/main/java/com/example/Other.java"), anchors.toString());
    }

    @Test
    void innerClassFramesUseTheOuterFile() {
        List<String> anchors = extract("\tat com.example.Outer$Inner.run(Outer.java:7)\n");

        assertTrue(anchors.contains("src/main/java/com/example/Outer.java"), anchors.toString());
        assertFalse(anchors.stream().anyMatch(path -> path.contains("$")), anchors.toString());
    }

    @Test
    void mavenCompilerErrorsRelativizeTheWorkspaceRoot() {
        String logs = "[ERROR] /ws/src/main/java/com/example/App.java:[12,5] cannot find symbol\n"
                + "[ERROR] src/test/java/AppTest.java:[3,1] cannot find symbol\n";

        List<String> anchors = WorkspacePathAnchorExtractor.extractPaths(logs, "/ws");

        assertTrue(anchors.contains("src/main/java/com/example/App.java"), anchors.toString());
        assertTrue(anchors.contains("src/test/java/AppTest.java"), anchors.toString());
        assertFalse(anchors.stream().anyMatch(path -> path.startsWith("/ws")), anchors.toString());
    }

    @Test
    void absolutePathsOutsideTheWorkspaceAreDropped() {
        List<String> anchors = WorkspacePathAnchorExtractor.extractPaths(
                "/etc/secret.java:1\nC:\\Windows\\win.java:2\n", "/ws");

        assertEquals(List.of(), anchors);
    }

    @Test
    void pythonTracebacksAndPytestNodeIds() {
        String logs = "Traceback (most recent call last):\n"
                + "  File \"/ws/app/main.py\", line 10, in <module>\n"
                + "FAILED tests/test_api.py::test_charge\n";

        List<String> anchors = WorkspacePathAnchorExtractor.extractPaths(logs, "/ws");

        assertTrue(anchors.contains("app/main.py"), anchors.toString());
        assertTrue(anchors.contains("tests/test_api.py"), anchors.toString());
    }

    @Test
    void nodeTypeScriptGoAndRustLocations() {
        String logs = "file:///ws/src/app.js:1:2\n"
                + "/ws/src/app.ts(4,5): error TS2322\n"
                + "./cmd/main.go:12\n"
                + "--> src/lib.rs:7:9\n";

        List<String> anchors = WorkspacePathAnchorExtractor.extractPaths(logs, "/ws");

        assertTrue(anchors.contains("src/app.js"), anchors.toString());
        assertTrue(anchors.contains("src/app.ts"), anchors.toString());
        assertTrue(anchors.contains("cmd/main.go"), anchors.toString());
        assertTrue(anchors.contains("src/lib.rs"), anchors.toString());
    }

    @Test
    void traversalSchemesAndNodeInternalsAreDropped() {
        String logs = "../../etc/passwd.java:1\n"
                + "src/../secret.java\n"
                + "https://host/app.js:1\n"
                + "node:internal/modules/cjs/loader:1356\n";

        assertEquals(List.of(), WorkspacePathAnchorExtractor.extractPaths(logs, "/ws"));
    }

    @Test
    void capsAndDeduplicatesAnchors() {
        StringBuilder logs = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            logs.append("\tat p.SomeClass").append(i).append(".m(SomeClass").append(i).append(".java:1)\n");
        }
        logs.append("\tat p.SomeClass0.m(SomeClass0.java:1)\n").append("src/app.js:1:2\n");

        List<String> anchors = WorkspacePathAnchorExtractor.extractPaths(logs.toString(), "/ws");

        assertEquals(WorkspacePathAnchorExtractor.MAX_ANCHORS, anchors.size(), anchors.toString());
        assertEquals(anchors.size(), new HashSet<>(anchors).size(), "anchors must be unique");
        assertEquals(1, anchors.stream().filter("src/main/java/p/SomeClass0.java"::equals).count(),
                anchors.toString());
    }

    @Test
    void nullAndEmptyLogsReturnNothing() {
        assertEquals(List.of(), WorkspacePathAnchorExtractor.extractPaths(null, null));
        assertEquals(List.of(), WorkspacePathAnchorExtractor.extractPaths("", null));
        assertEquals(List.of(), WorkspacePathAnchorExtractor.extractPaths("   \n", null));
    }

    @Test
    void workspaceRootIsOptionalForRelativePaths() {
        List<String> anchors = WorkspacePathAnchorExtractor.extractPaths(
                "FAILED tests/test_api.py::test_charge\nFile \"app/main.py\", line 1\n", null);

        assertTrue(anchors.contains("tests/test_api.py"), anchors.toString());
        assertTrue(anchors.contains("app/main.py"), anchors.toString());
    }

    @Test
    void windowsSeparatorsAreNormalized() {
        List<String> anchors = WorkspacePathAnchorExtractor.extractPaths(
                "C:\\ws\\src\\main\\App.java:[1,1] error\n", "C:\\ws");

        assertTrue(anchors.contains("src/main/App.java"), anchors.toString());
        assertFalse(anchors.stream().anyMatch(path -> path.contains("\\")), anchors.toString());
    }

    @Test
    void anchorsKeepTheLineTheLogNames() {
        String logs = "\tat com.example.App.fail(App.java:42)\n"
                + "[ERROR] /ws/src/main/java/com/example/Util.java:[12,5] cannot find symbol\n"
                + "  File \"/ws/app/main.py\", line 10, in <module>\n"
                + "/ws/src/app.ts(4,5): error TS2322\n"
                + "FAILED tests/test_api.py::test_charge\n";

        List<Anchor> anchors = WorkspacePathAnchorExtractor.extract(logs, "/ws");

        assertTrue(anchors.contains(new Anchor("src/main/java/com/example/App.java", 42)), anchors.toString());
        assertTrue(anchors.contains(new Anchor("src/main/java/com/example/Util.java", 12)), anchors.toString());
        assertTrue(anchors.contains(new Anchor("app/main.py", 10)), anchors.toString());
        assertTrue(anchors.contains(new Anchor("src/app.ts", 4)), anchors.toString());
        assertTrue(anchors.contains(new Anchor("tests/test_api.py", 0)), anchors.toString());
    }

    @Test
    void aPathNamedTwiceKeepsItsFirstLine() {
        List<Anchor> anchors = WorkspacePathAnchorExtractor.extract(
                "src/app.js:3:1\nsrc/app.js:9:1\nsrc/lib.js\nsrc/lib.js:7\n", null);

        assertEquals(List.of(new Anchor("src/app.js", 3), new Anchor("src/lib.js", 7)), anchors);
    }

    @Test
    void libraryFramesDoNotUseUpTheAnchors() {
        StringBuilder logs = new StringBuilder("java.lang.AssertionError: boom\n");
        for (int i = 0; i < 40; i++) {
            logs.append("\tat org.junit.jupiter.api.Assert").append(i).append(".fail(Assert").append(i)
                    .append(".java:1)\n");
            logs.append("\tat java.base/jdk.internal.Frame").append(i).append(".run(Frame").append(i)
                    .append(".java:1)\n");
            logs.append("\tat org.jenkinsci.plugins.workflow.Step").append(i).append(".run(Step").append(i)
                    .append(".java:1)\n");
        }
        logs.append("\tat com.example.AppTest.charges(AppTest.java:21)\n");

        List<String> anchors = extract(logs.toString());

        assertTrue(anchors.contains("src/test/java/com/example/AppTest.java"), anchors.toString());
        assertFalse(anchors.stream().anyMatch(path -> path.startsWith("src/main/java/org/junit")),
                anchors.toString());
    }

    private static List<String> extract(String logs) {
        return WorkspacePathAnchorExtractor.extractPaths(logs, null);
    }
}
