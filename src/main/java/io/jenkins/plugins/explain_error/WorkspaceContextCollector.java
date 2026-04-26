package io.jenkins.plugins.explain_error;

import hudson.FilePath;
import hudson.model.TaskListener;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Collects a small, explicitly allowed set of workspace files for AI analysis.
 */
class WorkspaceContextCollector {

    static final String DEFAULT_PATHS =
            "pom.xml,build.gradle,build.gradle.kts,package.json,Jenkinsfile,Dockerfile,*.yml,*.yaml,*.properties";
    static final int DEFAULT_MAX_BYTES = 20_000;

    private static final Logger LOGGER = Logger.getLogger(WorkspaceContextCollector.class.getName());
    private static final int MAX_BYTES_PER_FILE = 8_000;

    String collect(FilePath workspace, String pathPatterns, int maxBytes, TaskListener listener)
            throws IOException, InterruptedException {
        if (workspace == null || maxBytes <= 0) {
            return "";
        }

        List<String> patterns = parsePatterns(pathPatterns);
        if (patterns.isEmpty()) {
            return "";
        }

        Map<String, FilePath> files = new LinkedHashMap<>();
        for (String pattern : patterns) {
            String pathError = validatePattern(pattern);
            if (pathError != null) {
                log(listener, "Skipping workspace context pattern '" + pattern + "': " + pathError);
                continue;
            }
            collectMatches(workspace, pattern, files, listener);
        }

        if (files.isEmpty()) {
            return "";
        }

        List<Map.Entry<String, FilePath>> entries = new ArrayList<>(files.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));

        StringBuilder context = new StringBuilder();
        context.append("WORKSPACE CONTEXT\n");
        context.append("The following files were selected from the Jenkins workspace. ")
                .append("Use them only as supporting context for the failure analysis.\n\n");

        int remainingBytes = maxBytes - byteLength(context.toString());
        for (Map.Entry<String, FilePath> entry : entries) {
            if (remainingBytes <= 0) {
                break;
            }

            String fileContent = readFile(entry.getValue(), listener);
            if (fileContent == null) {
                continue;
            }

            String header = "### " + entry.getKey() + "\n```text\n";
            String footer = "\n```\n\n";
            int overheadBytes = byteLength(header) + byteLength(footer);
            if (overheadBytes >= remainingBytes) {
                break;
            }

            int markerBudget = byteLength("\n...[truncated]");
            int fileBudget = Math.min(MAX_BYTES_PER_FILE, remainingBytes - overheadBytes - markerBudget);
            if (fileBudget <= 0) {
                break;
            }
            TruncatedText truncated = truncateUtf8(fileContent, fileBudget);
            context.append(header).append(truncated.text());
            if (truncated.truncated()) {
                context.append("\n...[truncated]");
            }
            context.append(footer);
            remainingBytes = maxBytes - byteLength(context.toString());
        }

        return context.toString().stripTrailing();
    }

    private List<String> parsePatterns(String pathPatterns) {
        String effectivePatterns = pathPatterns == null || pathPatterns.isBlank() ? DEFAULT_PATHS : pathPatterns;
        List<String> patterns = new ArrayList<>();
        for (String rawPattern : effectivePatterns.split(",")) {
            String pattern = rawPattern.trim();
            if (!pattern.isEmpty()) {
                patterns.add(pattern);
            }
        }
        return patterns;
    }

    private void collectMatches(FilePath workspace, String pattern, Map<String, FilePath> files, TaskListener listener)
            throws IOException, InterruptedException {
        if (containsGlob(pattern)) {
            FilePath[] matches = workspace.list(pattern);
            for (FilePath match : matches) {
                addIfSafe(workspace, match, files, listener);
            }
            return;
        }

        FilePath file = workspace.child(pattern);
        if (file.exists()) {
            addIfSafe(workspace, file, files, listener);
        }
    }

    private void addIfSafe(FilePath workspace, FilePath file, Map<String, FilePath> files, TaskListener listener)
            throws IOException, InterruptedException {
        if (file.isDirectory()) {
            return;
        }
        String relativePath = relativePath(workspace, file);
        String pathError = validateRelativePath(relativePath);
        if (pathError != null) {
            log(listener, "Skipping workspace context file '" + relativePath + "': " + pathError);
            return;
        }
        if (isSkippedPath(relativePath)) {
            log(listener, "Skipping workspace context file '" + relativePath + "': path is excluded");
            return;
        }
        files.putIfAbsent(relativePath, file);
    }

    private String relativePath(FilePath workspace, FilePath file) {
        String base = normalizeSeparators(workspace.getRemote());
        String remote = normalizeSeparators(file.getRemote());
        if (remote.equals(base)) {
            return "";
        }
        if (remote.startsWith(base + "/")) {
            return remote.substring(base.length() + 1);
        }
        return remote;
    }

    private String validatePattern(String pattern) {
        if (pattern.startsWith("/") || pattern.matches("^[A-Za-z]:[/\\\\].*")) {
            return "absolute paths are not allowed";
        }
        return validateRelativePath(pattern);
    }

    private String validateRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return "blank paths are not allowed";
        }
        String normalized = normalizeSeparators(relativePath);
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:/.*")) {
            return "absolute paths are not allowed";
        }
        if (escapesWorkspace(normalized)) {
            return "path traversal is not allowed";
        }
        return null;
    }

    private boolean escapesWorkspace(String relativePath) {
        int depth = 0;
        for (String segment : relativePath.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                depth--;
                if (depth < 0) {
                    return true;
                }
                continue;
            }
            depth++;
        }
        return false;
    }

    private boolean isSkippedPath(String relativePath) {
        String normalized = normalizeSeparators(relativePath).toLowerCase(Locale.ROOT);
        String fileName = normalized;
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            fileName = normalized.substring(slash + 1);
        }

        if (fileName.startsWith(".env") || fileName.startsWith("credentials") || fileName.startsWith("secrets")) {
            return true;
        }

        for (String segment : normalized.split("/")) {
            if (segment.equals(".git")
                    || segment.equals(".gradle")
                    || segment.equals("target")
                    || segment.equals("build")
                    || segment.equals("dist")
                    || segment.equals("node_modules")) {
                return true;
            }
        }
        return false;
    }

    private String readFile(FilePath file, TaskListener listener) throws IOException, InterruptedException {
        try {
            return file.readToString();
        } catch (IOException e) {
            log(listener, "Skipping workspace context file '" + file.getRemote() + "': " + e.getMessage());
            LOGGER.log(Level.FINE, "Failed to read workspace context file " + file.getRemote(), e);
            return null;
        }
    }

    private boolean containsGlob(String pattern) {
        return pattern.indexOf('*') >= 0
                || pattern.indexOf('?') >= 0
                || pattern.indexOf('[') >= 0
                || pattern.indexOf('{') >= 0;
    }

    private String normalizeSeparators(String path) {
        return path.replace('\\', '/');
    }

    private int byteLength(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private TruncatedText truncateUtf8(String value, int maxBytes) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return new TruncatedText(value, false);
        }
        int end = Math.max(0, maxBytes);
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new TruncatedText(new String(bytes, 0, end, StandardCharsets.UTF_8), true);
    }

    private void log(TaskListener listener, String message) {
        if (listener != null) {
            listener.getLogger().println("[explain-error] " + message);
        }
    }

    private record TruncatedText(String text, boolean truncated) {}
}
