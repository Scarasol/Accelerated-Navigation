package com.scarasol.acceleratednavigation.topology;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.minecraft.SharedConstants;
import net.minecraft.core.SectionPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TopologyHandoffFaultProbeTest {
    @TempDir Path temporary;
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void physicalRecordFaultsReachProductionDecodeAndBaselineValidation() throws Exception {
        SectionPos section = SectionPos.of(0, 0, 0), sibling = SectionPos.of(0, 1, 0);
        var key = new TopologyWorkerRuntime.ClusterKey(Level.OVERWORLD, section);
        for (String kind : List.of("missing", "truncated", "version")) {
            try (TopologyStore store = new TopologyStore(temporary.resolve(kind));
                 var probe = TopologyHandoffFaultProbe.install(new Owner(store), Level.OVERWORLD, section, 7)) {
                var facts = BaseClusterTopology.PackedFacts.allAir().withChanges(Map.of(2, (byte) 0));
                TopologyStoreTest.writeFull(store, section, 5, facts).get(5, TimeUnit.SECONDS);
                TopologyStoreTest.writeFull(store, sibling, 9, facts).get(5, TimeUnit.SECONDS);
                probe.corrupt(kind).get(5, TimeUnit.SECONDS);
                var receipt = store.accept(new TopologyWorkerRuntime.FactDecision(key, 7, 1, 5, 6, null, Map.of(3, (byte) 0)));
                var result = receipt.completed.get(5, TimeUnit.SECONDS);
                assertNull(receipt.formed.join().facts());
                assertEquals(switch (kind) {
                    case "missing" -> TopologyStore.UpdateStatus.BASE_MISSING;
                    case "truncated" -> TopologyStore.UpdateStatus.CORRUPT;
                    default -> TopologyStore.UpdateStatus.VERSION_MISMATCH;
                }, result.status());
                if (!kind.equals("truncated")) {
                    var other = store.read(Level.OVERWORLD, sibling).get(5, TimeUnit.SECONDS);
                    assertEquals(9, other.record().version());
                    assertArrayEquals(facts.bytes(), other.record().facts().bytes());
                }
            }
        }
    }

    @Test void ioFaultSelectionIsScopedToItsStoreChunkAndExplicitLifetime() throws Exception {
        SectionPos section = SectionPos.of(0, 0, 0);
        try (TopologyStore store = new TopologyStore(temporary)) {
            var probe = TopologyHandoffFaultProbe.install(new Owner(store), Level.OVERWORLD, section, 7);
            Object key = chunkKey(section), other = chunkKey(SectionPos.of(1, 0, 0));
            try {
                probe.fault("io");
                TopologyHandoffFaultProbe.read(new Object(), key);
                TopologyHandoffFaultProbe.read(store, other);
                assertThrows(IOException.class, () -> TopologyHandoffFaultProbe.read(store, key));
                assertEquals(1, probe.snapshot().get("rejectedReads"));
                probe.fault("write");
                TopologyHandoffFaultProbe.read(store, key);
                assertThrows(IOException.class, () -> TopologyHandoffFaultProbe.write(store, key));
                assertEquals(1, probe.snapshot().get("rejectedWrites"));
            } finally { probe.close(); }
            assertDoesNotThrow(() -> TopologyHandoffFaultProbe.read(store, key));
            assertDoesNotThrow(() -> TopologyHandoffFaultProbe.write(store, key));
        }
    }

    private static Object chunkKey(SectionPos section) throws Exception {
        Class<?> type = Class.forName(TopologyStore.class.getName() + "$ChunkKey");
        var constructor = type.getDeclaredConstructor(net.minecraft.resources.ResourceKey.class, net.minecraft.world.level.ChunkPos.class);
        constructor.setAccessible(true); return constructor.newInstance(Level.OVERWORLD, section.chunk());
    }
    private record Owner(TopologyStore store) { }
}
