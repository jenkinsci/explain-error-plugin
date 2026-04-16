package io.jenkins.plugins.explain_error;

/**
 * Thread-safe counter that enforces a maximum number of AI provider calls within a rolling time window.
 *
 * <p>When {@link #tryAcquire(QuotaWindow, int)} is called:
 * <ol>
 *   <li>If the current time window has expired the counter is reset and the call is allowed.</li>
 *   <li>Otherwise the counter is incremented atomically. If the new count exceeds the configured
 *       maximum, the call is rejected.</li>
 * </ol>
 *
 * <p>The enforcer is intentionally stateless with respect to Jenkins configuration: callers
 * pass the window and limit on every invocation so the enforcer reacts immediately to
 * configuration changes without requiring a restart.
 */
public class QuotaEnforcer {

    private long windowStartMillis;
    private int count;

    public QuotaEnforcer() {
        this.windowStartMillis = System.currentTimeMillis();
        this.count = 0;
    }

    /**
     * Attempt to acquire a quota slot.
     *
     * @param window   the time window that governs this quota
     * @param maxCalls the maximum number of provider calls permitted within the window
     * @return {@code true} if the call is within the quota and should proceed;
     *         {@code false} if the quota has been exceeded and the call should be rejected
     */
    public synchronized boolean tryAcquire(QuotaWindow window, int maxCalls) {
        long now = System.currentTimeMillis();
        if (now - windowStartMillis >= window.getDurationMillis()) {
            windowStartMillis = now;
            count = 0;
        }
        if (count >= maxCalls) {
            return false;
        }
        count++;
        return true;
    }

    /**
     * Resets the quota counter and starts a fresh window.
     * Intended for testing and for manual admin resets.
     */
    public synchronized void reset() {
        windowStartMillis = System.currentTimeMillis();
        count = 0;
    }
}
