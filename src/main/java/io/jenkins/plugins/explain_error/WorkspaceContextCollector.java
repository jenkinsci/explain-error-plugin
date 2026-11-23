package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.FilePath;
import hudson.model.TaskListener;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    static final int MAX_FILES_PER_COLLECTION = 8;
    static final int EXCERPT_LINES_BEFORE = 40;
    static final int EXCERPT_LINES_AFTER = 20;
    private static final String CONFIGURED_FILES_INTRODUCTION = "WORKSPACE CONTEXT\n"
            + "The following files were selected from the Jenkins workspace. "
            + "Use them only as supporting context for the failure analysis.\n\n";
    private static final String LOG_NAMED_FILES_INTRODUCTION = "FILES NAMED BY THE FAILURE LOG\n"
            + "The failure log refers to the following files from the Jenkins workspace. Where the log gives "
            + "a line, the lines around it are shown. Use them only as supporting context for the failure "
            + "analysis.\n\n";

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
        List<FileEntry> rendered = new ArrayList<>();
        for (Map.Entry<String, FilePath> entry : entries) {
            rendered.add(new FileEntry(entry.getKey(), entry.getValue(), 0));
        }
        return render(rendered, maxBytes, listener, CONFIGURED_FILES_INTRODUCTION);
    }

    /**
     * Collects the files a build log names, in the order given, with the same format, per-file cap and
     * total budget as {@link #collect}. A file whose anchor names a line contributes the lines around
     * it, numbered, instead of its beginning; one whose anchor names no line, or a line past its end,
     * contributes its beginning as {@link #collect} does.
     */
    String collectAnchors(FilePath workspace, List<WorkspacePathAnchorExtractor.Anchor> anchors, int maxBytes,
                          TaskListener listener) throws IOException, InterruptedException {
        if (workspace == null || maxBytes <= 0 || anchors == null || anchors.isEmpty()) {
            return "";
        }
        List<FileEntry> entries = new ArrayList<>();
        for (WorkspacePathAnchorExtractor.Anchor anchor : selectExisting(workspace, anchors,
                MAX_FILES_PER_COLLECTION, listener)) {
            entries.add(new FileEntry(anchor.path(), workspace.child(anchor.path()), anchor.line()));
        }
        if (entries.isEmpty()) {
            return "";
        }
        return render(entries, maxBytes, listener, LOG_NAMED_FILES_INTRODUCTION);
    }

    /**
     * Returns the anchors whose paths are safe, exist and are regular files, in the order given and capped
     * at {@code maxFiles}. Symbolic links are rejected in every component of the path, because a link
     * inside the workspace can point outside it.
     */
    List<WorkspacePathAnchorExtractor.Anchor> selectExisting(FilePath workspace,
                                                             List<WorkspacePathAnchorExtractor.Anchor> anchors,
                                                             int maxFiles, @CheckForNull TaskListener listener)
            throws IOException, InterruptedException {
        List<WorkspacePathAnchorExtractor.Anchor> selected = new ArrayList<>();
        if (workspace == null || anchors == null || maxFiles <= 0) {
            return selected;
        }
        Set<String> seen = new HashSet<>();
        for (WorkspacePathAnchorExtractor.Anchor anchor : anchors) {
            if (selected.size() >= maxFiles) {
                break;
            }
            String path = normalizeSeparators(anchor.path().trim());
            if (path.isEmpty() || containsGlob(path) || pathError(path) != null || !seen.add(path)) {
                continue;
            }
            FilePath file = workspace.child(path);
            if (!file.exists() || file.isDirectory()) {
                continue;
            }
            if (isLinkedPath(workspace, path)) {
                log(listener, "Skipping workspace context file '" + path + "': symbolic links are not allowed");
                continue;
            }
            selected.add(new WorkspacePathAnchorExtractor.Anchor(path, anchor.line()));
        }
        return selected;
    }

    /**
     * Whether any component of the path is a symbolic link. A link in a parent directory can point
     * outside the workspace even when the leaf file is not itself a link.
     */
    private boolean isLinkedPath(FilePath workspace, String relativePath)
            throws IOException, InterruptedException {
        StringBuilder prefix = new StringBuilder();
        for (String segment : relativePath.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (prefix.length() > 0) {
                prefix.append('/');
            }
            prefix.append(segment);
            if (workspace.child(prefix.toString()).readLink() != null) {
                return true;
            }
        }
        return false;
    }

    /** The first safety problem of a relative path, or {@code null}. */
    @CheckForNull
    String pathError(String relativePath) {
        String error = validateRelativePath(relativePath);
        if (error != null) {
            return error;
        }
        return isSkippedPath(relativePath) ? "path is excluded" : null;
    }

    /** The lines around {@code line}, numbered, with the named line marked; {@code null} past the end. */
    @CheckForNull
    static Excerpt excerpt(String content, int line) {
        String[] lines = content.split("\r?\n", -1);
        if (line <= 0 || line > lines.length) {
            return null;
        }
        int first = Math.max(1, line - EXCERPT_LINES_BEFORE);
        int last = Math.min(lines.length, line + EXCERPT_LINES_AFTER);
        int width = String.valueOf(last).length();
        StringBuilder text = new StringBuilder();
        for (int number = first; number <= last; number++) {
            if (number > first) {
                text.append('\n');
            }
            String label = String.valueOf(number);
            text.append(" ".repeat(width - label.length())).append(label)
                    .append(number == line ? " > " : " | ").append(lines[number - 1]);
        }
        return new Excerpt(text.toString(), first, last);
    }

    record Excerpt(String text, int firstLine, int lastLine) {}

    private record FileEntry(String path, FilePath file, int line) {}

    private String render(List<FileEntry> entries, int maxBytes, TaskListener listener, String introduction)
            throws IOException, InterruptedException {
        StringBuilder context = new StringBuilder(introduction);

        int remainingBytes = maxBytes - byteLength(context.toString());
        for (FileEntry entry : entries) {
            if (remainingBytes <= 0) {
                break;
            }

            String fileContent = readFile(entry.file(), listener);
            if (fileContent == null) {
                continue;
            }

            String title = entry.path();
            Excerpt excerpt = entry.line() > 0 ? excerpt(fileContent, entry.line()) : null;
            if (excerpt != null) {
                fileContent = excerpt.text();
                title += " (lines " + excerpt.firstLine() + "-" + excerpt.lastLine()
                        + ", line " + entry.line() + " is marked with >)";
            }

            String header = "### " + title + "\n```text\n";
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

    String validateRelativePath(String relativePath) {
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

    boolean isSkippedPath(String relativePath) {
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
