package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.FilePath;

/** What the step asks the explainer to collect adaptively. */
record AdaptiveWorkspaceContextRequest(@NonNull FilePath workspace, int maxBytes) {

    AdaptiveWorkspaceContextRequest {
        if (maxBytes <= 0) {
            maxBytes = 0;
        }
    }
}
