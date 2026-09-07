package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import static org.junit.jupiter.api.Assertions.*;

class TopologyMetricsControlTest {
    @Test void everySuppressionTargetHasIdentifiableStatisticsWrites() throws Exception {
        for (String name : java.util.List.of("TopologyService", "TopologyService$MacroRequest", "TopologyWorkerRuntime",
                "TopologyWorkerRuntime$MacroRequest", "TopologyWorkerRuntime$MacroQuery", "TopologyStore", "TopologyTaskExecutor")) {
            try (var input = getClass().getResourceAsStream("/com/scarasol/acceleratednavigation/topology/" + name + ".class")) {
                assertNotNull(input); ClassNode node = new ClassNode(); new ClassReader(input).accept(node, 0);
                assertFalse(TopologyMetricsControl.suppress(node).isEmpty(), name);
            }
        }
    }

    @Test void transformedExecutorKeepsBusinessCountsAndActuallySkipsStatisticsStores() throws Exception {
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("com.scarasol.acceleratednavigation.topology.TopologyTaskExecutor")) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> found = findLoadedClass(name);
                    if (found == null) {
                        try (var input = getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (input == null) throw new ClassNotFoundException(name);
                            ClassNode node = new ClassNode(); new ClassReader(input).accept(node, 0);
                            if (name.endsWith("TopologyTaskExecutor")) {
                                var writes = TopologyMetricsControl.suppress(node);
                                assertTrue(writes.stream().anyMatch(write -> write.endsWith(".completedByKind")));
                                assertFalse(writes.stream().anyMatch(write -> write.endsWith(".runningByKind") || write.endsWith(".queuedByKind")));
                            }
                            ClassWriter writer = new ClassWriter(0); node.accept(writer);
                            byte[] bytes = writer.toByteArray(); found = defineClass(name, bytes, 0, bytes.length);
                        } catch (IOException | org.objectweb.asm.tree.analysis.AnalyzerException failure) { throw new ClassNotFoundException(name, failure); }
                    }
                    if (resolve) resolveClass(found);
                    return found;
                }
            }
        };
        Class<?> type = loader.loadClass(TopologyTaskExecutor.class.getName());
        var constructor = type.getDeclaredConstructor(BooleanSupplier.class); constructor.setAccessible(true);
        Object executor = constructor.newInstance((BooleanSupplier) () -> true);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            var submit = type.getDeclaredMethod("submit", NavigationScheduler.Priority.class, Runnable.class); submit.setAccessible(true);
            submit.invoke(executor, NavigationScheduler.Priority.ACTIVE, (Runnable) () -> {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Object monitor = TopologyTestBridge.readField(executor, "monitor");
            synchronized (monitor) {
                long[] running = (long[]) TopologyTestBridge.readField(executor, "runningByKind");
                assertEquals(1, java.util.Arrays.stream(running).sum());
                assertEquals(0, java.util.Arrays.stream((long[]) TopologyTestBridge.readField(executor, "highestRunningByKind")).sum());
            }
        } finally {
            release.countDown();
            TopologyTestBridge.invoke(executor, "shutdown");
            assertEquals(true, TopologyTestBridge.invoke(executor, "awaitTermination", 5L, TimeUnit.SECONDS));
        }
        assertEquals(0, java.util.Arrays.stream((long[]) TopologyTestBridge.readField(executor, "runningByKind")).sum());
        assertEquals(0, java.util.Arrays.stream((long[]) TopologyTestBridge.readField(executor, "completedByKind")).sum());
    }
}
