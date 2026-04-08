package io.jenkins.plugins.explain_error;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe per-user quota enforcer that maintains independent rolling-window counters
 * for each user identity.
 *
 * <p>Each user gets their own {@link QuotaEnforcer} instance so that one heavy user cannot
 * exhaust the quota for everyone else.</p>
 */
public class UserQuotaEnforcer {

    private final ConcurrentHashMap<String, QuotaEnforcer> perUserEnforcers = new ConcurrentHashMap<>();

    /**
     * Attempts to acquire one quota slot for the given user.
     *
     * @param userId   the unique user identifier (typically {@code Authentication#getName()})
     * @param window   the quota time window
     * @param maxCalls maximum calls per window per user
     * @return {@code true} if the call is permitted, {@code false} if the user's quota is exhausted
     */
    public boolean tryAcquire(String userId, QuotaWindow window, int maxCalls) {
        QuotaEnforcer enforcer = perUserEnforcers.computeIfAbsent(userId, k -> new QuotaEnforcer());
        return enforcer.tryAcquire(window, maxCalls);
    }

    /** Clears all per-user counters. Used for testing. */
    void resetAll() {
        perUserEnforcers.clear();
    }
}
