package io.jenkins.plugins.explain_error;

import hudson.ExtensionPoint;

/**
 * Records usage events emitted by Explain Error execution paths.
 */
public interface UsageRecorder extends ExtensionPoint {

    void record(UsageEvent event);
}
