package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.XmlFile;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * UsageRecorder that accumulates cumulative provider call statistics to disk, allowing
 * administrators to reconcile usage against AI provider billing statements.
 *
 * <p>Counts are persisted in {@code $JENKINS_HOME/explain-error-billing.xml} and survive
 * Jenkins restarts.  Each entry is keyed by {@code "<providerName>/<model>"} and records
 * the total number of successful real provider calls (cache hits and rejected calls are
 * tracked separately).</p>
 */
@Extension
public class BillingReconciliationRecorder implements UsageRecorder {

    private static final Logger LOGGER = Logger.getLogger(BillingReconciliationRecorder.class.getName());
    static final String STORAGE_FILE = "explain-error-billing.xml";

    /** In-memory map: key="providerName/model", value=cumulative successful calls. */
    private final ConcurrentHashMap<String, Long> successCalls = new ConcurrentHashMap<>();
    /** In-memory map: key="providerName/model", value=cumulative fallback calls. */
    private final ConcurrentHashMap<String, Long> fallbackCalls = new ConcurrentHashMap<>();
    /** Total quota-rejected calls (global + folder + user). */
    private final AtomicLong quotaRejectedTotal = new AtomicLong();

    public BillingReconciliationRecorder() {
        load();
    }

    @Override
    public void record(@NonNull UsageEvent event) {
        String key = event.providerName() + "/" + event.model();
        switch (event.result()) {
            case SUCCESS -> {
                successCalls.merge(key, 1L, Long::sum);
                persist();
            }
            case FALLBACK -> {
                fallbackCalls.merge(key, 1L, Long::sum);
                persist();
            }
            case QUOTA_REJECTED, USER_QUOTA_REJECTED -> {
                quotaRejectedTotal.incrementAndGet();
                persist();
            }
            default -> { /* not tracked for billing */ }
        }
    }

    /**
     * Returns a snapshot of cumulative successful calls keyed by "providerName/model".
     * The map is ordered by insertion order.
     */
    public Map<String, Long> getSuccessCallsByProvider() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(successCalls));
    }

    /**
     * Returns a snapshot of cumulative fallback calls keyed by "providerName/model".
     */
    public Map<String, Long> getFallbackCallsByProvider() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(fallbackCalls));
    }

    /** Returns the cumulative count of quota-rejected calls across all quota types. */
    public long getQuotaRejectedTotal() {
        return quotaRejectedTotal.get();
    }

    /** Retrieves the singleton instance registered as a Jenkins extension. */
    public static BillingReconciliationRecorder get() {
        return Jenkins.get().getExtensionList(UsageRecorder.class)
                .get(BillingReconciliationRecorder.class);
    }

    /** Resets all accumulated statistics and removes the persisted file. */
    public synchronized void resetStats() {
        successCalls.clear();
        fallbackCalls.clear();
        quotaRejectedTotal.set(0L);
        XmlFile file = dataFile();
        if (file.exists()) {
            try {
                Files.deleteIfExists(file.getFile().toPath());
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to delete billing reconciliation data", e);
            }
        }
    }

    // ── persistence ──────────────────────────────────────────────────────────

    private XmlFile dataFile() {
        return new XmlFile(new File(Jenkins.get().getRootDir(), STORAGE_FILE));
    }

    private synchronized void persist() {
        BillingData data = new BillingData(
                new LinkedHashMap<>(successCalls),
                new LinkedHashMap<>(fallbackCalls),
                quotaRejectedTotal.get());
        try {
            dataFile().write(data);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to persist billing reconciliation data", e);
        }
    }

    private synchronized void load() {
        XmlFile file = dataFile();
        if (!file.exists()) {
            return;
        }
        try {
            BillingData data = (BillingData) file.read();
            if (data != null) {
                if (data.successCalls != null) {
                    successCalls.putAll(data.successCalls);
                }
                if (data.fallbackCalls != null) {
                    fallbackCalls.putAll(data.fallbackCalls);
                }
                quotaRejectedTotal.set(data.quotaRejectedTotal);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load billing reconciliation data", e);
        }
    }

    /** JAXB-friendly data carrier for XmlFile serialisation. */
    private static class BillingData {
        Map<String, Long> successCalls;
        Map<String, Long> fallbackCalls;
        long quotaRejectedTotal;

        @SuppressWarnings("unused") // required by XStream
        BillingData() {
        }

        BillingData(Map<String, Long> successCalls, Map<String, Long> fallbackCalls, long quotaRejectedTotal) {
            this.successCalls = successCalls;
            this.fallbackCalls = fallbackCalls;
            this.quotaRejectedTotal = quotaRejectedTotal;
        }
    }
}
