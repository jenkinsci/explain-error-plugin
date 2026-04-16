package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QuotaEnforcerTest {

    @Test
    void allowsCallsWithinLimit() {
        QuotaEnforcer enforcer = new QuotaEnforcer();
        assertTrue(enforcer.tryAcquire(QuotaWindow.HOURLY, 3));
        assertTrue(enforcer.tryAcquire(QuotaWindow.HOURLY, 3));
        assertTrue(enforcer.tryAcquire(QuotaWindow.HOURLY, 3));
    }

    @Test
    void rejectsCallAtLimit() {
        QuotaEnforcer enforcer = new QuotaEnforcer();
        assertTrue(enforcer.tryAcquire(QuotaWindow.HOURLY, 1));
        assertFalse(enforcer.tryAcquire(QuotaWindow.HOURLY, 1));
    }

    @Test
    void rejectsAllCallsWhenLimitIsZero() {
        QuotaEnforcer enforcer = new QuotaEnforcer();
        assertFalse(enforcer.tryAcquire(QuotaWindow.HOURLY, 0));
        assertFalse(enforcer.tryAcquire(QuotaWindow.DAILY, 0));
    }

    @Test
    void resetAllowsCallsAgainAfterLimitReached() {
        QuotaEnforcer enforcer = new QuotaEnforcer();

        // Fill up the window
        assertTrue(enforcer.tryAcquire(QuotaWindow.HOURLY, 1));
        assertFalse(enforcer.tryAcquire(QuotaWindow.HOURLY, 1));

        // Simulate window rollover by resetting
        enforcer.reset();
        assertTrue(enforcer.tryAcquire(QuotaWindow.HOURLY, 1));
    }

    @Test
    void dailyWindowEnforcesItsOwnLimit() {
        QuotaEnforcer enforcer = new QuotaEnforcer();
        assertTrue(enforcer.tryAcquire(QuotaWindow.DAILY, 2));
        assertTrue(enforcer.tryAcquire(QuotaWindow.DAILY, 2));
        assertFalse(enforcer.tryAcquire(QuotaWindow.DAILY, 2));
    }
}
