package com.scarasol.acceleratednavigation.topology;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TopologySegmentTraceTest {
    private static Map<String, Object> complete() {
        return Map.of("status", "BOUNDED", "writer", "complete", "callBeforeNanos", System.nanoTime());
    }

    @Test void serialDurationsReconstructMeasuredPrefixAndRejectMissingOrRepeatedDelivery() {
        long start = System.nanoTime();
        var service = new TopologySegmentTrace.Request(); var worker = new TopologySegmentTrace.Request();
        service.submit(); var flight = new TopologySegmentTrace.Flight(); worker.bind(9, flight);
        flight.taskStart(); flight.taskEnd(); flight.complete = System.nanoTime();
        worker.workerReady(); service.queued(); service.serverStart();
        var boundary = complete(); var row = service.snapshot(1, 2, worker, start, boundary);
        assertEquals("COMPLETE", row.get("status"));
        var durations = (Map<?, ?>) row.get("durations");
        assertEquals((long) boundary.get("callBeforeNanos") - start,
                durations.values().stream().mapToLong(v -> ((Number) v).longValue()).sum());
        service.queued();
        var repeated = service.snapshot(1, 2, worker, start, complete());
        assertEquals("UNAVAILABLE", repeated.get("status"));
        assertTrue(((Map<?, ?>) repeated.get("durations")).values().stream().allMatch(v -> v == null));
        assertEquals("UNAVAILABLE", new TopologySegmentTrace.Request().snapshot(3, 4, worker, start, Map.of()).get("status"));
    }

    @Test void sharedFlightHasIndependentOwnersAndLateBindingCannotResurrectClearedRequest() {
        var first = new TopologySegmentTrace.Request(); var second = new TopologySegmentTrace.Request();
        var flight = new TopologySegmentTrace.Flight(); first.bind(1, flight); second.bind(1, flight);
        first.clear(); first.bind(2, new TopologySegmentTrace.Flight()); first.workerReady();
        assertTrue(first.cleared()); assertEquals(0, first.retainedFlights());
        assertFalse(second.cleared()); assertEquals(1, second.retainedFlights());
        assertEquals("UNAVAILABLE", second.snapshot(1, 2, first, System.nanoTime(), complete()).get("status"));
    }

    @Test void previousSharedSearchOrExceptionDoesNotPretendToBeSerialSuccess() {
        var old = new TopologySegmentTrace.Flight(); long start = System.nanoTime();
        var service = new TopologySegmentTrace.Request(); var worker = new TopologySegmentTrace.Request();
        service.submit(); worker.bind(4, old); old.complete = System.nanoTime();
        worker.workerReady(); service.queued(); service.serverStart();
        var row = service.snapshot(1, 2, worker, start, complete());
        assertTrue(((List<?>) row.get("issues")).contains("MISSING_OR_UNORDERED_ANCHORS"));
        var failure = service.snapshot(1, 2, worker, start, Map.of("status", "BOUNDED", "writer", "exception", "callBeforeNanos", System.nanoTime()));
        assertTrue(((List<?>) failure.get("issues")).contains("NON_NORMAL_COMPLETION"));
    }

    @Test void activeTaskIsExplicitAndOverlappingTasksAccumulateIndependently() throws Exception {
        var flight = new TopologySegmentTrace.Flight(); flight.taskStart();
        Thread other = new Thread(() -> { flight.taskStart(); flight.taskEnd(); }); other.start(); other.join(5000);
        assertFalse(other.isAlive()); assertEquals(1, flight.snapshot(1).get("activeTasks"));
        flight.taskEnd(); assertEquals(2, flight.snapshot(1).get("taskCount"));
        assertEquals(0, flight.snapshot(1).get("activeTasks"));
        assertTrue((long) flight.snapshot(1).get("taskNanos") >= 0);
    }

    @Test void tickCadenceBracketsRequestAndRejectsGapsMissingCoverageAndDiscardedHistory() {
        var timeline = new TopologySegmentTrace.TickTimeline();
        timeline.record(100, 10); timeline.record(50_000_100, 11); timeline.record(150_000_100, 12);
        var row = timeline.window(200, 100_000_000);
        assertEquals("COVERED", row.get("status")); assertEquals(100_000_000L, row.get("maxIntervalNanos"));
        assertEquals(50_000_000L, row.get("maxExcessOverNominalNanos"));
        assertEquals("INCOMPLETE", timeline.window(99, 200).get("status"));
        assertEquals("INCOMPLETE", timeline.window(200, 200_000_000).get("status"));
        timeline.record(200_000_100, 14);
        assertEquals("INCOMPLETE", timeline.window(150_000_200, 180_000_000).get("status"));
        timeline.clear(); assertEquals("INCOMPLETE", timeline.window(200, 300).get("status"));
        for (int i = 0; i < 3000; i++) timeline.record(i * 50_000_000L, i);
        assertEquals("INCOMPLETE", timeline.window(1, 100).get("status"));
        assertEquals("COVERED", timeline.window(2997L * 50_000_000 + 1, 2998L * 50_000_000 + 1).get("status"));
    }

    @Test void clockAnchorBracketsWallClockRead() {
        long before = System.nanoTime(); long wallBefore = System.currentTimeMillis();
        var anchor = TopologySegmentTrace.clockAnchor(); long wallAfter = System.currentTimeMillis(); long after = System.nanoTime();
        assertTrue(anchor.get("nanoBefore") >= before); assertTrue(anchor.get("nanoAfter") <= after);
        assertEquals(anchor.get("nanoAfter") - anchor.get("nanoBefore"), anchor.get("readSpanNanos"));
        assertTrue(anchor.get("epochMillis") >= wallBefore && anchor.get("epochMillis") <= wallAfter);
    }
}
