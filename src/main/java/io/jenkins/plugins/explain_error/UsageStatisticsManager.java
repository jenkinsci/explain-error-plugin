package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Tracks AI explanation call statistics globally.
 * Persists to UsageStatisticsManager.xml via XStream.
 *
 * Serialization pattern (XStream cannot handle AtomicLong/ConcurrentHashMap directly):
 *
 *   recordCall()
 *     ├── increment transient AtomicLong totalCalls
 *     ├── update transient ConcurrentHashMap dailyCounts (yyyy-MM-dd key, UTC)
 *     ├── update transient ConcurrentHashMap perJobCounts (job full name key)
 *     └── scheduleSave() — debounced 5s via Jenkins Timer
 *
 *   save() override
 *     ├── pruneOldDays() — drop dailyCounts entries older than 31 days
 *     ├── snapshot AtomicLong  → long         (totalCallsValue)
 *     ├── snapshot dailyCounts → Map<String,Long> (dailyCountsMap)
 *     ├── snapshot perJobCounts → Map<String,Long> (perJobCountsMap)
 *     └── super.save() — writes XML
 *
 *   readResolve()
 *     └── rebuild transient ConcurrentHashMap + AtomicLong from persistent plain fields
 */
@Extension
@Symbol("explainErrorUsage")
public class UsageStatisticsManager extends GlobalConfiguration {

    private static final Logger LOGGER = Logger.getLogger(UsageStatisticsManager.class.getName());

    /** Shared daemon timer for deferred saves. Daemon so it doesn't block JVM shutdown. */
    private static final Timer SAVE_TIMER = new Timer("explain-error-stats-save", /* daemon= */ true);

    /** Delay before flushing stats to disk (debounces rapid concurrent calls). */
    static final long SAVE_DELAY_MS = 5000L;

    /** Number of days of daily-resolution history to retain. */
    static final int PRUNE_DAYS = 31;

    private static final int TOP_JOBS_LIMIT = 10;

    // Date formatter: yyyy-MM-dd in UTC. ISO-8601 format allows lexicographic == chronological comparison.
    private static final DateTimeFormatter DAY_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    // --- Persistent fields (XStream-serializable) ---
    // package-private for test reset in setUp()
    long totalCallsValue = 0L;
    Map<String, Long> dailyCountsMap = new HashMap<>();
    Map<String, Long> perJobCountsMap = new HashMap<>();

    // --- Transient working fields (rebuilt by readResolve after each load) ---
    // package-private for direct manipulation in pruneOldDays tests
    transient AtomicLong totalCalls;
    transient ConcurrentHashMap<String, AtomicLong> dailyCounts;
    transient ConcurrentHashMap<String, AtomicLong> perJobCounts;
    private transient AtomicReference<TimerTask> pendingSave;

    public UsageStatisticsManager() {
        load();
    }

