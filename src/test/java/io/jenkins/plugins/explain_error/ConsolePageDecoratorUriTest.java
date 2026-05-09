package io.jenkins.plugins.explain_error;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConsolePageDecoratorUriTest {

    @Test
    void testPipelineGraphViewUriSupportsCurrentAndLegacyUrls() {
        assertTrue(ConsolePageDecorator.isPipelineGraphViewUri("/job/test/1/stages"));
        assertTrue(ConsolePageDecorator.isPipelineGraphViewUri("/job/test/1/stages/"));
        assertTrue(ConsolePageDecorator.isPipelineGraphViewUri("/job/test/1/pipeline-overview"));
        assertTrue(ConsolePageDecorator.isPipelineGraphViewUri("/job/test/1/pipeline-overview/"));
        assertFalse(ConsolePageDecorator.isPipelineGraphViewUri("/job/test/1/console"));
        assertFalse(ConsolePageDecorator.isPipelineGraphViewUri("/job/test/1/stages/log"));
    }
}
