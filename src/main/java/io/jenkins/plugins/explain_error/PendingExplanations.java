package io.jenkins.plugins.explain_error;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Run;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The builds whose explanation has been requested and has not come back yet, so that their pages
 * can say one is on its way instead of offering to request another. Automatic explanations are
 * added when they are queued; one requested from a page is added for as long as it runs.
 *
 * Kept in memory only: the request itself does not survive a restart, and a stored marker
 * would then claim an explanation is being generated forever. For the same reason a request
 * that never returns stops counting after {@link #MAX_AGE_MILLIS}.
 */
final class PendingExplanations {

    static final long MAX_AGE_MILLIS = TimeUnit.MINUTES.toMillis(10);

    /** Build to the time its explanation was requested. */
    private static final Map<String, Long> PENDING = new ConcurrentHashMap<>();

    private PendingExplanations() {
    }

    static void add(@NonNull Run<?, ?> run) {
        add(run, System.currentTimeMillis());
    }

    static void add(@NonNull Run<?, ?> run, long requestedAtMillis) {
        PENDING.put(run.getExternalizableId(), requestedAtMillis);
    }

    /**
     * Marks the build as pending unless it already is, in one step, so that two requests at the
     * same time cannot both go on to call the provider.
     *
     * @return whether the build was marked; {@code false} when an explanation is already on its way
     */
    static boolean tryAdd(@NonNull Run<?, ?> run) {
        String id = run.getExternalizableId();
        long now = System.currentTimeMillis();
        Long requestedAt = PENDING.putIfAbsent(id, now);
        if (requestedAt == null) {
            return true;
        }
        return now - requestedAt > MAX_AGE_MILLIS && PENDING.replace(id, requestedAt, now);
    }

    static void remove(@NonNull Run<?, ?> run) {
        PENDING.remove(run.getExternalizableId());
    }

    static boolean contains(@NonNull Run<?, ?> run) {
        String id = run.getExternalizableId();
        Long requestedAt = PENDING.get(id);
        if (requestedAt == null) {
            return false;
        }
        if (System.currentTimeMillis() - requestedAt > MAX_AGE_MILLIS) {
            PENDING.remove(id, requestedAt);
            return false;
        }
        return true;
    }
}
