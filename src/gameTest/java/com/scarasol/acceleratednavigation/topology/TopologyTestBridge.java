package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.gametest.TopologyStoreFlushAccess;
import com.scarasol.acceleratednavigation.gametest.TopologyStoreObservationAccess;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Test-only bridge for package-private production owners. */
public final class TopologyTestBridge {

    private TopologyTestBridge() {
    }

    public static boolean hasStore(Object service) {
        return readField(service, "store") != null;
    }

    public static TopologyRuntimeFormalControlAccess formalRuntimeAccess(Object service) {
        return (TopologyRuntimeFormalControlAccess) readField(service, "runtime");
    }

    public static TopologyStoreFlushAccess flushAccess(Object service) {
        Object store = readField(service, "store");
        if (store == null) {
            throw new IllegalStateException("macro topology persistence is unavailable");
        }
        return (TopologyStoreFlushAccess) store;
    }

    public static TopologyStoreObservationAccess.StoreObservation storeObservation(
            Object service) {
        Object store = readField(service, "store");
        if (store == null) {
            return new TopologyStoreObservationAccess.StoreObservation(0, 0, 0);
        }
        if (store instanceof TopologyStoreFlushAccess flush) {
            TopologyStoreFlushAccess.FlushObservation observation =
                    flush.acceleratedNavigation$flushObservation();
            return new TopologyStoreObservationAccess.StoreObservation(
                    observation.requestedFlushes(),
                    observation.queuedFlushes(),
                    observation.activeFlushes());
        }
        return ((TopologyStoreObservationAccess) store).acceleratedNavigation$storeObservation();
    }

    public static Map<String, Long> workerMetrics(Object service) {
        return workerMetrics((TopologyWorkerRuntime) readField(service, "runtime"));
    }

    public static Map<String, Long> persistenceMetrics(Object service) {
        Object store = readField(service, "store");
        if (store == null) {
            return Map.of();
        }
        return persistenceMetrics((TopologyStore) store);
    }

