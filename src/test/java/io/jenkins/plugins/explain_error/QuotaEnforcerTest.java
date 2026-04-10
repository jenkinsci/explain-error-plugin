package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QuotaEnforcerTest {

    @Test
    void allowsCallsUpToLimit() {
        QuotaEnforcer enforcer = new QuotaEnforcer();

        assertTrue(enforcer.tryAcquire(QuotaWindow.HOURLY, 3));
        assertTrue(enforcer.tryAcquire(QuotaWindow.HOURLY, 3));
        assertTrue(enforcer.tryAcquire(QuotaWindow.HOURLY, 3));
        assertFalse(enforcer.tryAcquire(QuotaWindow.HOURLY, 3));
        assertFalse(enforcer.tryAcquire(QuotaWindow.HOURLY, 3));
        assertEquals(3, enforcer.getCallCount());
    }

    @Test
    void zeroLimitRejectsImmediately() {
        QuotaEnforcer enforcer = new QuotaEnforcer();

        assertFalse(enforcer.tryAcquire(QuotaWindow.HOURLY, 0));
        assertEquals(0, enforcer.getCallCount());
    }

    @Test
    void resetClearsCounterAndAllowsNewCalls() {
        QuotaEnforcer enforcer = new QuotaEnforcer();
        enforcer.tryAcquire(QuotaWindow.HOURLY, 2);
        enforcer.tryAcquire(QuotaWindow.HOURLY, 2);
        assertFalse(enforcer.tryAcquire(QuotaWindow.HOURLY, 2));

        enforcer.reset();

        assertTrue(enforcer.tryAcquire(QuotaWindow.HOURLY, 2));
        assertEquals(1, enforcer.getCallCount());
    }

    @Test
    void windowRolloverResetsCount() {
        // Simulate a new time window by resetting the enforcer.
        QuotaEnforcer enforcer = new QuotaEnforcer();

        // Fill up the window
        assertTrue(enforcer.tryAcquire(QuotaWindow.HOURLY, 1));
        assertFalse(enforcer.tryAcquire(QuotaWindow.HOURLY, 1));

        // Simulate rollover by resetting
        enforcer.reset();

        assertTrue(enforcer.tryAcquire(QuotaWindow.HOURLY, 1));
        assertEquals(1, enforcer.getCallCount());
    }

    @Test
    void dailyWindowWorksTheSameWay() {
        QuotaEnforcer enforcer = new QuotaEnforcer();

        assertTrue(enforcer.tryAcquire(QuotaWindow.DAILY, 5));
        assertEquals(1, enforcer.getCallCount());
        assertFalse(enforcer.tryAcquire(QuotaWindow.DAILY, 1));
    }
}
