package com.scarasol.acceleratednavigation.topology;

import java.util.*;
import java.util.concurrent.*;

final class OptimizationControls {
    static final Map<String, Object> evidence = new LinkedHashMap<>();
    static long assertions;
    static void verify(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    static LocalKernel.Row randomRow(Random random) {
        var edges = new TreeMap<Integer, Long>();
        for (int i = random.nextInt(9); i > 0; i--)
            edges.put(random.nextInt(4096), 1L << random.nextInt(30));
        return new LocalKernel.Row(edges.keySet().stream().mapToInt(Integer::intValue).toArray(),
                edges.values().stream().mapToLong(Long::longValue).toArray());
    }
    static List<Object> dump(RowStorage rows, LocalKernel.Row[] expected) {
        var result = new ArrayList<Object>();
        for (int cell = 0; cell < 4096; cell++) {
            var row = expected[cell];
            if (row == null) row = LocalKernel.Row.EMPTY;
            int[] targets = new int[rows.count(cell)]; long[] masks = new long[targets.length];
            for (int edge = 0; edge < targets.length; edge++) {
                targets[edge] = rows.target(cell, edge); masks[edge] = rows.mask(cell, edge);
            }
            result.add(Map.of("cell", cell, "actualTargets", targets, "actualMasks", masks,
                    "expectedTargets", row.targets(), "expectedMasks", row.masks()));
        }
        return result;
    }
    static void rows() {
        var random = new Random(20261014);
        var initial = new LocalKernel.Row[4096];
        for (int cell = 0; cell < 4096; cell++) initial[cell] = randomRow(random);
        var basis = RowStorage.create(initial, true);
        for (int cell = 0; cell < 4096; cell++) verify(basis.same(cell, initial[cell]), "packed basis exact");
        var current = RowStorage.empty(true, basis);
        var oracle = new LocalKernel.Row[4096];
        long sharedChunks = 0;
        for (int round = 0; round < 20; round++) {
            var delta = new HashMap<Integer, LocalKernel.Row>();
            for (int i = 0; i < 40; i++) {
                int cell = random.nextInt(4096);
                delta.put(cell, i % 3 == 0 ? initial[cell] : i % 3 == 1 ? LocalKernel.Row.EMPTY : randomRow(random));
            }
            var old = current; var oldOracle = oracle.clone();
            delta.forEach((cell, row) -> oracle[cell] = row);
            current = current.merge(delta);
            for (int cell = 0; cell < 4096; cell++) {
                verify(current.same(cell, oracle[cell] == null ? LocalKernel.Row.EMPTY : oracle[cell]), "merged CSR exact");
                verify(old.same(cell, oldOracle[cell] == null ? LocalKernel.Row.EMPTY : oldOracle[cell]), "old CSR immutable");
            }
            for (int chunk = 0; chunk < 64; chunk++) {
                final int selected = chunk;
                if (delta.keySet().stream().noneMatch(cell -> (cell >>> 6) == selected)) {
                    verify(current.blocks[chunk] == old.blocks[chunk], "untouched block identity");
                    if (old.blocks[chunk] != null) sharedChunks++;
                }
            }
        }
        var all = new HashMap<Integer, LocalKernel.Row>();
        for (int cell = 0; cell < 4096; cell++) all.put(cell, LocalKernel.Row.EMPTY);
        var empty = current.merge(all);
        for (int cell = 0; cell < 4096; cell++) verify(empty.count(cell) == 0, "full sensitive empty replacement");
        verify(current.basisReferences() > 0 && sharedChunks > 0, "basis and unchanged chunks actually shared");
        evidence.put("rowAssertions", assertions);
        evidence.put("sharedUnchangedChunks", sharedChunks);
        evidence.put("basisReferences", current.basisReferences());
        evidence.put("finalRows", dump(current, oracle));
        SharedRuntimeProbe.check(true, "CSR random exact rows and immutable chunks");
    }
    static void captures() throws Exception {
        try (var host = new SharedRuntimeProbe.Host(Fixtures.fromFlags("flat", Fixtures.flat()).input().center())) {
            SharedRuntimeProbe.get(host.request(SharedRuntimeProbe.OPEN));
            long captures = host.runtime.shared.metrics().get("capturesStarted");
            host.runtime.shared.maxCaptures = 0; host.runtime.shared.maxSlots = 0;
            if (host.runtime.shared.fastCapture) {
                SharedRuntimeProbe.check(SharedRuntimeProbe.get(host.request(SharedRuntimeProbe.OPEN)).candidate(),
                        "valid slot survives zero new-slot and capture quotas");
                SharedRuntimeProbe.check(host.runtime.shared.metrics().get("capturesStarted") == captures,
                        "warm fast request does not recapture");
            } else SharedRuntimeProbe.rejected(host.request(SharedRuntimeProbe.OPEN), "capture-each obeys zero capture quota");
            host.runtime.shared.maxCaptures = 1024; host.runtime.shared.maxSlots = 64;
            SharedRuntimeProbe.cases.add(Map.of("name", "warm-quota-control", "metrics", host.runtime.shared.metrics()));
        }
        try (var host = new SharedRuntimeProbe.Host(Fixtures.fromFlags("flat", Fixtures.flat()).input().center())) {
            var old = SharedRuntimeProbe.get(host.request(SharedRuntimeProbe.OPEN));
            var gate = new SharedRuntimeProbe.Gate("before-delivery", 1);
            host.runtime.shared.hook = gate;
            var pending = host.request(SharedRuntimeProbe.SMALL);
            gate.await();
            host.publish(SharedRuntimeProbe.SECTION, 1, Fixtures.wall("sealed", 0, false).input().center());
            try { SharedRuntimeProbe.settle(() -> !old.current()); }
            finally { gate.release.countDown(); }
            SharedRuntimeProbe.rejected(pending, "input changes after settlement before delivery");
            host.runtime.shared.hook = (point, key) -> {};
            SharedRuntimeProbe.check(!SharedRuntimeProbe.get(host.request(SharedRuntimeProbe.SMALL)).candidate(),
                    "fresh query after delivery invalidation is genuinely blocked");
            SharedRuntimeProbe.check(host.runtime.shared.metrics().get("capturesStarted") >= 2,
                    "invalid slot uses fresh capture path");
            SharedRuntimeProbe.cases.add(Map.of("name", "input-after-settlement", "metrics", host.runtime.shared.metrics()));
        }
    }
    static void run() throws Exception {
        rows(); captures();
        evidence.put("packedRows", !Boolean.getBoolean("an.objectRows"));
        evidence.put("fastCapture", !Boolean.getBoolean("an.captureEach"));
    }
}
