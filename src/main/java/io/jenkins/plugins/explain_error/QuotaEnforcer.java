package io.jenkins.plugins.explain_error;

import com.google.common.annotations.VisibleForTesting;

/**
 * Thread-safe enforcer for rate-limiting real AI provider calls within a rolling time window.
 *
 * <p>The counter resets when the current wall-clock time passes the end of the window that was
 * active when the first call in that window was recorded.  Cache hits do not go through this
 * enforcer; only real provider calls consume quota.</p>
 */
public class QuotaEnforcer {

    private final Object lock = new Object();
    private long windowStart = 0L;
    private long callCount = 0L;

    /**
     * Attempts to acquire one quota slot for the given window and limit.
     *
     * @param window   the time window (hourly / daily)
     * @param maxCalls maximum number of provider calls allowed per window
     * @return {@code true} if the call is permitted (slot reserved), {@code false} if the quota
     *         has been reached for the current window
     */
    public boolean tryAcquire(QuotaWindow window, int maxCalls) {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            long windowDuration = window.getDurationMillis();
            if (now >= windowStart + windowDuration) {
                // Window has rolled over; start a fresh window
                windowStart = now;
                callCount = 0;
            }
            if (callCount >= maxCalls) {
                return false;
            }
            callCount++;
            return true;
        }
    }

    @VisibleForTesting
    long getCallCount() {
        synchronized (lock) {
            return callCount;
        }
    }

    @VisibleForTesting
    void reset() {
        synchronized (lock) {
            windowStart = 0L;
            callCount = 0L;
        }
    }
}