    /** Test-only pressure setup under the worker runtime lock. */
    @SuppressWarnings("unchecked")
    public static void invalidateSections(Object service,
                                          ResourceKey<Level> dimension,
                                          List<SectionPos> sections) {
        Object runtime = readField(service, "runtime");
        Object runtimeLock = readField(runtime, "runtimeLock");
        try {
            Class<?> clusterKeyClass = Class.forName(
                    "com.scarasol.acceleratednavigation.topology.TopologyWorkerRuntime$ClusterKey");
            Class<?> factStateClass = Class.forName(
                    "com.scarasol.acceleratednavigation.topology.TopologyWorkerRuntime$FactState");
            Constructor<?> clusterKey = clusterKeyClass.getDeclaredConstructor(
                    ResourceKey.class, SectionPos.class);
            Method invalidate = runtime.getClass().getDeclaredMethod(
                    "markLatestDerivedStale", clusterKeyClass, factStateClass);
            clusterKey.setAccessible(true);
            invalidate.setAccessible(true);
            Object available = Enum.valueOf(
                    (Class<? extends Enum>) factStateClass.asSubclass(Enum.class), "AVAILABLE");
            synchronized (runtimeLock) {
                for (SectionPos section : sections) {
                    invalidate.invoke(runtime, clusterKey.newInstance(dimension, section), available);
                }
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("benchmark could not invalidate pressure sections", failure);
        }
    }

    private static Map<String, Long> workerMetrics(TopologyWorkerRuntime runtime) {
        TopologyService.WorkerMetrics worker = runtime.metrics();
        Map<String, Long> result = new LinkedHashMap<>();
        put(result, "worker.logicalRequests", worker.logicalRequests());
        put(result, "worker.highestLogicalRequests", worker.highestLogicalRequests());
        put(result, "worker.endpointResolutions", worker.endpointResolutions());
        put(result, "worker.highestEndpointResolutions", worker.highestEndpointResolutions());
        put(result, "worker.physicalSearches", worker.physicalSearches());
        put(result, "worker.highestPhysicalSearches", worker.highestPhysicalSearches());
        put(result, "worker.buildDemands", worker.buildDemands());
        put(result, "worker.highestBuildDemands", worker.highestBuildDemands());
        put(result, "worker.dependencyConsumers", worker.dependencyConsumers());
        put(result, "worker.highestDependencyConsumers", worker.highestDependencyConsumers());
        put(result, "worker.liveSearchDependencies", worker.liveSearchDependencies());
        put(result, "worker.highestLiveSearchDependencies", worker.highestLiveSearchDependencies());
        put(result, "worker.prewarmCandidates", worker.prewarmCandidates());
        put(result, "worker.highestPrewarmCandidates", worker.highestPrewarmCandidates());
        put(result, "worker.admittedPrewarms", worker.admittedPrewarms());
        put(result, "worker.highestAdmittedPrewarms", worker.highestAdmittedPrewarms());
        put(result, "worker.activeReferences", worker.activeReferences());
        put(result, "worker.highestActiveReferences", worker.highestActiveReferences());
        put(result, "worker.facts.transfers", worker.facts().transfers());
        put(result, "worker.facts.mergedSections", worker.facts().mergedSections());
        put(result, "worker.facts.mergedCells", worker.facts().mergedCells());
        put(result, "worker.facts.executingDeltaCells", worker.facts().executingDeltaCells());
        put(result, "worker.facts.activeFactsBytes", worker.facts().activeFactsBytes());
        put(result, "worker.facts.sectionIndexBytes", worker.facts().sectionIndexBytes());
        put(result, "worker.facts.mergedEstimatedBytes", worker.facts().mergedEstimatedBytes());
        put(result, "worker.facts.executingInputEstimatedBytes", worker.facts().executingInputEstimatedBytes());
        putCache(result, "worker.baseCache", worker.baseCache());
        putCache(result, "worker.parentCache", worker.parentCache());
        putCache(result, "worker.corridorCache", worker.corridorCache());
        put(result, "worker.completedCacheHits", worker.completedCacheHits());
        put(result, "worker.physicalSearchesStarted", worker.physicalSearchesStarted());
        put(result, "worker.physicalSearchesSucceeded", worker.physicalSearchesSucceeded());
        put(result, "worker.physicalSearchesFailed", worker.physicalSearchesFailed());
        put(result, "worker.workerStaleResults", worker.workerStaleResults());
        put(result, "worker.automaticStaleRetries", worker.automaticStaleRetries());
        put(result, "worker.staleRetryExhaustions", worker.staleRetryExhaustions());
        putEvents(result, worker.events());
        putTasks(result, "worker.tasks", worker.tasks());
        return result;
    }

    private static Map<String, Long> persistenceMetrics(TopologyStore store) {
        TopologyService.PersistenceMetrics persistence = store.metrics();
        Map<String, Long> result = new LinkedHashMap<>();
        put(result, "persistence.pendingChunks", persistence.pendingChunks());
        put(result, "persistence.highestPendingChunks", persistence.highestPendingChunks());
        put(result, "persistence.readsInFlight", persistence.readsInFlight());
        put(result, "persistence.queuedReadTasks", persistence.queuedReadTasks());
        put(result, "persistence.queuedWriteOrFlushTasks", persistence.queuedWriteOrFlushTasks());
        put(result, "persistence.highestQueuedTasks", persistence.highestQueuedTasks());
        put(result, "persistence.decodedChunks", persistence.decodedChunks());
        put(result, "persistence.readRequests", persistence.readRequests());
        put(result, "persistence.recordsFound", persistence.recordsFound());
        put(result, "persistence.recordsMissing", persistence.recordsMissing());
        put(result, "persistence.readFailures", persistence.readFailures());
        put(result, "persistence.writeRequests", persistence.writeRequests());
        put(result, "persistence.recordsWritten", persistence.recordsWritten());
        put(result, "persistence.recordsCoalesced", persistence.recordsCoalesced());
        put(result, "persistence.writeFailures", persistence.writeFailures());
        put(result, "persistence.saveRequests", persistence.saveRequests());
        put(result, "persistence.flushes", persistence.flushes());
        put(result, "persistence.flushFailures", persistence.flushFailures());
        put(result, "persistence.accepting", persistence.accepting() ? 1L : 0L);
        put(result, "persistence.closing", persistence.closing() ? 1L : 0L);
        put(result, "persistence.acceptedWrites", persistence.acceptedWrites());
        return result;
    }

    private static void putCache(Map<String, Long> target,
                                 String prefix,
                                 TopologyService.CacheMetrics cache) {
        put(target, prefix + ".entries", cache.entries());
        put(target, prefix + ".highestEntries", cache.highestEntries());
        put(target, prefix + ".retainedBytes", cache.retainedBytes());
        put(target, prefix + ".highestRetainedBytes", cache.highestRetainedBytes());
    }

    private static void putEvents(Map<String, Long> target,
                                  TopologyService.EventMetrics events) {
        put(target, "worker.events.pendingKeys", events.pendingKeys());
        put(target, "worker.events.activeBatchKeys", events.activeBatchKeys());
        put(target, "worker.events.highestPendingKeys", events.highestPendingKeys());
        put(target, "worker.events.highestBatchKeys", events.highestBatchKeys());
        put(target, "worker.events.pendingEstimatedBytes", events.pendingEstimatedBytes());
        put(target, "worker.events.activeBatchEstimatedBytes", events.activeBatchEstimatedBytes());
        put(target, "worker.events.highestPendingEstimatedBytes", events.highestPendingEstimatedBytes());
        put(target, "worker.events.highestBatchEstimatedBytes", events.highestBatchEstimatedBytes());
        put(target, "worker.events.completedBatches", events.completedBatches());
        put(target, "worker.events.failedBatches", events.failedBatches());
        put(target, "worker.events.longestWaitNanos", events.longestWaitNanos());
        put(target, "worker.events.longestBatchNanos", events.longestBatchNanos());
        put(target, "worker.events.taskOutstanding", events.taskOutstanding() ? 1L : 0L);
        put(target, "worker.events.batchActive", events.batchActive() ? 1L : 0L);
    }

    private static void putTasks(Map<String, Long> target,
                                 String prefix,
                                 TopologyService.TaskMetrics tasks) {
        putTaskCounts(target, prefix + ".queued", tasks.queued());
        putTaskCounts(target, prefix + ".running", tasks.running());
        putTaskCounts(target, prefix + ".highestQueued", tasks.highestQueued());
        putTaskCounts(target, prefix + ".highestRunning", tasks.highestRunning());
        putTaskCounts(target, prefix + ".completed", tasks.completed());
        putTaskCounts(target, prefix + ".failed", tasks.failed());
    }

    private static void putTaskCounts(Map<String, Long> target,
                                      String prefix,
                                      TopologyService.TaskCounts counts) {
        put(target, prefix + ".control", counts.control());
        put(target, prefix + ".builds", counts.builds());
        put(target, prefix + ".quickSearches", counts.quickSearches());
        put(target, prefix + ".longSearches", counts.longSearches());
        put(target, prefix + ".prewarms", counts.prewarms());
    }

    public static Object readField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("test bridge could not read " + name, failure);
        }
    }

    public static void writeField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("test bridge could not write " + name, failure);
        }
    }

    public static Object invoke(Object target, String name, Object... arguments) {
        Method selected = null;
        for (Method method : target.getClass().getDeclaredMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
            Class<?>[] types = method.getParameterTypes();
            boolean matches = true;
            for (int i = 0; i < types.length; i++) {
                Class<?> type = types[i];
                if (type == long.class) type = Long.class;
                else if (type == int.class) type = Integer.class;
                else if (type == boolean.class) type = Boolean.class;
                if (arguments[i] != null && !type.isInstance(arguments[i])) matches = false;
            }
            if (!matches) continue;
            if (selected != null) throw new IllegalStateException("ambiguous test invocation " + name);
            selected = method;
        }
        if (selected == null) throw new IllegalStateException("missing test invocation " + name);
        try {
            selected.setAccessible(true);
            return selected.invoke(target, arguments);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("test invocation failed " + name, failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("test invocation failed " + name, failure);
        }
    }

    private static void put(Map<String, Long> target, String key, long value) {
        target.put(key, value);
    }
}
