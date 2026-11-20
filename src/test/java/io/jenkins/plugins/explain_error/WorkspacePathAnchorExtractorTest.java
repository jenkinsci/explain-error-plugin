package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void innerClassFramesUseTheOuterFile() {
        List<String> anchors = extract("\tat com.example.Outer$Inner.run(Outer.java:7)\n");

        assertTrue(anchors.contains("src/main/java/com/example/Outer.java"), anchors.toString());
        assertFalse(anchors.stream().anyMatch(path -> path.contains("$")), anchors.toString());
    }

    @Test
    void mavenCompilerErrorsRelativizeTheWorkspaceRoot() {
        String logs = "[ERROR] /ws/src/main/java/com/example/App.java:[12,5] cannot find symbol\n"
                + "[ERROR] src/test/java/AppTest.java:[3,1] cannot find symbol\n";

        List<String> anchors = WorkspacePathAnchorExtractor.extract(logs, "/ws");

        assertTrue(anchors.contains("src/main/java/com/example/App.java"), anchors.toString());
        assertTrue(anchors.contains("src/test/java/AppTest.java"), anchors.toString());
        assertFalse(anchors.stream().anyMatch(path -> path.startsWith("/ws")), anchors.toString());
    }

    @Test
    void absolutePathsOutsideTheWorkspaceAreDropped() {
        List<String> anchors = WorkspacePathAnchorExtractor.extract(
                "/etc/secret.java:1\nC:\\Windows\\win.java:2\n", "/ws");

        assertEquals(List.of(), anchors);
    }

    @Test
    void pythonTracebacksAndPytestNodeIds() {
        String logs = "Traceback (most recent call last):\n"
                + "  File \"/ws/app/main.py\", line 10, in <module>\n"
                + "FAILED tests/test_api.py::test_charge\n";

        List<String> anchors = WorkspacePathAnchorExtractor.extract(logs, "/ws");

        assertTrue(anchors.contains("app/main.py"), anchors.toString());
        assertTrue(anchors.contains("tests/test_api.py"), anchors.toString());
    }

    @Test
    void nodeTypeScriptGoAndRustLocations() {
        String logs = "file:///ws/src/app.js:1:2\n"
                + "/ws/src/app.ts(4,5): error TS2322\n"
                + "./cmd/main.go:12\n"
                + "--> src/lib.rs:7:9\n";

        List<String> anchors = WorkspacePathAnchorExtractor.extract(logs, "/ws");

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

        assertEquals(List.of(), WorkspacePathAnchorExtractor.extract(logs, "/ws"));
    }

    @Test
    void capsAndDeduplicatesAnchors() {
        StringBuilder logs = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            logs.append("\tat p.SomeClass").append(i).append(".m(SomeClass").append(i).append(".java:1)\n");
        }
        logs.append("\tat p.SomeClass0.m(SomeClass0.java:1)\n").append("src/app.js:1:2\n");

        List<String> anchors = WorkspacePathAnchorExtractor.extract(logs.toString(), "/ws");

        assertEquals(WorkspacePathAnchorExtractor.MAX_ANCHORS, anchors.size(), anchors.toString());
        assertEquals(anchors.size(), new HashSet<>(anchors).size(), "anchors must be unique");
        assertEquals(1, anchors.stream().filter("src/main/java/p/SomeClass0.java"::equals).count(),
                anchors.toString());
    }

    @Test
    void nullAndEmptyLogsReturnNothing() {
        assertEquals(List.of(), WorkspacePathAnchorExtractor.extract(null, null));
        assertEquals(List.of(), WorkspacePathAnchorExtractor.extract("", null));
        assertEquals(List.of(), WorkspacePathAnchorExtractor.extract("   \n", null));
    }

    @Test
    void workspaceRootIsOptionalForRelativePaths() {
        List<String> anchors = WorkspacePathAnchorExtractor.extract(
                "FAILED tests/test_api.py::test_charge\nFile \"app/main.py\", line 1\n", null);

        assertTrue(anchors.contains("tests/test_api.py"), anchors.toString());
        assertTrue(anchors.contains("app/main.py"), anchors.toString());
    }

    @Test
    void windowsSeparatorsAreNormalized() {
        List<String> anchors = WorkspacePathAnchorExtractor.extract(
                "C:\\ws\\src\\main\\App.java:[1,1] error\n", "C:\\ws");

        assertTrue(anchors.contains("src/main/App.java"), anchors.toString());
        assertFalse(anchors.stream().anyMatch(path -> path.contains("\\")), anchors.toString());
    }

    private static List<String> extract(String logs) {
        return WorkspacePathAnchorExtractor.extract(logs, null);
    }
}
