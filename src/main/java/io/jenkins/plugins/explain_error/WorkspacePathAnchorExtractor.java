package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds workspace-relative file paths, and the line they point at, mentioned by a build log.
 * Pure: no workspace access.
 */
final class WorkspacePathAnchorExtractor {

    /** A workspace-relative path named by the log, and the line it names, or 0 when it names none. */
    record Anchor(String path, int line) {
    }

    static final int MAX_ANCHORS = 60;

    private static final String EXTENSIONS = "java|kt|kts|scala|groovy|py|pyi|js|jsx|ts|tsx|mjs|cjs|go|rs"
            + "|vue|svelte|rb|cs|xml|yaml|yml|json|properties|gradle|toml|mod|c|cpp|cc|h|hpp|php";

    private static final Pattern JAVA_FRAME = Pattern.compile(
            "^\\s*at\\s+(?:[\\w.$@-]*/+)?([\\w$.]+)\\.([\\w$<>]+)\\(([\\w$]+\\.(?:java|kt|scala|groovy)):(\\d+)\\)",
            Pattern.MULTILINE);

    private static final Pattern STACK_FRAME_LINE = Pattern.compile("^\\s*at\\s+\\S+\\(.*\\)\\s*$",
            Pattern.MULTILINE);

    private static final Pattern PYTHON_FILE = Pattern.compile(
            "^\\s*File\\s+[\"']([^\"']+\\.pyi?)[\"'],\\s*line\\s+(\\d+)", Pattern.MULTILINE);

    private static final Pattern PYTEST_NODE = Pattern.compile(
            "^FAILED\\s+([^\\s:]+)::", Pattern.MULTILINE);

    private static final Pattern FILE_WITH_LOCATION = Pattern.compile(
            "(?<![\\w./-])((?:[A-Za-z]:)?[A-Za-z0-9_./-]+\\.(?:" + EXTENSIONS + "))"
                    + "(?::\\[?(\\d+)(?:,\\d+)?\\]?|\\s*\\((\\d+),\\d+\\))?",
            Pattern.MULTILINE);

    /**
     * Frames of the JDK, the JVM languages, test frameworks, build tools and Jenkins itself. Their
     * sources are never in the workspace, and a deep stack of them would otherwise use up the anchors
     * before the frames of the project's own code.
     */
    private static final List<String> LIBRARY_PACKAGES = List.of(
            "java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun.",
            "kotlin.", "kotlinx.", "scala.", "groovy.", "org.codehaus.groovy.", "org.apache.groovy.",
            "org.junit.", "junit.", "org.testng.", "org.opentest4j.", "org.assertj.", "org.hamcrest.",
            "org.mockito.", "org.spockframework.",
            "org.apache.maven.", "org.gradle.", "worker.org.gradle.",
            "hudson.", "jenkins.", "org.jenkinsci.", "org.kohsuke.");

    private static final WorkspaceContextCollector SAFETY = new WorkspaceContextCollector();

    private WorkspacePathAnchorExtractor() {
    }

    /**
     * Returns the anchors in the order the log names them, one per path. A path named more than once
     * keeps the first line given for it.
     */
    static List<Anchor> extract(@CheckForNull String errorLogs, @CheckForNull String workspaceRoot) {
        if (errorLogs == null || errorLogs.isBlank()) {
            return List.of();
        }
        String logs = normalize(errorLogs);
        String root = workspaceRoot == null ? null : normalize(workspaceRoot);
        Map<String, Integer> anchors = new LinkedHashMap<>();
        collectJavaFrames(logs, anchors);
        collectPythonFiles(logs, root, anchors);
        collectPytestNodes(logs, anchors);
        // A stack frame names a bare file name; its path was already derived above
        collectGenericPaths(STACK_FRAME_LINE.matcher(logs).replaceAll(""), root, anchors);
        List<Anchor> result = new ArrayList<>(anchors.size());
        anchors.forEach((path, line) -> result.add(new Anchor(path, line)));
        return result;
    }

    static List<String> extractPaths(@CheckForNull String errorLogs, @CheckForNull String workspaceRoot) {
        return extract(errorLogs, workspaceRoot).stream().map(Anchor::path).toList();
    }

    private static void collectJavaFrames(String logs, Map<String, Integer> anchors) {
        Matcher matcher = JAVA_FRAME.matcher(logs);
        while (matcher.find() && anchors.size() < MAX_ANCHORS) {
            String fqn = matcher.group(1).replaceAll("\\$.*$", "");
            if (isLibraryClass(fqn)) {
                continue;
            }
            String fileName = matcher.group(3).replaceAll("\\$[^/]*\\.", ".");
            int line = parseLine(matcher.group(4));
            String packagePath = packagePath(fqn);
            String bare = packagePath.isEmpty() ? fileName : packagePath + "/" + fileName;
            for (String sourceRoot : sourceRoots(extension(fileName))) {
                add(anchors, sourceRoot + "/" + bare, line);
            }
            add(anchors, bare, line);
        }
    }

    private static void collectPythonFiles(String logs, @CheckForNull String root, Map<String, Integer> anchors) {
        Matcher matcher = PYTHON_FILE.matcher(logs);
        while (matcher.find() && anchors.size() < MAX_ANCHORS) {
            add(anchors, relativize(matcher.group(1), root), parseLine(matcher.group(2)));
        }
    }

    private static void collectPytestNodes(String logs, Map<String, Integer> anchors) {
        Matcher matcher = PYTEST_NODE.matcher(logs);
        while (matcher.find() && anchors.size() < MAX_ANCHORS) {
            add(anchors, normalize(matcher.group(1)), 0);
        }
    }

    private static void collectGenericPaths(String logs, @CheckForNull String root, Map<String, Integer> anchors) {
        Matcher matcher = FILE_WITH_LOCATION.matcher(logs);
        while (matcher.find() && anchors.size() < MAX_ANCHORS) {
            String line = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            add(anchors, relativize(matcher.group(1), root), parseLine(line));
        }
    }

    private static boolean isLibraryClass(String fqn) {
        for (String prefix : LIBRARY_PACKAGES) {
            if (fqn.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static int parseLine(@CheckForNull String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void add(Map<String, Integer> anchors, @CheckForNull String candidate, int line) {
        if (candidate == null || anchors.size() >= MAX_ANCHORS) {
            return;
        }
        String path = normalize(candidate);
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        if (path.isEmpty() || isAbsolute(path) || path.contains(":")) {
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
        Integer known = anchors.get(path);
        if (known == null) {
            anchors.put(path, line);
        } else if (known == 0 && line > 0) {
            anchors.put(path, line);
        }
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
