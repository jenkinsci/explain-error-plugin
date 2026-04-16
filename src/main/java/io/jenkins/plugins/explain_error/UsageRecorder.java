package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionPoint;

/**
 * Records usage events emitted by Explain Error execution paths.
 */
public interface UsageRecorder extends ExtensionPoint {

    void record(@NonNull UsageEvent event);
}