    /** Returns the singleton instance, or null if Jenkins is not running. */
    public static UsageStatisticsManager get() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        return (jenkins != null) ? jenkins.getDescriptorByType(UsageStatisticsManager.class) : null;
    }

    /**
     * Rebuilds transient concurrent types from the XStream-deserialized plain fields.
     * Called by XStream after deserialization and explicitly guarded via ensureInitialized().
     */
    public Object readResolve() {
        this.totalCalls = new AtomicLong(totalCallsValue);
        this.dailyCounts = new ConcurrentHashMap<>();
        if (dailyCountsMap != null) {
            dailyCountsMap.forEach((k, v) -> dailyCounts.put(k, new AtomicLong(v)));
        }
        this.perJobCounts = new ConcurrentHashMap<>();
        if (perJobCountsMap != null) {
            perJobCountsMap.forEach((k, v) -> perJobCounts.put(k, new AtomicLong(v)));
        }
        this.pendingSave = new AtomicReference<>();
        return this;
    }

    /**
     * Records one AI explanation attempt. Counts both successes and failures — both
     * represent a billable API call to the underlying provider.
     *
     * @param jobFullName job full name (e.g. "folder/my-job"); "unknown" if null
     * @param timestamp   call instant; Instant.now() if null
     */
    public void recordCall(String jobFullName, Instant timestamp) {
        ensureInitialized();
        String job = (jobFullName != null) ? jobFullName : "unknown";
        Instant ts = (timestamp != null) ? timestamp : Instant.now();
        String dayKey = DAY_FMT.format(ts);

        totalCalls.incrementAndGet();
        dailyCounts.computeIfAbsent(dayKey, k -> new AtomicLong(0L)).incrementAndGet();
        perJobCounts.computeIfAbsent(job, k -> new AtomicLong(0L)).incrementAndGet();

        scheduleSave();
    }

    /**
     * Debounced save: cancels any pending flush and reschedules for SAVE_DELAY_MS from now.
     * Safe to call from multiple threads concurrently.
     */
    private void scheduleSave() {
        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                UsageStatisticsManager.this.save();
            }
        };
        TimerTask old = pendingSave.getAndSet(task);
        if (old != null) old.cancel();
        try {
            SAVE_TIMER.schedule(task, SAVE_DELAY_MS);
        } catch (IllegalStateException ignored) {
            // A concurrent scheduleSave() cancelled this task before we could schedule it.
            // A newer task is already scheduled — safe to ignore.
        }
    }

    /**
     * Snapshots concurrent working fields to XStream-serializable fields,
     * prunes stale daily entries, then calls super.save() to write XML.
     */
    @Override
    public synchronized void save() {
        ensureInitialized();
        try {
            pruneOldDays();
            this.totalCallsValue = totalCalls.get();
            Map<String, Long> dailySnap = new HashMap<>();
            dailyCounts.forEach((k, v) -> dailySnap.put(k, v.get()));
            this.dailyCountsMap = dailySnap;
            Map<String, Long> jobSnap = new HashMap<>();
            perJobCounts.forEach((k, v) -> jobSnap.put(k, v.get()));
            this.perJobCountsMap = jobSnap;
            super.save();
        } catch (Exception e) {
            LOGGER.warning("Failed to persist usage statistics: " + e.getMessage());
        }
    }

    /**
     * Removes dailyCounts entries older than PRUNE_DAYS.
     * Lexicographic string comparison is equivalent to chronological comparison for yyyy-MM-dd keys.
     * Called from save() — not in the recordCall() hot path.
     */
    void pruneOldDays() {
        String cutoff = DAY_FMT.format(Instant.now().minusSeconds((long) PRUNE_DAYS * 86400));
        dailyCounts.entrySet().removeIf(entry -> {
            try {
                return entry.getKey().compareTo(cutoff) < 0;
            } catch (Exception e) {
                return false; // keep malformed keys
            }
        });
    }

    // --- View helpers (read by config.jelly) ---

    public long getTotalCalls() {
        ensureInitialized();
        return totalCalls.get();
    }

    /** Returns total calls made during the current calendar month (UTC). */
    public long getCallsThisMonth() {
        ensureInitialized();
        String monthPrefix = DAY_FMT.format(Instant.now()).substring(0, 7); // "yyyy-MM"
        return dailyCounts.entrySet().stream()
                .filter(e -> e.getKey().startsWith(monthPrefix))
                .mapToLong(e -> e.getValue().get())
                .sum();
    }

    /** Returns top jobs by call count, sorted descending, capped at 10. */
    public List<Map.Entry<String, Long>> getTopJobs() {
        ensureInitialized();
        List<Map.Entry<String, Long>> entries = new ArrayList<>();
        perJobCounts.forEach((k, v) -> entries.add(Map.entry(k, v.get())));
        entries.sort(Map.Entry.<String, Long>comparingByValue().reversed());
        return entries.subList(0, Math.min(TOP_JOBS_LIMIT, entries.size()));
    }

    /** Resets all statistics to zero. Package-private for test setup only. */
    void resetForTesting() {
        this.totalCallsValue = 0L;
        this.dailyCountsMap = new HashMap<>();
        this.perJobCountsMap = new HashMap<>();
        readResolve();
    }

    /** Guards against deserialization edge cases where XStream skips readResolve on the root object. */
    private void ensureInitialized() {
        if (totalCalls == null) {
            readResolve();
        }
    }

    @Override
    @NonNull
    public String getDisplayName() {
        return "Explain Error Plugin - Usage Statistics";
    }
}
