package com.scarasol.acceleratednavigation.topology;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacroTopologyPerformanceTest {

    private static volatile int resultSink;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void measuresSearchDistancesAndClusterBuildResources() throws IOException {
        List<SearchMeasurement> searches = List.of(
                measureSearch("short", 8, 200, 0),
                measureSearch("medium", 96, 100, 0),
                measureSearch("long", 512, 30, 0),
                measureSearch("short_with_2048_unrelated_clusters", 8, 100, 2_048)
        );
        List<BuildMeasurement> builds = List.of(
                measureBuild("empty", emptySnapshot(), 200),
                measureBuild("open", openSnapshot(), 200),
                measureBuild("fragmented_boundary", fragmentedBoundarySnapshot(), 100),
                measureBuild("fluid_boundary", fluidBoundarySnapshot(), 200)
        );

        SearchMeasurement shortSearch = searches.get(0);
        assertTrue(shortSearch.p95Nanos < 5_000_000L,
                "ready-graph short macro search p95 exceeded 5 ms");
        assertTrue(searches.get(1).p95Nanos < 20_000_000L,
                "ready-graph medium macro search p95 exceeded 20 ms");
        assertTrue(searches.get(2).p95Nanos < 50_000_000L,
                "ready-graph long macro search p95 exceeded one tick");
        assertTrue(searches.get(3).p95Nanos < 5_000_000L,
                "short macro search scaled with unrelated cached clusters");
        writeReport(searches, builds);
    }

    private static SearchMeasurement measureSearch(String name,
                                                   int distance,
                                                   int runs,
                                                   int unrelatedClusters) {
        TopologyService service = new TopologyService(Runnable::run, Runnable::run, () -> true);
        int startX = 1;
        int goalX = startX + distance;
        int lastSection = goalX >> 4;
        for (int sectionX = 0; sectionX <= lastSection; sectionX++) {
            service.submitSnapshot(
                    key(sectionX),
                    corridorSnapshot()
            ).join();
        }
        for (int index = 0; index < unrelatedClusters; index++) {
            service.submitSnapshot(key(10_000 + index), corridorSnapshot()).join();
        }

        for (int warmup = 0; warmup < 20; warmup++) {
            runSearch(service, distance);
        }
        long[] samples = new long[runs];
        long expanded = 0L;
        long generatedConnections = 0L;
        int corridorConnections = 0;
        for (int run = 0; run < runs; run++) {
            long started = System.nanoTime();
            MacroSearch search = runSearch(service, distance);
            samples[run] = System.nanoTime() - started;
            expanded += search.metrics().expandedNodes();
            generatedConnections += search.metrics().generatedConnections();
            corridorConnections = search.result().connections().size();
        }
        Arrays.sort(samples);
        resultSink ^= corridorConnections;
        return new SearchMeasurement(
                name,
                distance,
                runs,
                percentile(samples, 0.50D),
                percentile(samples, 0.95D),
                samples[samples.length - 1],
                (double) expanded / runs,
                (double) generatedConnections / runs,
                corridorConnections,
                service.metrics().retainedBytes()
        );
    }

    private static MacroSearch runSearch(TopologyService service, int distance) {
        MacroSearch search = new MacroSearch(
            service.graph(
                        Level.OVERWORLD,
                        new BlockPos(1, 4, 4),
                        new BlockPos(1 + distance, 4, 4),
                        BaseClusterTopology.Channel.GROUND
                ),
                MacroSearch.DEFAULT_WEIGHT
        );
        for (int step = 0; step < 10_000 && search.status() == MacroSearch.Status.RUNNING; step++) {
            search.step(4_096, Long.MAX_VALUE);
        }
        assertEquals(MacroSearch.Status.SUCCEEDED, search.status());
        assertNotNull(search.result());
        return search;
    }

    private static BuildMeasurement measureBuild(String name,
                                                  BaseClusterTopology.Snapshot snapshot,
                                                  int runs) {
        for (int warmup = 0; warmup < 20; warmup++) {
            BaseClusterTopology.build(SectionPos.of(0, 0, 0), warmup, snapshot);
        }
        long[] samples = new long[runs];
        BaseClusterTopology last = null;
        for (int run = 0; run < runs; run++) {
            long started = System.nanoTime();
            last = BaseClusterTopology.build(SectionPos.of(0, 0, 0), run, snapshot);
            samples[run] = System.nanoTime() - started;
        }
        Arrays.sort(samples);
        resultSink ^= last.retainedBytes();
        return new BuildMeasurement(
                name,
                runs,
                percentile(samples, 0.50D),
                percentile(samples, 0.95D),
                samples[samples.length - 1],
                last.components().size(),
                last.retainedBytes()
        );
    }

    private static BaseClusterTopology.Snapshot emptySnapshot() {
        return new BaseClusterTopology.Snapshot(new byte[BaseClusterTopology.CELL_COUNT]);
    }

    private static BaseClusterTopology.Snapshot openSnapshot() {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        Arrays.fill(cells, (byte) (BaseClusterTopology.VOLUME_OPEN
                | BaseClusterTopology.GROUND_OPEN));
        return new BaseClusterTopology.Snapshot(cells);
    }

    private static BaseClusterTopology.Snapshot corridorSnapshot() {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        int flags = BaseClusterTopology.VOLUME_OPEN | BaseClusterTopology.GROUND_OPEN;
        for (int x = 0; x < BaseClusterTopology.SIDE; x++) {
            cells[BaseClusterTopology.cellIndex(x, 4, 4)] = (byte) flags;
            cells[BaseClusterTopology.cellIndex(x, 5, 4)] = BaseClusterTopology.VOLUME_OPEN;
        }
        return new BaseClusterTopology.Snapshot(cells);
    }

    private static BaseClusterTopology.Snapshot fragmentedBoundarySnapshot() {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        int flags = BaseClusterTopology.VOLUME_OPEN
                | BaseClusterTopology.GROUND_OPEN
                | BaseClusterTopology.EXACT_REQUIRED;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                if (((y + z) & 1) == 0) {
                    cells[BaseClusterTopology.cellIndex(0, y, z)] = (byte) flags;
                    cells[BaseClusterTopology.cellIndex(15, y, z)] = (byte) flags;
                }
            }
        }
        return new BaseClusterTopology.Snapshot(cells);
    }

    private static BaseClusterTopology.Snapshot fluidBoundarySnapshot() {
        byte[] cells = new byte[BaseClusterTopology.CELL_COUNT];
        int flags = BaseClusterTopology.VOLUME_OPEN
                | BaseClusterTopology.GROUND_OPEN
                | BaseClusterTopology.FLUID
                | BaseClusterTopology.EXACT_REQUIRED;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                cells[BaseClusterTopology.cellIndex(15, y, z)] = (byte) flags;
            }
        }
        return new BaseClusterTopology.Snapshot(cells);
    }

    private static TopologyService.ClusterKey key(int sectionX) {
        return new TopologyService.ClusterKey(Level.OVERWORLD, SectionPos.of(sectionX, 0, 0));
    }

    private static long percentile(long[] sorted, double percentile) {
        int index = Math.max(0, (int) Math.ceil(sorted.length * percentile) - 1);
        return sorted[index];
    }

    private static void writeReport(List<SearchMeasurement> searches,
                                    List<BuildMeasurement> builds) throws IOException {
        String configured = System.getProperty("accelerated.navigation.performanceReport");
        Path report = configured == null
                ? Path.of("build", "reports", "macro-topology-synthetic-microbenchmark.json")
                : Path.of(configured);
        Files.createDirectories(report.getParent());
        List<String> searchJson = new ArrayList<>();
        for (SearchMeasurement value : searches) {
            searchJson.add(String.format(Locale.ROOT,
                    "{\"name\":\"%s\",\"distanceBlocks\":%d,\"runs\":%d,"
                            + "\"p50Micros\":%.3f,\"p95Micros\":%.3f,\"maxMicros\":%.3f,"
                            + "\"averageExpandedNodes\":%.3f,\"averageGeneratedConnections\":%.3f,"
                            + "\"corridorConnections\":%d,\"readyTopologyBytes\":%d}",
                    value.name, value.distanceBlocks, value.runs,
                    value.p50Nanos / 1_000.0D, value.p95Nanos / 1_000.0D,
                    value.maxNanos / 1_000.0D, value.averageExpandedNodes,
                    value.averageGeneratedConnections,
                    value.corridorConnections,
                    value.readyTopologyBytes));
        }
        List<String> buildJson = new ArrayList<>();
        for (BuildMeasurement value : builds) {
            buildJson.add(String.format(Locale.ROOT,
                    "{\"name\":\"%s\",\"runs\":%d,\"p50Micros\":%.3f,"
                            + "\"p95Micros\":%.3f,\"maxMicros\":%.3f,"
                            + "\"components\":%d,\"retainedBytes\":%d}",
                    value.name, value.runs, value.p50Nanos / 1_000.0D,
                    value.p95Nanos / 1_000.0D, value.maxNanos / 1_000.0D,
                    value.components, value.retainedBytes));
        }
        String json = "{\n  \"benchmarkKind\": \"synthetic_microbenchmark\","
                + "\n  \"terrain\": \"none\","
                + "\n  \"warning\": \"Measures ready-graph algorithm scaling only; not world traversability\","
                + "\n  \"searches\": [\n    "
                + String.join(",\n    ", searchJson)
                + "\n  ],\n  \"clusterBuilds\": [\n    "
                + String.join(",\n    ", buildJson)
                + "\n  ]\n}\n";
        Files.writeString(report, json, StandardCharsets.UTF_8);
    }

    private record SearchMeasurement(String name,
                                     int distanceBlocks,
                                     int runs,
                                     long p50Nanos,
                                     long p95Nanos,
                                     long maxNanos,
                                     double averageExpandedNodes,
                                     double averageGeneratedConnections,
                                     int corridorConnections,
                                     long readyTopologyBytes) {
    }

    private record BuildMeasurement(String name,
                                    int runs,
                                    long p50Nanos,
                                    long p95Nanos,
                                    long maxNanos,
                                    int components,
                                    int retainedBytes) {
    }
}
