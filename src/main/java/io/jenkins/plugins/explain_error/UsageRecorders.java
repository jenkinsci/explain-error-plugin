package io.jenkins.plugins.explain_error;

import com.google.common.annotations.VisibleForTesting;
import hudson.ExtensionList;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves configured usage recorders and dispatches events to them.
 */
public final class UsageRecorders {

    private static final Logger LOGGER = Logger.getLogger(UsageRecorders.class.getName());
    private static final UsageRecorder NO_OP = event -> {
    };

    private static Supplier<Iterable<UsageRecorder>> recorderSupplier = () -> ExtensionList.lookup(UsageRecorder.class);

    private UsageRecorders() {
    }

    public static UsageRecorder get() {
        List<UsageRecorder> recorders = new ArrayList<>();
        for (UsageRecorder recorder : recorderSupplier.get()) {
            recorders.add(recorder);
        }

        if (recorders.isEmpty()) {
            return NO_OP;
        }

        return event -> {
            for (UsageRecorder recorder : recorders) {
                try {
                    recorder.record(event);
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING, "Failed to record Explain Error usage event.", e);
                }
            }
        };
    }

    @VisibleForTesting
    static void setRecorderSupplier(Supplier<Iterable<UsageRecorder>> supplier) {
        recorderSupplier = Objects.requireNonNull(supplier);
    }

    @VisibleForTesting
    static void resetRecorderSupplier() {
        recorderSupplier = () -> ExtensionList.lookup(UsageRecorder.class);
    }
}
