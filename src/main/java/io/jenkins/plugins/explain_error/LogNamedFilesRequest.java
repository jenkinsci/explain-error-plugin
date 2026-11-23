package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.FilePath;

/** The workspace in which to look for the files a failure log names, and the bytes they may take. */
record LogNamedFilesRequest(@NonNull FilePath workspace, int maxBytes) {
}
