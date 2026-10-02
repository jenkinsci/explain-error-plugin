package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class UsageRecordersTest {

    @AfterEach
    void resetRecorders() {
        UsageRecorders.resetRecorderSupplier();
    }

    @Test
    void eventsAreDroppedWhenNoRecorderIsInstalled() {
        UsageRecorders.setRecorderSupplier(List::of);

        assertDoesNotThrow(() -> UsageRecorders.get().record(event()));
    }

    @Test
    void aFailingRecorderDoesNotStopTheOthers() {
        List<UsageEvent> recorded = new ArrayList<>();
        UsageRecorder failing = event -> {
            throw new IllegalStateException("metrics backend unavailable");
        };
        UsageRecorders.setRecorderSupplier(() -> List.of(failing, recorded::add));

        UsageEvent event = event();
        UsageRecorders.get().record(event);

        assertEquals(List.of(event), recorded);
    }

    private static UsageEvent event() {
        return new UsageEvent(System.currentTimeMillis(), UsageEvent.EntryPoint.CONSOLE_ACTION,
                UsageEvent.Result.SUCCESS, "Test", "test-model", 5, 10, false);
    }
}
