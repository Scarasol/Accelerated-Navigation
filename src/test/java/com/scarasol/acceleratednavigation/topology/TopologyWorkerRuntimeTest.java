package com.scarasol.acceleratednavigation.topology;

import net.minecraft.SharedConstants;
import net.minecraft.core.SectionPos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TopologyWorkerRuntimeTest {
    private static TopologyWorkerRuntime.ClusterKey KEY;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        KEY = new TopologyWorkerRuntime.ClusterKey(Level.OVERWORLD, SectionPos.of(-2, 3, 4));
    }

    @Test
    void oldParentCompletionCannotClearTheSuccessorTaskOrInputs() throws Exception {
        try (RuntimeCase test = new RuntimeCase()) {
            Class<?> keyType = Class.forName(TopologyWorkerRuntime.class.getName() + "$SuperCacheKey");
            Class<?> entryType = Class.forName(TopologyWorkerRuntime.class.getName() + "$SuperEntry");
            var keyConstructor = keyType.getDeclaredConstructor(net.minecraft.resources.ResourceKey.class,
                    SectionPos.class, BaseClusterTopology.GeometryKey.class, BaseClusterTopology.MovementKey.class);
            keyConstructor.setAccessible(true);
            var profile = BaseClusterTopology.TraversalProfile.DEFAULT_GROUND;
            Object key = keyConstructor.newInstance(Level.OVERWORLD, SectionPos.of(0, 0, 0),
                    profile.geometry(BaseClusterTopology.Channel.GROUND), profile.movement(BaseClusterTopology.Channel.GROUND));
            var entryConstructor = entryType.getDeclaredConstructor(TopologyWorkerRuntime.class, keyType);
            entryConstructor.setAccessible(true);
            Object entry = entryConstructor.newInstance(test.runtime, key);
            TopologyTaskExecutor.TaskHandle successor = completion -> completion.accept(false);
            field(entryType, "attempt").setLong(entry, 2);
            field(entryType, "attemptRunning").setBoolean(entry, true);
            field(entryType, "buildTask").set(entry, successor);
            field(entryType, "buildInputAttempt").setLong(entry, 1);
            field(entryType, "buildInputs").set(entry, new BaseClusterTopology[0]);
            Method publish = TopologyWorkerRuntime.class.getDeclaredMethod("publishSuperCluster",
                    keyType, entryType, long.class, BaseClusterTopology[].class, SuperClusterTopology.class);
            Method fail = TopologyWorkerRuntime.class.getDeclaredMethod("failSuperBuild",
                    keyType, entryType, long.class, Throwable.class);
            publish.setAccessible(true); fail.setAccessible(true);
            synchronized (test.lock()) {
                publish.invoke(test.runtime, key, entry, 1L, new BaseClusterTopology[0], null);
                assertSame(successor, field(entryType, "buildTask").get(entry));
                assertNull(field(entryType, "buildInputs").get(entry));
                var newInputs = new BaseClusterTopology[0];
                field(entryType, "buildInputs").set(entry, newInputs);
                field(entryType, "buildInputAttempt").setLong(entry, 2);
                fail.invoke(test.runtime, key, entry, 1L, new IllegalStateException("old build"));
                assertSame(successor, field(entryType, "buildTask").get(entry));
                assertSame(newInputs, field(entryType, "buildInputs").get(entry));
            }
        }
    }

    @Test
    void unavailableEndpointCarriesAnAbsenceStampUntilFinalDelivery() throws Exception {
        try (RuntimeCase test = new RuntimeCase()) {
            var request = test.runtime.requestMacroQuery(Level.OVERWORLD,
                    new BlockPos(1, 1, 1), new BlockPos(2, 1, 1), BaseClusterTopology.Channel.GROUND,
                    BaseClusterTopology.TraversalProfile.DEFAULT_GROUND, NavigationScheduler.Priority.ACTIVE);
            var result = request.future().get(5, TimeUnit.SECONDS);
            assertEquals(MacroSearch.Failure.UNAVAILABLE_CHUNK, result.progress().failure());
            var absent = result.stamps().stream().filter(TopologyWorkerRuntime.SectionStamp::absent).findFirst().orElseThrow();
            assertTrue(absent.current());
            synchronized (test.lock()) {
                assertTrue(((Map<?, ?>) field(TopologyWorkerRuntime.class, "clusters").get(test.runtime)).isEmpty());
            }
            test.runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(absent.key(), 1, 0, 0,
                    TopologyWorkerRuntime.FactState.AVAILABLE, BaseClusterTopology.PackedFacts.allAir(), Map.of()));
            assertFalse(absent.current(), "loading before final delivery must invalidate the old U result");
        }
    }

    @Test
    void olderFormationAbsorbsContinuousTailAndDuplicateAckCannotReleaseSuccessor() throws Exception {
        try (RuntimeCase test = new RuntimeCase()) {
            test.pending();
            test.delta(0, 1, Map.of(1, (byte) 0));
            var first = test.decision();
            assertNull(first.facts());
            test.delta(1, 2, Map.of(2, (byte) 0));
            test.awaitVersion(2);
            assertTrue(test.decisions.isEmpty());
            assertEquals(Map.of(2, (byte) 0), test.tail().cells());

            assertTrue(test.runtime.acceptFacts(first));
            assertFalse(test.runtime.acceptFacts(first));
            var formed = BaseClusterTopology.PackedFacts.allAir().withChanges(first.changes());
            assertTrue(test.runtime.factsFormed(first, formed).get(5, TimeUnit.SECONDS));
            var canonical = test.facts();
            assertFacts(BaseClusterTopology.PackedFacts.allAir()
                    .withChanges(Map.of(1, (byte) 0, 2, (byte) 0)), canonical);
            assertTrue(test.runtime.completeFacts(first, true, true).get(5, TimeUnit.SECONDS));
            var second = test.decision();
            assertEquals(2, second.version());
            assertSame(canonical, second.facts());
            int references = test.runtime.metrics().activeReferences();
            assertFalse(test.runtime.completeFacts(first, true, false).get(5, TimeUnit.SECONDS));
            assertEquals(references, test.runtime.metrics().activeReferences());
            assertSame(second, test.entryField("decision"));
            test.finish(second, true);
            assertEquals(0, test.runtime.metrics().activeReferences());
        }
    }

    @Test
    void versionGapDoesNotPublishPredecessorAsCompleteCurrentFacts() throws Exception {
        try (RuntimeCase test = new RuntimeCase()) {
            test.pending();
            test.delta(0, 1, Map.of(1, (byte) 0));
            var first = test.decision();
            test.delta(2, 3, Map.of(3, (byte) 0));
            test.awaitVersion(3);
            assertEquals(2, test.tail().originalVersion());
            assertTrue(test.runtime.acceptFacts(first));
            assertTrue(test.runtime.factsFormed(first, BaseClusterTopology.PackedFacts.allAir()
                    .withChanges(first.changes())).get(5, TimeUnit.SECONDS));
            assertNull(test.facts(), "a complete v1 cannot bridge the absent v2");
            assertTrue(test.runtime.completeFacts(first, true, true).get(5, TimeUnit.SECONDS));
            var later = test.decision();
            assertEquals(2, later.previousVersion());
            assertNull(later.facts());
            assertEquals(Map.of(3, (byte) 0), later.changes());
            assertTrue(test.runtime.completeFacts(later, false, false).get(5, TimeUnit.SECONDS));
            test.runtime.publishChunkUnload(Level.OVERWORLD, KEY.section().chunk(), 1);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!test.runtime.factsSettled() && System.nanoTime() < deadline) Thread.sleep(1);
            assertTrue(test.runtime.factsSettled());
            assertEquals(0, test.runtime.metrics().activeReferences());
        }
    }

    @Test
    void manyTicksRetainOneExecutingInputAndAtMostOneFinalCellTail() throws Exception {
        try (RuntimeCase test = new RuntimeCase()) {
            test.pending();
            test.delta(0, 1, Map.of(0, (byte) 0));
            var first = test.decision();
            for (int version = 2; version <= 100; version++) {
                test.delta(version - 1, version, Map.of(10, (byte) (version & 1)));
            }
            test.awaitVersion(100);
            assertEquals(1, test.tail().originalVersion());
            assertEquals(100, test.tail().newVersion());
            assertEquals(Map.of(10, (byte) 0), test.tail().cells());
            assertTrue(test.decisions.isEmpty());
            test.finish(first, true);
            var last = test.decision();
            assertEquals(100, last.version());
            assertFacts(BaseClusterTopology.PackedFacts.allAir()
                    .withChanges(Map.of(0, (byte) 0, 10, (byte) 0)), last.facts());
            test.finish(last, true);
        }
    }

    @Test
    void writeFailurePreservesMemoryAndEvictionBecomesPersistenceUnavailable() throws Exception {
        try (RuntimeCase test = new RuntimeCase()) {
            var facts = BaseClusterTopology.PackedFacts.allAir().withChanges(Map.of(5, (byte) 0));
            test.full(1, 1, facts, true);
            var decision = test.decision();
            test.finish(decision, false);
            assertSame(facts, test.facts());
            assertEquals(TopologyWorkerRuntime.FactState.AVAILABLE, test.entryField("factState"));
            assertEquals(0, test.runtime.metrics().activeReferences());
            synchronized (test.lock()) {
                field(TopologyWorkerRuntime.class, "baseRetainedBytes")
                        .setLong(test.runtime, 128L * 1024 * 1024 + 1);
                Method evict = TopologyWorkerRuntime.class.getDeclaredMethod("evictBaseCache");
                evict.setAccessible(true);
                evict.invoke(test.runtime);
            }
            assertNull(test.facts());
            assertEquals(TopologyWorkerRuntime.FactState.PERSISTENCE_UNAVAILABLE,
                    test.entryField("factState"));
            test.delta(1, 2, Map.of(6, (byte) 0));
            test.awaitVersion(2);
            assertTrue(test.decisions.isEmpty());
            assertEquals(TopologyWorkerRuntime.FactState.PERSISTENCE_UNAVAILABLE,
                    test.entryField("factState"));
        }
    }

    @Test
    void realNewVersionMayWriteOnceWhenFailedVersionsCompleteBaselineRemains() throws Exception {
        try (RuntimeCase test = new RuntimeCase()) {
            test.full(1, 1, BaseClusterTopology.PackedFacts.allAir(), true);
            test.finish(test.decision(), false);
            test.delta(1, 2, Map.of(20, (byte) 0));
            var next = test.decision();
            assertNotNull(next.facts());
            assertEquals(2, next.version());
            test.finish(next, true);
            assertEquals(-1L, test.entryField("persistenceUnavailableVersion"));
        }
    }

    @Test
    void exhaustedRecoveryTerminatesWaitingQueryAndDropsUnformableTailUntilReload() throws Exception {
        try (RuntimeCase test = new RuntimeCase()) {
            test.pending();
            test.delta(0, 1, Map.of(20, (byte) 0));
            var first = test.decision();
            test.delta(1, 2, Map.of(21, (byte) 0));
            test.awaitVersion(2);
            test.runtime.completeFacts(first, false, false);
            assertNotNull(test.tail());

            BlockPos start = new BlockPos(KEY.section().minBlockX() + 2,
                    KEY.section().minBlockY() + 2, KEY.section().minBlockZ() + 2);
            var waiting = test.runtime.requestMacroQuery(Level.OVERWORLD, start, start.offset(1, 0, 0),
                    BaseClusterTopology.Channel.VOLUME, BaseClusterTopology.TraversalProfile.DEFAULT_GROUND,
                    NavigationScheduler.Priority.ACTIVE);
            assertNotNull(test.demands.poll(5, TimeUnit.SECONDS));
            assertFalse(waiting.future().isDone());
            test.runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(KEY, 1, 2, 2,
                    TopologyWorkerRuntime.FactState.PERSISTENCE_UNAVAILABLE, null, Map.of()));
            var result = waiting.future().get(5, TimeUnit.SECONDS);
            assertNull(result.corridor());
            assertEquals(MacroSearch.Failure.FACTS_PERSISTENCE_UNAVAILABLE, result.progress().failure());
            assertEquals(KEY.section(), result.progress().blockedSection());
            assertNull(test.tail());
            assertTrue(test.runtime.factsSettled());
            assertEquals(0, test.runtime.metrics().activeReferences());

            test.delta(2, 3, Map.of(22, (byte) 0));
            test.awaitVersion(3);
            assertEquals(TopologyWorkerRuntime.FactState.PERSISTENCE_UNAVAILABLE, test.entryField("factState"));
            assertTrue(test.decisions.isEmpty());
            assertNull(test.tail());
            test.full(1, 4, BaseClusterTopology.PackedFacts.allAir(), false);
            test.awaitVersion(4);
            assertNull(test.facts(), "a late same-load full result cannot revive exhausted recovery");

            test.full(2, 0, BaseClusterTopology.PackedFacts.allAir(), false);
            test.awaitLoad(2);
            assertEquals(-1L, test.entryField("persistenceUnavailableVersion"));
            var reloaded = test.runtime.requestMacroQuery(Level.OVERWORLD, start, start.offset(1, 0, 0),
                    BaseClusterTopology.Channel.VOLUME, BaseClusterTopology.TraversalProfile.DEFAULT_GROUND,
                    NavigationScheduler.Priority.ACTIVE);
            assertNotNull(reloaded.future().get(5, TimeUnit.SECONDS).corridor());
        }
    }

    @Test
    void resolvedEndpointFailureCannotSurviveReloadWhileAnotherEndpointIsPending() throws Exception {
        for (boolean reload : new boolean[] { false, true }) try (RuntimeCase test = new RuntimeCase()) {
            var other = new TopologyWorkerRuntime.ClusterKey(Level.OVERWORLD,
                    SectionPos.of(KEY.section().x() + 1, KEY.section().y(), KEY.section().z()));
            test.runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(KEY, 1, 0, 0,
                    TopologyWorkerRuntime.FactState.RECOVERY_FAILED, null, Map.of()));
            test.runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(other, 1, 0, 0,
                    TopologyWorkerRuntime.FactState.PENDING, null, Map.of()));
            BlockPos start = new BlockPos(KEY.section().minBlockX() + 2,
                    KEY.section().minBlockY() + 2, KEY.section().minBlockZ() + 2);
            var request = test.runtime.requestMacroQuery(Level.OVERWORLD, start, start.offset(16, 0, 0),
                    BaseClusterTopology.Channel.VOLUME, BaseClusterTopology.TraversalProfile.DEFAULT_GROUND,
                    NavigationScheduler.Priority.ACTIVE);
            assertNotNull(test.demands.poll(5, TimeUnit.SECONDS), "the second endpoint must still be waiting");
            assertFalse(request.future().isDone());
            if (reload) test.full(2, 0, BaseClusterTopology.PackedFacts.allAir(), false);
            test.runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(other, 1, 0, 0,
                    TopologyWorkerRuntime.FactState.RECOVERY_FAILED, null, Map.of()));
            var result = request.future().get(5, TimeUnit.SECONDS);
            assertEquals(MacroSearch.Failure.FACTS_RECOVERY_FAILED, result.progress().failure());
            assertEquals(reload ? other.section() : KEY.section(), result.progress().blockedSection());
            assertEquals(reload ? 1 : 0, result.staleRetries());
            assertFalse(result.stamps().isEmpty());
            assertTrue(result.stamps().stream().allMatch(TopologyWorkerRuntime.SectionStamp::current));
        }
    }

    @Test
    void lateOldLoadFormationCannotReplaceReloadedFacts() throws Exception {
        try (RuntimeCase test = new RuntimeCase()) {
            test.pending();
            test.delta(0, 1, Map.of(8, (byte) 0));
            var old = test.decision();
            assertTrue(test.runtime.acceptFacts(old));
            var replacement = BaseClusterTopology.PackedFacts.allAir();
            test.full(2, 0, replacement, false);
            test.awaitLoad(2);
            test.runtime.factsFormed(old, replacement.withChanges(old.changes()));
            test.runtime.completeFacts(old, true, false);
            assertSame(replacement, test.facts());
            assertEquals(-1L, test.entryField("persistenceUnavailableVersion"));
        }
    }

    @Test
    void closingRetainsFactsNeededByUndeliveredDecisionAndMergedTail() throws Exception {
        try (RuntimeCase test = new RuntimeCase()) {
            test.pending();
            test.delta(0, 1, Map.of(30, (byte) 0));
            var first = test.decision();
            test.delta(1, 2, Map.of(31, (byte) 0));
            test.runtime.beginStopping();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!(Boolean) field(TopologyWorkerRuntime.class, "closed").get(test.runtime)
                    && System.nanoTime() < deadline) Thread.sleep(1);
            assertTrue((Boolean) field(TopologyWorkerRuntime.class, "closed").get(test.runtime));
            assertFalse(test.runtime.factsSettled());
            test.finish(first, true);
            var last = test.decision();
            assertEquals(2, last.version());
            assertFacts(BaseClusterTopology.PackedFacts.allAir().withChanges(
                    Map.of(30, (byte) 0, 31, (byte) 0)), last.facts());
            test.finish(last, true);
            while (!test.runtime.factsSettled() && System.nanoTime() < deadline) Thread.sleep(1);
            assertTrue(test.runtime.factsSettled());
        }
    }

    @Test
    void sharedAirBytesAreCountedOnceAcrossIdleAndExecutingReferences() throws Exception {
        try (RuntimeCase test = new RuntimeCase()) {
            var air = BaseClusterTopology.PackedFacts.allAir();
            test.full(1, 1, air, true);
            var first = test.decision();
            var otherKey = new TopologyWorkerRuntime.ClusterKey(Level.OVERWORLD, SectionPos.of(3, 4, 5));
            test.runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(otherKey, 1, 1, 1,
                    TopologyWorkerRuntime.FactState.AVAILABLE, air, Map.of(), true));
            var second = test.decision();
            assertEquals(air.retainedBytes(), test.runtime.metrics().facts().activeFactsBytes());
            test.finish(first, true);
            assertEquals(air.retainedBytes(), test.runtime.metrics().facts().activeFactsBytes());
            assertEquals(64, test.runtime.metrics().baseCache().retainedBytes());
            test.finish(second, true);
            assertEquals(0, test.runtime.metrics().facts().activeFactsBytes());
            assertEquals(128 + air.retainedBytes(), test.runtime.metrics().baseCache().retainedBytes());
        }
    }

    private static void assertFacts(BaseClusterTopology.PackedFacts expected,
                                    BaseClusterTopology.PackedFacts actual) {
        assertNotNull(actual);
        assertArrayEquals(expected.bytes(), actual.bytes());
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static final class RuntimeCase implements AutoCloseable {
        final BlockingQueue<TopologyWorkerRuntime.FactDecision> decisions = new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> demands = new LinkedBlockingQueue<>();
        final TopologyWorkerRuntime runtime = new TopologyWorkerRuntime(
                (key, load, count) -> { if (count > 0) demands.add(count); }, decisions::add);

        void pending() {
            runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(KEY, 1, 0, 0,
                    TopologyWorkerRuntime.FactState.PENDING, null, Map.of()));
        }

        void full(long load, long version, BaseClusterTopology.PackedFacts facts, boolean persist) {
            runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(KEY, load, version, version,
                    TopologyWorkerRuntime.FactState.AVAILABLE, facts, Map.of(), persist));
        }

        void delta(long from, long to, Map<Integer, Byte> cells) {
            runtime.publishSection(new TopologyWorkerRuntime.SectionEvent(KEY, 1, from, to,
                    TopologyWorkerRuntime.FactState.AVAILABLE, null, cells));
        }

        TopologyWorkerRuntime.FactDecision decision() throws Exception {
            var result = decisions.poll(5, TimeUnit.SECONDS);
            assertNotNull(result, "facts decision did not arrive");
            return result;
        }

        void finish(TopologyWorkerRuntime.FactDecision decision, boolean written) throws Exception {
            runtime.acceptFacts(decision);
            var formed = decision.facts() == null ? BaseClusterTopology.PackedFacts.allAir()
                    .withChanges(decision.changes()) : decision.facts();
            assertTrue(runtime.factsFormed(decision, formed).get(5, TimeUnit.SECONDS));
            runtime.completeFacts(decision, true, written).get(5, TimeUnit.SECONDS);
        }

        Object lock() throws Exception { return field(TopologyWorkerRuntime.class, "runtimeLock").get(runtime); }

        Object entryField(String name) throws Exception {
            synchronized (lock()) {
                Map<?, ?> clusters = (Map<?, ?>) field(TopologyWorkerRuntime.class, "clusters").get(runtime);
                Object entry = clusters.get(KEY);
                return entry == null ? null : field(entry.getClass(), name).get(entry);
            }
        }

        BaseClusterTopology.PackedFacts facts() throws Exception {
            return (BaseClusterTopology.PackedFacts) entryField("facts");
        }

        TopologyStore.SectionDelta tail() throws Exception {
            return (TopologyStore.SectionDelta) entryField("tail");
        }

        void awaitVersion(long version) throws Exception { awaitField("revision", version); }
        void awaitLoad(long load) throws Exception { awaitField("loadIdentity", load); }

        void awaitField(String name, long value) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Long.valueOf(value).equals(entryField(name)) && System.nanoTime() < deadline) Thread.sleep(1);
            assertEquals(value, entryField(name));
        }

        @Override public void close() {
            runtime.beginStopping();
            assertTrue(runtime.awaitStopped(5, TimeUnit.SECONDS));
            runtime.finishFactClosing();
        }
    }
}
