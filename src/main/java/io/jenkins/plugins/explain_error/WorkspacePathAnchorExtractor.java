package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds workspace-relative file paths mentioned by a build log. Pure: no workspace access.
 */
final class WorkspacePathAnchorExtractor {

    static final int MAX_ANCHORS = 20;

    private static final String EXTENSIONS = "java|kt|kts|scala|groovy|py|pyi|js|jsx|ts|tsx|mjs|cjs|go|rs"
            + "|vue|svelte|rb|cs|xml|yaml|yml|json|properties|gradle|toml|mod|c|cpp|cc|h|hpp|php";

    private static final Pattern JAVA_FRAME = Pattern.compile(
            "^\\s*at\\s+([\\w$.]+)\\.([\\w$<>]+)\\(([\\w$]+\\.(?:java|kt|scala|groovy)):(\\d+)\\)",
            Pattern.MULTILINE);

    private static final Pattern PYTHON_FILE = Pattern.compile(
            "^\\s*File\\s+[\"']([^\"']+\\.pyi?)[\"'],\\s*line\\s+\\d+", Pattern.MULTILINE);

    private static final Pattern PYTEST_NODE = Pattern.compile(
            "^FAILED\\s+([^\\s:]+)::", Pattern.MULTILINE);

    private static final Pattern FILE_WITH_LOCATION = Pattern.compile(
            "(?<![\\w./-])((?:[A-Za-z]:)?[A-Za-z0-9_./-]+\\.(?:" + EXTENSIONS + "))"
                    + "(?::\\[?\\d+(?:,\\d+)?\\]?|\\s*\\(\\d+,\\d+\\))?",
            Pattern.MULTILINE);

    private static final WorkspaceContextCollector SAFETY = new WorkspaceContextCollector();

    private WorkspacePathAnchorExtractor() {
    }

    static List<String> extract(@CheckForNull String errorLogs, @CheckForNull String workspaceRoot) {
        if (errorLogs == null || errorLogs.isBlank()) {
            return List.of();
        }
        String logs = normalize(errorLogs);
        String root = workspaceRoot == null ? null : normalize(workspaceRoot);
        LinkedHashSet<String> anchors = new LinkedHashSet<>();
        collectJavaFrames(logs, anchors);
        collectPythonFiles(logs, root, anchors);
        collectPytestNodes(logs, anchors);
        collectGenericPaths(logs, root, anchors);
        return new ArrayList<>(anchors);
    }

    static List<String> extract(@CheckForNull String errorLogs) {
        return extract(errorLogs, null);
    }

    private static void collectJavaFrames(String logs, LinkedHashSet<String> anchors) {
        Matcher matcher = JAVA_FRAME.matcher(logs);
        while (matcher.find() && anchors.size() < MAX_ANCHORS) {
            String fqn = matcher.group(1).replaceAll("\\$.*$", "");
            String fileName = matcher.group(3).replaceAll("\\$[^/]*\\.", ".");
            String packagePath = packagePath(fqn);
            String bare = packagePath.isEmpty() ? fileName : packagePath + "/" + fileName;
            for (String root : sourceRoots(extension(fileName))) {
                add(anchors, root + "/" + bare);
            }
            add(anchors, bare);
        }
    }

    private static void collectPythonFiles(String logs, @CheckForNull String root, LinkedHashSet<String> anchors) {
        Matcher matcher = PYTHON_FILE.matcher(logs);
        while (matcher.find() && anchors.size() < MAX_ANCHORS) {
            add(anchors, relativize(matcher.group(1), root));
        }
    }

    private static void collectPytestNodes(String logs, LinkedHashSet<String> anchors) {
        Matcher matcher = PYTEST_NODE.matcher(logs);
        while (matcher.find() && anchors.size() < MAX_ANCHORS) {
            add(anchors, normalize(matcher.group(1)));
        }
    }

    private static void collectGenericPaths(String logs, @CheckForNull String root, LinkedHashSet<String> anchors) {
        Matcher matcher = FILE_WITH_LOCATION.matcher(logs);
        while (matcher.find() && anchors.size() < MAX_ANCHORS) {
            add(anchors, relativize(matcher.group(1), root));
        }
    }

    private static void add(LinkedHashSet<String> anchors, @CheckForNull String candidate) {
        if (candidate == null || anchors.size() >= MAX_ANCHORS) {
            return;
        }
        String path = normalize(candidate);
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        if (path.isEmpty() || isAbsolute(path) || path.contains(":") || path.contains("://")) {
            return;
        }
        if (path.contains("*") || path.contains("?") || path.contains("[") || path.contains("]")
                || path.contains("{") || path.contains("}")) {
            return;
        }
        for (String segment : path.split("/")) {
            if (segment.equals("..")) {
                return;
            }
        }
        if (SAFETY.pathError(path) != null) {
            return;
        }
        anchors.add(path);
    }

    @CheckForNull
    private static String relativize(@CheckForNull String path, @CheckForNull String root) {
        if (path == null) {
            return null;
        }
        String normalized = normalize(path);
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (!isAbsolute(normalized)) {
            return normalized;
        }
        if (root == null || root.isBlank()) {
            return null;
        }
        String base = normalize(root);
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty()) {
            return normalized.substring(1);
        }
        if (normalized.equals(base)) {
            return null;
        }
        String prefix = base + "/";
        if (!startsWithPath(normalized, prefix)) {
            return null;
        }
        return normalized.substring(prefix.length());
    }

    private static boolean startsWithPath(String value, String prefix) {
        if (value.length() >= 2 && prefix.length() >= 2 && value.charAt(1) == ':' && prefix.charAt(1) == ':') {
            return value.regionMatches(true, 0, prefix, 0, 2) && value.startsWith(prefix.substring(2), 2);
        }
        return value.startsWith(prefix);
    }

    private static boolean isAbsolute(String path) {
        return path.startsWith("/") || path.matches("^[A-Za-z]:/.*");
    }

    private static String normalize(String value) {
        return value.replace("file://", "").replace('\\', '/');
    }

    private static String packagePath(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot < 0 ? "" : fqn.substring(0, lastDot).replace('.', '/');
    }

    private static String extension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot < 0 ? "" : fileName.substring(lastDot + 1);
    }

    private static List<String> sourceRoots(String extension) {
        return switch (extension) {
            case "java" -> List.of("src/main/java", "src/test/java");
            case "kt", "kts" -> List.of("src/main/kotlin", "src/test/kotlin");
            case "scala" -> List.of("src/main/scala", "src/test/scala");
            case "groovy" -> List.of("src/main/groovy", "src/test/groovy");
            default -> List.of();
        };
    }
}
