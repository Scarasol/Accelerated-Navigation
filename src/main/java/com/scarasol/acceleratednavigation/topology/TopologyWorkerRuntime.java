package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.AcceleratedNavigation;
import com.scarasol.acceleratednavigation.api.ResumableSearch;
import com.scarasol.acceleratednavigation.api.ResumableSearch.Status;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Owns worker-side derived topology, searches, and immutable publication state. */
final class TopologyWorkerRuntime {

    private static final Direction[] DIRECTIONS = Direction.values();
    // One expansion can expose fourteen parent-boundary slots plus its two graph
    // dependencies.  This is a per-search live-wait limit, not a build quota.
    private static final int MAX_LIVE_SEARCH_DEPENDENCIES = 16;
    private static final int MIN_SUPER_CLUSTER_DISTANCE = 2;
    private static final int MAX_SUPER_CACHE_ENTRIES = 512;
    private static final long MAX_BASE_RETAINED_BYTES = 128L * 1024L * 1024L;
    private static final long MAX_SUPER_RETAINED_BYTES = 16L * 1024L * 1024L;
    private static final int MIN_QUERY_VISITED_NODES = 1_024;
    private static final int MIN_HIERARCHICAL_QUERY_VISITED_NODES = 2_048;
    private static final int MAX_QUERY_VISITED_NODES = 8_192;
    private static final int MAX_LOCAL_WITNESS_NODES = 512;
    private static final int MAX_FAST_SEARCH_NODES = 512;
    private static final int MAX_LONG_SEARCH_NODES = 256;
    private static final float QUERY_VISITED_NODES_PER_BLOCK = 8.0F;
    private static final int MAX_PREWARM_ADMITTED = 8;
    // Approximate event-table metadata only; shared facts and requests are not counted twice.
    private static final long SECTION_EVENT_METADATA_BYTES = 96L;
    private static final long SECTION_CHANGE_METADATA_BYTES = 32L;
    private static final long SIMPLE_EVENT_METADATA_BYTES = 48L;
    private static final long FLAG_EVENT_METADATA_BYTES = 8L;
    private static final BaseClusterTopology.GeometryKey DEFAULT_GEOMETRY =
            BaseClusterTopology.TraversalProfile.DEFAULT_GROUND.geometry(
                    BaseClusterTopology.Channel.GROUND);
    private static final int MAX_COMPLETED_CORRIDORS = 1_024;
    private static final long MAX_COMPLETED_CORRIDOR_BYTES = 16L * 1024L * 1024L;
    private static final TopologyTaskExecutor.TaskHandle UNTRACKED_TASK =
            TopologyTaskExecutor.TaskHandle.NONE;
    private static final TopologyTaskExecutor.TaskHandle SUBMITTING_TASK =
            completion -> completion.accept(false);

    private final TopologyTaskExecutor taskExecutor;
    private final FactDemandListener factDemandListener;
    private final AtomicBoolean prewarmAllowed = new AtomicBoolean(true);
    private final Object runtimeLock = new Object();
    private final ThreadLocal<List<Runnable>> afterRuntimeLock = new ThreadLocal<>();
    private Map<ClusterKey, SectionEvent> pendingSectionEvents = new HashMap<>();
    private Map<ClusterKey, SectionEvent> processingSectionEvents = Map.of();
    private Map<ChunkKey, Long> pendingChunkUnloads = new HashMap<>();
    private final IdentityHashMap<MacroRequest, RequestEvent> pendingRequestEvents =
            new IdentityHashMap<>();
    private Map<MacroRequest, RequestEvent> processingRequestEvents = Map.of();
    private boolean tickPending;
    private boolean eventTaskOutstanding;
    private boolean stopRequested;
    private boolean stopEventPending;
    private final Map<ClusterKey, ClusterEntry> clusters = new HashMap<>();
    private final Map<SuperCacheKey, SuperEntry> superClusters = new HashMap<>();
    private final Map<SuperOriginKey, Set<SuperCacheKey>> superKeysByOrigin = new HashMap<>();
    private final LinkedHashSet<BaseIdleEntry> idleBaseEntries = new LinkedHashSet<>();
    private final LinkedHashSet<ViewEntry> baseHandoffs = new LinkedHashSet<>();
    private final LinkedHashSet<SuperEntry> idleSuperEntries = new LinkedHashSet<>();
    private final LinkedHashSet<SuperEntry> superHandoffs = new LinkedHashSet<>();
    private final Set<MacroRequest> macroRequests =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<RawQueryKey, ResolveFlight> resolveFlights = new HashMap<>();
    private final Map<MacroQueryKey, MacroFlight> macroFlights = new HashMap<>();
    private final Set<MacroFlight> deferredSearchResumes =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final LinkedHashMap<MacroQueryKey, CachedCorridor> completedCorridors =
            new LinkedHashMap<>(32, 0.75F, true);
    private final LinkedHashMap<PrewarmKey, PrewarmCandidate> prewarmCandidates =
            new LinkedHashMap<>();
    private final BaseClusterTopology.BuildScratch buildScratch =
            new BaseClusterTopology.BuildScratch();
    private long baseRetainedBytes;
    private long superRetainedBytes;
    private int highestLogicalRequests;
    private int highestEndpointResolutions;
    private int highestPhysicalSearches;
    private int buildDemands;
    private int highestBuildDemands;
    private int dependencyConsumers;
    private int highestDependencyConsumers;
    private int liveSearchDependencies;
    private int highestLiveSearchDependencies;
    private int highestPrewarmCandidates;
    private int highestPrewarmAdmitted;
    private int activeReferences;
    private int highestActiveReferences;
    private int highestBaseCacheEntries;
    private long highestBaseRetainedBytes;
    private int highestSuperCacheEntries;
    private long highestSuperRetainedBytes;
    private int highestCorridorCacheEntries;
    private long highestCompletedCorridorBytes;
    private long completedCacheHits;
    private long physicalSearchesStarted;
    private long physicalSearchesSucceeded;
    private long physicalSearchesFailed;
    private long workerStaleResults;
    private long automaticStaleRetries;
    private long staleRetryExhaustions;
    private int pendingSectionChangeCells;
    private int highestPendingEventKeys;
    private int activeEventBatchKeys;
    private int highestEventBatchKeys;
    private long activeEventBatchEstimatedBytes;
    private long highestPendingEventEstimatedBytes;
    private long highestEventBatchEstimatedBytes;
    private long pendingEventSinceNanos;
    private long completedEventBatches;
    private long failedEventBatches;
    private long longestEventWaitNanos;
    private long longestEventBatchNanos;

    private boolean closed;
    private long topologyTick;
    private int prewarmAdmitted;
    private int pendingExecutorSubmissions;
    private long completedCorridorBytes;
    private boolean eventBatchActive;

    TopologyWorkerRuntime(FactDemandListener factDemandListener) {
        this.factDemandListener = Objects.requireNonNull(factDemandListener, "factDemandListener");
        this.taskExecutor = new TopologyTaskExecutor(prewarmAllowed::get);
    }

    /** Copies fixed counters only; task and runtime locks are never nested. */
    TopologyService.WorkerMetrics metrics() {
        TopologyService.TaskMetrics tasks = taskExecutor.metrics();
        synchronized (runtimeLock) {
            return new TopologyService.WorkerMetrics(
                    macroRequests.size(),
                    highestLogicalRequests,
                    resolveFlights.size(),
                    highestEndpointResolutions,
                    macroFlights.size(),
                    highestPhysicalSearches,
                    buildDemands,
                    highestBuildDemands,
                    dependencyConsumers,
                    highestDependencyConsumers,
                    liveSearchDependencies,
                    highestLiveSearchDependencies,
                    prewarmCandidates.size(),
                    highestPrewarmCandidates,
                    prewarmAdmitted,
                    highestPrewarmAdmitted,
                    activeReferences,
                    highestActiveReferences,
                    new TopologyService.CacheMetrics(
                            idleBaseEntries.size(), highestBaseCacheEntries,
                            baseRetainedBytes, highestBaseRetainedBytes),
                    new TopologyService.CacheMetrics(
                            idleSuperEntries.size(), highestSuperCacheEntries,
                            superRetainedBytes, highestSuperRetainedBytes),
                    new TopologyService.CacheMetrics(
                            completedCorridors.size(), highestCorridorCacheEntries,
                            completedCorridorBytes, highestCompletedCorridorBytes),
                    completedCacheHits,
                    physicalSearchesStarted,
                    physicalSearchesSucceeded,
                    physicalSearchesFailed,
                    workerStaleResults,
                    automaticStaleRetries,
                    staleRetryExhaustions,
                    new TopologyService.EventMetrics(
                            pendingEventKeyCount(), activeEventBatchKeys,
                            highestPendingEventKeys, highestEventBatchKeys,
                            pendingEventEstimatedBytes(), activeEventBatchEstimatedBytes,
                            highestPendingEventEstimatedBytes,
                            highestEventBatchEstimatedBytes,
                            completedEventBatches, failedEventBatches,
                            longestEventWaitNanos, longestEventBatchNanos,
                            eventTaskOutstanding, eventBatchActive),
                    tasks
            );
        }
    }

    private int pendingEventKeyCount() {
        return pendingSectionEvents.size() + pendingChunkUnloads.size()
                + pendingRequestEvents.size() + (tickPending ? 1 : 0)
                + (stopEventPending ? 1 : 0);
    }

    private long pendingEventEstimatedBytes() {
        return pendingSectionEvents.size() * SECTION_EVENT_METADATA_BYTES
                + pendingSectionChangeCells * SECTION_CHANGE_METADATA_BYTES
                + pendingChunkUnloads.size() * SIMPLE_EVENT_METADATA_BYTES
                + pendingRequestEvents.size() * SIMPLE_EVENT_METADATA_BYTES
                + (tickPending ? FLAG_EVENT_METADATA_BYTES : 0L)
                + (stopEventPending ? FLAG_EVENT_METADATA_BYTES : 0L);
    }

    private void recordPendingEvent() {
        int pending = pendingEventKeyCount();
        highestPendingEventKeys = Math.max(highestPendingEventKeys, pending);
        highestPendingEventEstimatedBytes = Math.max(
                highestPendingEventEstimatedBytes, pendingEventEstimatedBytes());
        if (pending != 0 && pendingEventSinceNanos == 0L) {
            pendingEventSinceNanos = System.nanoTime();
        }
    }

    private void beginBuildDemand() {
        buildDemands++;
        highestBuildDemands = Math.max(highestBuildDemands, buildDemands);
    }

    private void finishBuildDemand() {
        if (--buildDemands < 0) {
            throw new IllegalStateException("build demand count became negative");
        }
    }

    private void beginDependencyConsumer() {
        dependencyConsumers++;
        highestDependencyConsumers = Math.max(
                highestDependencyConsumers, dependencyConsumers);
    }

    private void finishDependencyConsumer() {
        if (--dependencyConsumers < 0) {
            throw new IllegalStateException("dependency consumer count became negative");
        }
    }

    private void adjustLiveSearchDependencies(int delta) {
        liveSearchDependencies += delta;
        if (liveSearchDependencies < 0) {
            throw new IllegalStateException("live search dependency count became negative");
        }
        highestLiveSearchDependencies = Math.max(
                highestLiveSearchDependencies, liveSearchDependencies);
    }

    private void addActiveReference() {
        activeReferences++;
        highestActiveReferences = Math.max(highestActiveReferences, activeReferences);
    }

    private void removeActiveReference() {
        if (--activeReferences < 0) {
            throw new IllegalStateException("active topology reference count became negative");
        }
    }

    private void recordBaseCachePeak() {
        highestBaseCacheEntries = Math.max(highestBaseCacheEntries, idleBaseEntries.size());
        highestBaseRetainedBytes = Math.max(highestBaseRetainedBytes, baseRetainedBytes);
    }

    private void recordSuperCachePeak() {
        highestSuperCacheEntries = Math.max(highestSuperCacheEntries, idleSuperEntries.size());
        highestSuperRetainedBytes = Math.max(highestSuperRetainedBytes, superRetainedBytes);
    }

    private void recordCorridorCachePeak() {
        highestCorridorCacheEntries = Math.max(
                highestCorridorCacheEntries, completedCorridors.size());
        highestCompletedCorridorBytes = Math.max(
                highestCompletedCorridorBytes, completedCorridorBytes);
    }

    private void runRuntimeTransition(Runnable command) {
        runtimeTransition(true, () -> {
            command.run();
            return null;
        });
    }

    private <T> T runtimeTransition(Supplier<T> command) {
        return runtimeTransition(true, command);
    }

    private void runBatchedRuntimeTransition(Runnable command) {
        runtimeTransition(false, () -> {
            command.run();
            return null;
        });
    }

    private <T> T runtimeTransition(boolean allowPrewarmAdmission, Supplier<T> command) {
        Objects.requireNonNull(command, "command");
        if (Thread.holdsLock(runtimeLock)) {
            return command.get();
        }
        List<Runnable> deferred = new ArrayList<>();
        boolean wakePrewarm;
        T result = null;
        RuntimeException runtimeFailure = null;
        Error error = null;
        synchronized (runtimeLock) {
            afterRuntimeLock.set(deferred);
            try {
                result = command.get();
                if (allowPrewarmAdmission) admitPrewarm();
            } catch (RuntimeException failure) {
                runtimeFailure = failure;
            } catch (Error failure) {
                error = failure;
            } finally {
                boolean allowed = !closed && !stopRequested && !foregroundWorkPresent();
                wakePrewarm = allowed && !prewarmAllowed.getAndSet(allowed);
                afterRuntimeLock.remove();
            }
        }
        deferred.forEach(Runnable::run);
        if (wakePrewarm) taskExecutor.signalWork();
        if (runtimeFailure != null) throw runtimeFailure;
        if (error != null) throw error;
        return result;
    }

    private void afterRuntimeLock(Runnable action) {
        List<Runnable> deferred = afterRuntimeLock.get();
        if (deferred == null) {
            action.run();
        } else {
            deferred.add(action);
        }
    }

    private void submitOutsideRuntimeLock(
            Supplier<TopologyTaskExecutor.TaskHandle> submission,
            Consumer<TopologyTaskExecutor.TaskHandle> accepted,
            Consumer<Throwable> rejected) {
        requireRuntimeLock();
        pendingExecutorSubmissions++;
        afterRuntimeLock(() -> {
            TopologyTaskExecutor.TaskHandle submitted;
            try {
                submitted = submission.get();
            } catch (RuntimeException failure) {
                runRuntimeTransition(() -> finishExecutorSubmission(
                        () -> rejected.accept(failure)));
                return;
            }
            runRuntimeTransition(() -> finishExecutorSubmission(
                    () -> accepted.accept(submitted)));
        });
    }

    private void finishExecutorSubmission(Runnable completion) {
        requireRuntimeLock();
        try {
            completion.run();
        } finally {
            if (--pendingExecutorSubmissions < 0) {
                throw new IllegalStateException("executor submission count became negative");
            }
            if (closed && pendingExecutorSubmissions == 0) {
                afterRuntimeLock(taskExecutor::shutdown);
            }
        }
    }

    private void reprioritizeTask(TopologyTaskExecutor.TaskHandle task,
                                  NavigationScheduler.Priority priority) {
        if (task == UNTRACKED_TASK || task == SUBMITTING_TASK) return;
        afterRuntimeLock(() -> task.reprioritize(priority));
    }

    private void promoteTask(TopologyTaskExecutor.TaskHandle task) {
        if (task == UNTRACKED_TASK || task == SUBMITTING_TASK) return;
        afterRuntimeLock(task::promoteBuild);
    }

    private void cancelQueuedTask(TopologyTaskExecutor.TaskHandle task,
                                  Consumer<Boolean> completion) {
        if (task == UNTRACKED_TASK) {
            completion.accept(false);
            return;
        }
        afterRuntimeLock(() -> task.cancelWhenQueued(
                removed -> runRuntimeTransition(() -> completion.accept(removed))));
    }

    private <T> void finishFuture(CompletableFuture<T> future,
                                  @Nullable T value,
                                  @Nullable Throwable failure) {
        afterRuntimeLock(() -> {
            if (failure == null) future.complete(value);
            else future.completeExceptionally(failure);
        });
    }

    private <T> void forwardFuture(CompletableFuture<T> source,
                                   CompletableFuture<T> target) {
        afterRuntimeLock(() -> source.whenComplete((value, failure) -> {
            if (failure == null) target.complete(value);
            else target.completeExceptionally(failure);
        }));
    }

    void publishSection(SectionEvent event) {
        boolean schedule;
        synchronized (runtimeLock) {
            if (closed || stopRequested) return;
            ClusterEntry entry = clusters.computeIfAbsent(event.key(), ClusterEntry::new);
            LatestFacts latest = entry.latest.get();
            if (latest.loadIdentity() > event.loadIdentity()
                    || latest.loadIdentity() == event.loadIdentity()
                    && (latest.revision() > event.version()
                    || terminalStateRejects(latest.state(), event.state()))) {
                return;
            }
            SectionEvent queued = pendingSectionEvents.get(event.key());
            SectionEvent previous = queued != null
                    ? queued : processingSectionEvents.get(event.key());
            if (previous == null && sectionEventAlreadyApplied(entry, event)) return;
            SectionEvent merged = mergeSectionEvent(previous, event);
            if (queued == null && sameSectionEvent(previous, merged)) return;
            boolean invalidates = latest.loadIdentity() != merged.loadIdentity()
                    || latest.revision() != merged.version()
                    || latest.state() != merged.state()
                    || sameVersionFactsChanged(entry, merged);
            if (invalidates) {
                entry.latest.set(merged.loadIdentity(), merged.version(), merged.state());
                markLatestDerivedStale(event.key(), merged.state());
            }
            pendingSectionEvents.put(event.key(), merged);
            pendingSectionChangeCells += merged.changes().size()
                    - (queued == null ? 0 : queued.changes().size());
            recordPendingEvent();
            // A correctness event has arrived; do not admit idle work until this
            // event and any state it coalesces with have been consumed.
            prewarmAllowed.set(false);
            schedule = !eventTaskOutstanding;
            if (schedule) eventTaskOutstanding = true;
        }
        if (schedule) scheduleEventTask();
    }

    private static boolean terminalStateRejects(FactState current, FactState next) {
        if (current == FactState.UNLOADED) return next != FactState.UNLOADED;
        return current == FactState.RECOVERY_FAILED
                && next != FactState.RECOVERY_FAILED && next != FactState.UNLOADED;
    }

    private static boolean sameSectionEvent(@Nullable SectionEvent first,
                                            SectionEvent second) {
        if (first == null || first.loadIdentity() != second.loadIdentity()
                || first.previousVersion() != second.previousVersion()
                || first.version() != second.version() || first.state() != second.state()
                || !first.changes().equals(second.changes())) return false;
        return first.facts() == second.facts()
                || first.facts() != null && second.facts() != null
                && first.facts().contentEquals(second.facts());
    }

    private static boolean sameVersionFactsChanged(ClusterEntry entry, SectionEvent event) {
        return entry.loadIdentity == event.loadIdentity()
                && entry.revision == event.version()
                && entry.latest.state() == FactState.AVAILABLE
                && event.state() == FactState.AVAILABLE && event.facts() != null
                && knownFactsChanged(entry, event.facts());
    }

    private static boolean knownFactsChanged(ClusterEntry entry,
                                             BaseClusterTopology.PackedFacts facts) {
        return entry.facts != null
                ? !entry.facts.contentEquals(facts)
                : entry.factFingerprintKnown
                && entry.factFingerprint != facts.fingerprint();
    }

    private static boolean sectionEventAlreadyApplied(ClusterEntry entry, SectionEvent event) {
        if (entry.loadIdentity != event.loadIdentity() || entry.revision != event.version()
                || entry.factState != event.state()) return false;
        if (!event.changes().isEmpty() || event.facts() == null) return true;
        return entry.facts != null && entry.facts.contentEquals(event.facts());
    }

    private void markLatestDerivedStale(ClusterKey source, FactState nextState) {
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                ClusterKey affected = new ClusterKey(source.dimension(), SectionPos.of(
                        source.section().x() + dx, source.section().y() + dy,
                        source.section().z() + dz));
                ClusterEntry cluster = clusters.get(affected);
                if (cluster != null) for (ViewEntry view : cluster.views.values()) {
                    boolean direct = (dx | dy | dz) == 0;
                    boolean affectedView = direct || (nextState == FactState.AVAILABLE
                            ? requiresHalo(view.geometry, -dx, -dy, -dz)
                            : view.topology != null && referencesHalo(
                            view.topology, -dx, -dy, -dz));
                    if (affectedView) view.validity++;
                }
                Set<SuperCacheKey> parents = superKeysByOrigin.get(new SuperOriginKey(
                        source.dimension(), SuperClusterTopology.originOf(affected.section())));
                if (parents != null) for (SuperCacheKey key : parents) {
                    if ((dx | dy | dz) == 0 || requiresHalo(key.geometry(), -dx, -dy, -dz)) {
                        SuperEntry parent = superClusters.get(key);
                        if (parent != null) parent.validity++;
                    }
                }
            }
        }
    }

    private static SectionEvent mergeSectionEvent(@Nullable SectionEvent previous,
                                                  SectionEvent next) {
        if (previous == null || next.loadIdentity() > previous.loadIdentity()) return next;
        if (next.loadIdentity() < previous.loadIdentity()
                || terminalStateRejects(previous.state(), next.state())) return previous;
        if (next.state() == FactState.UNLOADED
                || next.state() == FactState.RECOVERY_FAILED) return next;
        if (next.version() < previous.version()) return previous;
        if (next.state() != FactState.AVAILABLE || next.changes().isEmpty()
                || next.facts() != null) return next;

        boolean contiguous = next.version() == previous.version()
                || next.previousVersion() == previous.version();
        if (!contiguous || previous.state() != FactState.AVAILABLE) return next;
        Map<Integer, Byte> changes = new HashMap<>(previous.changes());
        changes.putAll(next.changes());
        return new SectionEvent(next.key(), next.loadIdentity(),
                previous.previousVersion(), next.version(), FactState.AVAILABLE,
                previous.facts(), changes);
    }

    void publishChunkUnload(ResourceKey<Level> dimension,
                            ChunkPos chunk,
                            long loadIdentity) {
        boolean schedule;
        synchronized (runtimeLock) {
            if (closed || stopRequested) return;
            pendingChunkUnloads.merge(
                    new ChunkKey(dimension, chunk.toLong()), loadIdentity, Math::max);
            recordPendingEvent();
            prewarmAllowed.set(false);
            schedule = !eventTaskOutstanding;
            if (schedule) eventTaskOutstanding = true;
        }
        if (schedule) scheduleEventTask();
    }

    private void scheduleEventTask() {
        try {
            taskExecutor.submitControl(this::processSectionEvents);
        } catch (RejectedExecutionException ignored) {
            synchronized (runtimeLock) {
                eventTaskOutstanding = false;
            }
        }
    }

    private void processSectionEvents() {
        Map<ClusterKey, SectionEvent> batch;
        Map<ChunkKey, Long> chunkUnloads;
        IdentityHashMap<MacroRequest, RequestEvent> requests;
        boolean tick;
        boolean stop;
        long batchStartedNanos = System.nanoTime();
        long batchQueuedNanos;
        synchronized (runtimeLock) {
            long batchEstimatedBytes = pendingEventEstimatedBytes();
            batch = pendingSectionEvents;
            pendingSectionEvents = new HashMap<>();
            pendingSectionChangeCells = 0;
            processingSectionEvents = batch;
            chunkUnloads = pendingChunkUnloads;
            pendingChunkUnloads = new HashMap<>();
            requests = new IdentityHashMap<>(pendingRequestEvents);
            pendingRequestEvents.clear();
            processingRequestEvents = requests;
            eventBatchActive = true;
            if (!requests.isEmpty()) {
                // A foreground event has priority even before its control batch runs.
                prewarmAllowed.set(false);
            }
            tick = tickPending;
            tickPending = false;
            stop = stopEventPending;
            stopEventPending = false;
            batchQueuedNanos = pendingEventSinceNanos;
            pendingEventSinceNanos = 0L;
            activeEventBatchKeys = batch.size() + chunkUnloads.size() + requests.size()
                    + (tick ? 1 : 0) + (stop ? 1 : 0);
            activeEventBatchEstimatedBytes = batchEstimatedBytes;
            highestEventBatchKeys = Math.max(
                    highestEventBatchKeys, activeEventBatchKeys);
            highestEventBatchEstimatedBytes = Math.max(
                    highestEventBatchEstimatedBytes, activeEventBatchEstimatedBytes);
        }
        Throwable processingFailure = null;
        try {
            if (stop) {
                runBatchedRuntimeTransition(() -> {
                    requests.forEach(this::cancelRequestEvent);
                    closeState();
                });
            } else {
                for (SectionEvent event : List.copyOf(batch.values())) {
                    runBatchedRuntimeTransition(() -> {
                        applySectionEvent(event);
                        // A later event must merge from the state just applied, not from
                        // this old batch value. Keep removal in the same lock transition.
                        processingSectionEvents.remove(event.key(), event);
                    });
                }
                for (Map.Entry<ChunkKey, Long> unloaded : chunkUnloads.entrySet()) {
                    runBatchedRuntimeTransition(() -> {
                        ChunkKey chunk = unloaded.getKey();
                        removePrewarm(chunk.dimension(), chunk.chunkLong(), unloaded.getValue());
                    });
                }
                requests.forEach((request, event) ->
                        runBatchedRuntimeTransition(() -> {
                            applyRequestEvent(request, event);
                            processingRequestEvents.remove(request, event);
                        }));
                if (tick) {
                    runBatchedRuntimeTransition(() -> {
                        topologyTick++;
                        releaseHandoffs();
                        evictBaseCache();
                        evictSuperCache();
                    });
                }
            }
        } catch (VirtualMachineError | ThreadDeath fatal) {
            processingFailure = fatal;
            throw fatal;
        } catch (Throwable failure) {
            processingFailure = failure;
            AcceleratedNavigation.LOGGER.error(
                    "Topology correctness event processing failed; stopping the worker runtime",
                    failure);
            try {
                runRuntimeTransition(() -> {
                    requests.forEach(this::cancelRequestEvent);
                    pendingRequestEvents.forEach(this::cancelRequestEvent);
                    pendingRequestEvents.clear();
                    pendingSectionEvents.clear();
                    pendingSectionChangeCells = 0;
                    pendingChunkUnloads.clear();
                    tickPending = false;
                    closeState();
                });
            } catch (Throwable closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        } finally {
            synchronized (runtimeLock) {
                if (processingSectionEvents == batch) processingSectionEvents = Map.of();
                if (processingRequestEvents == requests) processingRequestEvents = Map.of();
                if (stop || processingFailure != null) {
                    eventBatchActive = false;
                    deferredSearchResumes.clear();
                }
                completedEventBatches++;
                if (processingFailure != null) failedEventBatches++;
                longestEventWaitNanos = Math.max(longestEventWaitNanos,
                        batchQueuedNanos == 0L ? 0L
                                : Math.max(0L, batchStartedNanos - batchQueuedNanos));
                longestEventBatchNanos = Math.max(longestEventBatchNanos,
                        Math.max(0L, System.nanoTime() - batchStartedNanos));
                activeEventBatchKeys = 0;
                activeEventBatchEstimatedBytes = 0L;
            }
        }
        if (!stop && processingFailure == null) {
            runRuntimeTransition(this::finishEventBatch);
        }
        if (stop || processingFailure != null) {
            synchronized (runtimeLock) {
                eventTaskOutstanding = false;
            }
            return;
        }
        boolean requeue;
        synchronized (runtimeLock) {
            requeue = stopEventPending || !pendingSectionEvents.isEmpty()
                    || !pendingChunkUnloads.isEmpty() || !pendingRequestEvents.isEmpty()
                    || tickPending;
            if (!requeue) eventTaskOutstanding = false;
        }
        if (requeue && !taskExecutor.requeueControlCurrent(this::processSectionEvents)) {
            synchronized (runtimeLock) {
                eventTaskOutstanding = false;
            }
            scheduleEventTaskIfPending();
        }
    }

    private void cancelRequestEvent(MacroRequest request, RequestEvent event) {
        if (event.response() != null) finishFuture(event.response(), null,
                new CancellationException("topology service stopped"));
        request.cancelInternal();
    }

    private void scheduleEventTaskIfPending() {
        boolean schedule;
        synchronized (runtimeLock) {
            boolean pending = stopEventPending || !pendingSectionEvents.isEmpty()
                    || !pendingChunkUnloads.isEmpty() || !pendingRequestEvents.isEmpty()
                    || tickPending;
            schedule = pending && !eventTaskOutstanding && !closed;
            if (schedule) eventTaskOutstanding = true;
        }
        if (schedule) scheduleEventTask();
    }

    private void finishEventBatch() {
        requireRuntimeLock();
        eventBatchActive = false;
        List<MacroFlight> resumable = List.copyOf(deferredSearchResumes);
        deferredSearchResumes.clear();
        resumable.forEach(flight -> afterRuntimeLock(
                () -> runRuntimeTransition(flight::resume)));
    }

    private void applySectionEvent(SectionEvent event) {
        ClusterEntry entry = clusters.computeIfAbsent(event.key(), ClusterEntry::new);
        LatestFacts latest = entry.latest.get();
        if (latest.loadIdentity() != event.loadIdentity()
                || latest.revision() != event.version()
                || latest.state() != event.state()
                || entry.loadIdentity > event.loadIdentity()
                || entry.loadIdentity == event.loadIdentity()
                && entry.revision > event.version()) {
            return;
        }
        if (!event.changes().isEmpty() && entry.loadIdentity == event.loadIdentity()
                && entry.revision == event.version()) return;
        BaseClusterTopology.PackedFacts nextFacts = event.facts();
        FactState nextState = event.state();
        if (!event.changes().isEmpty()) {
            if (nextFacts != null) {
                nextFacts = nextFacts.withChanges(event.changes());
            } else if (entry.loadIdentity == event.loadIdentity()
                    && entry.revision == event.previousVersion()
                    && entry.facts != null) {
                nextFacts = entry.facts.withChanges(event.changes());
            } else {
                nextState = FactState.PENDING;
            }
        }
        boolean identityChanged = entry.loadIdentity != event.loadIdentity()
                || entry.revision != event.version();
        // A same-version fact reload after cache eviction restores the canonical
        // source object; it does not invalidate already-derived topology.
        boolean factsChanged = entry.loadIdentity == event.loadIdentity()
                && entry.revision == event.version()
                && nextState == FactState.AVAILABLE && nextFacts != null
                && knownFactsChanged(entry, nextFacts);
        boolean restoresEvictedFacts = entry.loadIdentity == event.loadIdentity()
                && entry.revision == event.version()
                && entry.factState == FactState.PENDING && entry.facts == null
                && nextState == FactState.AVAILABLE && nextFacts != null;
        boolean stateChanged = entry.factState != nextState && !restoresEvictedFacts;
        if (identityChanged || factsChanged || stateChanged) {
            discardDerived(event.key(), entry, event.version(), nextState);
        }
        BaseClusterTopology.PackedFacts previousFacts = entry.facts;
        if (previousFacts != null) {
            removeIdleFact(entry);
        }
        entry.loadIdentity = event.loadIdentity();
        entry.revision = event.version();
        entry.factState = nextState;
        entry.facts = nextFacts;
        if (nextFacts != null) {
            entry.factFingerprint = nextFacts.fingerprint();
            entry.factFingerprintKnown = true;
        } else if (identityChanged || nextState != FactState.AVAILABLE) {
            entry.factFingerprintKnown = false;
        }
        if (entry.facts != null) {
            releaseUnusedFacts(entry);
        }
        if (identityChanged || factsChanged || stateChanged) {
            invalidateHaloDependents(event.key());
        }
        wakeFactWaiters(entry);
        evictBaseCache();
        pruneCluster(entry);
    }

    private void discardDerived(ClusterKey key,
                                ClusterEntry entry,
                                long version,
                                FactState nextState) {
        for (ViewEntry view : List.copyOf(entry.views.values())) {
            if (view.topology != null) {
                BaseClusterTopology stale = view.topology;
                invalidateBaseBoundaryLinks(key, stale);
                retireBaseTopology(view);
                view.topology = null;
            }
            TopologyDemand demand = view.demand;
            if (demand == null) continue;
            if (eventUnavailable(nextState)) {
                failDemand(entry, demand, nextState == FactState.RECOVERY_FAILED
                        ? new FactsRecoveryException(key)
                        : new StaleTopologyException(key));
                continue;
            }
            boolean prewarm = demand.prewarmSlot;
            stopDemandWork(demand);
            TopologyDemand replacement = new TopologyDemand(key, demand.geometry, version);
            replacement.priority = demand.priority;
            replacement.prewarmSlot = prewarm;
            if (prewarm) {
                prewarmAdmitted++;
                highestPrewarmAdmitted = Math.max(
                        highestPrewarmAdmitted, prewarmAdmitted);
            }
            replacement.waiters.addAll(demand.waiters);
            for (TopologyWaiter<BaseClusterTopology> waiter : replacement.waiters) {
                waiter.demand = replacement;
            }
            demand.waiters.clear();
            view.demand = replacement;
            prepareDemand(entry, replacement);
        }
        pruneViews(entry);
        invalidateSuperParent(key, eventUnavailable(nextState));
    }

    private static boolean eventUnavailable(FactState state) {
        return state == FactState.UNLOADED || state == FactState.RECOVERY_FAILED;
    }

    private void wakeFactWaiters(ClusterEntry entry) {
        if (entry.factWaiters.isEmpty()) return;
        List<TopologyDemand> waiters = List.copyOf(entry.factWaiters);
        for (TopologyDemand demand : waiters) clearFactWaits(demand);
        for (TopologyDemand demand : waiters) {
            ClusterEntry owner = clusters.get(demand.key);
            if (owner != null) prepareDemand(owner, demand);
        }
    }

    void endServerTick() {
        boolean schedule;
        synchronized (runtimeLock) {
            if (closed || stopRequested || baseHandoffs.isEmpty() && superHandoffs.isEmpty()) {
                return;
            }
            tickPending = true;
            recordPendingEvent();
            prewarmAllowed.set(false);
            schedule = !eventTaskOutstanding;
            if (schedule) eventTaskOutstanding = true;
        }
        if (schedule) scheduleEventTask();
    }

    boolean retainFactsForPersistence(ClusterKey key, long loadIdentity, long version,
                                      BaseClusterTopology.PackedFacts facts) {
        return runtimeTransition(false, () -> {
            ClusterEntry entry = clusters.get(key);
            if (entry == null || entry.latest.loadIdentity() != loadIdentity
                    || entry.latest.revision() != version) return false;
            if (entry.facts == facts) removeIdleFact(entry);
            entry.pinnedFacts.merge(facts, 1, Integer::sum);
            addActiveReference();
            return true;
        });
    }

    void releaseFactsForPersistence(ClusterKey key,
                                    BaseClusterTopology.PackedFacts facts) {
        runBatchedRuntimeTransition(() -> {
            ClusterEntry entry = clusters.get(key);
            if (entry != null) {
                Integer count = entry.pinnedFacts.remove(facts);
                if (count != null && count > 1) entry.pinnedFacts.put(facts, count - 1);
            }
            removeActiveReference();
            if (entry == null) return;
            releaseUnusedFacts(entry);
            pruneCluster(entry);
        });
    }

    private boolean requestClusterDependency(
            ResourceKey<Level> dimension,
            SectionPos section,
            BaseClusterTopology.GeometryKey geometry,
            NavigationScheduler.Priority priority,
            boolean prewarm,
            @Nullable TopologyWaiter<BaseClusterTopology> waiter) {
        requireRuntimeLock();
        ensureOpen();
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(priority, "priority");
        ClusterKey key = new ClusterKey(dimension, section);
        ClusterEntry entry = clusters.get(key);
        if (entry == null || entry.latest.state() == FactState.UNLOADED
                || entry.latest.state() == FactState.RECOVERY_FAILED) {
            if (waiter != null) waiter.complete(null,
                    entry != null && entry.latest.state() == FactState.RECOVERY_FAILED
                            ? new FactsRecoveryException(key)
                            : new IllegalStateException(
                            "topology request cannot load an unavailable chunk"));
            return false;
        }
        ViewEntry view = entry.view(geometry);
        BaseClusterTopology ready = entry.topology(geometry);
        if (ready != null && ready.revision() == entry.revision) {
            touchBase(view);
            if (waiter != null) waiter.complete(ready, null);
            return false;
        }
        removeIdleBase(view);
        TopologyDemand demand = view.demand;
        if (demand == null) {
            demand = new TopologyDemand(
                    key,
                    geometry,
                    entry.latest.revision()
            );
            view.demand = demand;
            demand.prewarmSlot = prewarm;
        } else if (!prewarm && demand.prewarmSlot) {
            releasePrewarmSlot(demand);
            if (demand.buildTask != UNTRACKED_TASK) {
                promoteTask(demand.buildTask);
            }
        }
        if (waiter != null) {
            waiter.demand = demand;
            waiter.cancellation = () -> cancelClusterWaiter(waiter);
            waiter.reprioritization = requested -> reconcileClusterWaiter(waiter, requested);
            demand.waiters.add(waiter);
        }
        NavigationScheduler.Priority previous = demand.priority;
        demand.priority = higherPriority(previous, priority);
        if (demand.buildTask != UNTRACKED_TASK && previous != demand.priority) {
            reprioritizeTask(demand.buildTask, demand.priority);
        }
        prepareDemand(entry, demand);
        return demand.prewarmSlot;
    }

    private void requestSuperCluster(
            ResourceKey<Level> dimension,
            SectionPos origin,
            BaseClusterTopology.Channel channel,
            BaseClusterTopology.TraversalProfile profile,
            NavigationScheduler.Priority priority,
            TopologyWaiter<SuperClusterTopology> waiter) {
        requireRuntimeLock();
        ensureOpen();
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(priority, "priority");
        if (!SuperClusterTopology.originOf(origin).equals(origin)) {
            throw new IllegalArgumentException("super-cluster origin is not aligned");
        }
        SuperCacheKey key = new SuperCacheKey(dimension, origin, channel, profile);
        if (!superClusterAvailable(dimension, origin)) {
            waiter.complete(null,
                    new IllegalStateException("super topology cannot use unavailable sections"));
            return;
        }

        SuperEntry entry = superEntry(key);
        SuperClusterTopology ready = superTopology(key);
        if (ready != null) {
            waiter.complete(ready, null);
            return;
        }
        removeIdleSuper(entry);
        waiter.cancellation = () -> cancelSuperWaiter(key, entry, waiter);
        waiter.reprioritization = requested -> reconcileSuperWaiter(
                entry,
                waiter,
                requested
        );
        if (entry.waiters.isEmpty()) {
            beginBuildDemand();
        }
        entry.waiters.add(waiter);
        NavigationScheduler.Priority previousPriority = entry.requestPriority;
        entry.requestPriority = higherPriority(previousPriority, priority);
        if (entry.buildTask != null && previousPriority != entry.requestPriority) {
            reprioritizeTask(entry.buildTask, entry.requestPriority);
        }
        if (entry.attemptRunning) {
            reconcileSuperChildren(entry);
        }
        if (!entry.attemptRunning) {
            beginSuperRequest(key, entry);
        }
    }

    private void beginSuperRequest(SuperCacheKey key,
                                   SuperEntry entry) {
        requireRuntimeLock();
        if (closed || entry.waiters.isEmpty()) {
            return;
        }
        entry.attemptRunning = true;
        long attempt = ++entry.attempt;
        List<TopologyWaiter<BaseClusterTopology>> children = new ArrayList<>(8);
        entry.collectingChildren = true;
        entry.children = children;
        try {
            for (SectionPos child : SuperClusterTopology.childSections(key.origin())) {
                TopologyWaiter<BaseClusterTopology> waiter = new TopologyWaiter<>(
                        entry.requestPriority,
                        ignored -> completeSuperChildren(key, entry, attempt));
                children.add(waiter);
                requestClusterDependency(key.dimension(), child, key.geometry(),
                        entry.requestPriority, false, waiter);
            }
        } catch (RuntimeException failure) {
            children.forEach(TopologyWaiter::cancel);
            entry.collectingChildren = false;
            entry.children = List.of();
            entry.attemptRunning = false;
            failSuperRequest(key, entry, failure);
            return;
        }
        entry.collectingChildren = false;
        entry.children = List.copyOf(children);
        completeSuperChildren(key, entry, attempt);
    }

    private void completeSuperChildren(SuperCacheKey key,
                                       SuperEntry expected,
                                       long attempt) {
        requireRuntimeLock();
        SuperEntry entry = superClusters.get(key);
        if (entry != expected || entry.attempt != attempt || closed
                || entry.waiters.isEmpty() || entry.collectingChildren
                || entry.children.stream().anyMatch(waiter -> waiter.active)) {
            return;
        }
        Throwable failure = entry.children.stream().map(waiter -> waiter.failure)
                .filter(Objects::nonNull).findFirst().orElse(null);
        entry.children = List.of();
        if (failure != null) {
            entry.attemptRunning = false;
            if (retryableAttemptFailure(failure)
                    && superClusterAvailable(key.dimension(), key.origin())) {
                beginSuperRequest(key, entry);
            } else {
                failSuperRequest(key, entry, failure);
            }
            return;
        }
        entry.buildTask = SUBMITTING_TASK;
        submitOutsideRuntimeLock(
                () -> taskExecutor.submit(entry.requestPriority,
                        () -> buildSuperCluster(key, entry, attempt)),
                submitted -> {
                    if (entry.buildTask != SUBMITTING_TASK || entry.attempt != attempt) {
                        afterRuntimeLock(() -> submitted.cancelWhenQueued(ignored -> {
                        }));
                        return;
                    }
                    entry.buildTask = submitted;
                    afterRuntimeLock(() -> submitted.reprioritize(entry.requestPriority));
                },
                submitFailure -> {
                    if (superClusters.get(key) != entry || entry.attempt != attempt
                            || entry.buildTask != SUBMITTING_TASK) return;
                    entry.buildTask = null;
                    entry.attemptRunning = false;
                    failSuperRequest(key, entry, submitFailure);
                });
    }

    private void buildSuperCluster(SuperCacheKey key,
                                   SuperEntry expected,
                                   long attempt) {
        try {
            BaseClusterTopology[] childSnapshot = runtimeTransition(
                    () -> beginSuperBuild(key, expected, attempt));
            if (childSnapshot == null) return;
            SuperClusterTopology topology = SuperClusterTopology.build(
                    key.origin(),
                    childSnapshot,
                    key.geometry(),
                    key.movement(),
                    buildScratch
            );
            runRuntimeTransition(() -> publishSuperCluster(
                    key,
                    expected,
                    attempt,
                    childSnapshot,
                    topology
            ));
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable failure) {
            runRuntimeTransition(() -> failSuperBuild(key, expected, attempt, failure));
        }
    }

    @Nullable
    private BaseClusterTopology[] beginSuperBuild(SuperCacheKey key,
                                                  SuperEntry expected,
                                                  long attempt) {
        SuperEntry entry = superClusters.get(key);
        if (closed || entry != expected || entry.attempt != attempt
                || entry.buildTask == null || entry.waiters.isEmpty()) {
            if (entry == expected) pruneSuperEntry(entry);
            return null;
        }
        BaseClusterTopology[] inputs = currentChildTopologies(key);
        List<ViewEntry> owners = inputs == null ? null : pinBaseInputs(key.dimension(), inputs);
        List<SectionStamp> stamps = owners == null ? null
                : mergeBaseStamps(key.dimension(), inputs);
        if (owners == null || stamps == null) {
            if (owners != null) releaseBaseInputs(owners, inputs);
            entry.buildTask = null;
            entry.attemptRunning = false;
            beginSuperRequest(key, entry);
            return null;
        }
        entry.buildInputs = inputs;
        entry.buildInputOwners = owners;
        entry.buildStamps = stamps;
        return inputs;
    }

    private void publishSuperCluster(SuperCacheKey key,
                                     SuperEntry expected,
                                     long attempt,
                                     BaseClusterTopology[] childSnapshot,
                                     SuperClusterTopology topology) {
        requireRuntimeLock();
        SuperEntry entry = superClusters.get(key);
        if (entry != expected || entry.attempt != attempt || closed
                || entry.waiters.isEmpty()) {
            expected.buildTask = null;
            releaseSuperBuildInputs(expected);
            pruneSuperEntry(expected);
            return;
        }
        BaseClusterTopology[] current = currentChildTopologies(key);
        List<SectionStamp> topologyStamps = entry.buildStamps;
        boolean inputsCurrent = current != null && Arrays.equals(childSnapshot, current)
                && topology.matchesChildren(current) && !topologyStamps.isEmpty()
                && isCurrent(topologyStamps);
        entry.buildTask = null;
        releaseSuperBuildInputs(entry);
        if (!inputsCurrent) {
            entry.attemptRunning = false;
            if (!superClusterAvailable(key.dimension(), key.origin())) {
                failSuperRequest(key, entry, new StaleTopologyException(
                        new ClusterKey(key.dimension(), key.origin())
                ));
            } else {
                beginSuperRequest(key, entry);
            }
            return;
        }

        removeSuperTopology(key, entry);
        entry.topology = topology;
        entry.topologyStamps = topologyStamps;
        entry.topologyValidity = entry.validity;
        entry.handoffUntilTick = topologyTick + 1L;
        superHandoffs.add(entry);
        entry.attemptRunning = false;
        List<TopologyWaiter<SuperClusterTopology>> waiters = List.copyOf(entry.waiters);
        entry.waiters.clear();
        finishBuildDemand();
        entry.requestPriority = null;
        waiters.forEach(waiter -> waiter.complete(topology, null));
        evictSuperCache();
    }

    private void failSuperBuild(SuperCacheKey key,
                                SuperEntry expected,
                                long attempt,
                                Throwable failure) {
        requireRuntimeLock();
        expected.buildTask = null;
        SuperEntry entry = superClusters.get(key);
        releaseSuperBuildInputs(expected);
        if (entry != expected || entry.attempt != attempt) {
            pruneSuperEntry(expected);
            return;
        }
        entry.attemptRunning = false;
        failSuperRequest(key, entry, failure);
    }

    private void failSuperRequest(SuperCacheKey key,
                                  SuperEntry entry,
                                  Throwable failure) {
        boolean demanded = !entry.waiters.isEmpty();
        List<TopologyWaiter<SuperClusterTopology>> waiters = List.copyOf(entry.waiters);
        entry.waiters.clear();
        if (demanded) finishBuildDemand();
        entry.requestPriority = null;
        cancelSuperBuild(entry);
        cancelSuperChildren(entry);
        entry.attemptRunning = false;
        waiters.forEach(waiter -> waiter.complete(null, failure));
        if (entry.topology == null) {
            pruneSuperEntry(entry);
        }
    }

    private void requestBaseBoundaryLinks(
            ResourceKey<Level> dimension,
            BaseClusterTopology source,
            BaseClusterTopology target,
            Direction face,
            NavigationScheduler.Priority priority,
            TopologyWaiter<SuperClusterTopology.BoundaryLinks> waiter) {
        requireRuntimeLock();
        ensureOpen();
        BaseBoundaryCacheKey key = new BaseBoundaryCacheKey(
                dimension,
                source,
                target,
                face
        );
        ViewEntry owner = baseView(key.dimension(), key.source());
        if (owner == null) {
            waiter.complete(null, new StaleTopologyException(
                    "base boundary source is no longer current"));
            return;
        }
        int slot = baseLinkSlot(key.source().section(), key.target().section(), key.face());
        LinkEntry<SuperClusterTopology.BoundaryLinks> current = owner.links[slot];
        if (current != null && current.targetSignature != key.target().signature()) {
            clearBaseLink(owner, slot, "base boundary target changed");
        }
        LinkEntry<SuperClusterTopology.BoundaryLinks> existing = owner.links[slot];
        if (existing != null) {
            touchBase(owner);
            if (existing.value != null) {
                waiter.complete(existing.value, null);
                return;
            }
            addLinkWaiter(existing, waiter, () -> clearBaseLinkIfOwned(
                    owner, slot, existing));
            existing.promote(priority);
            return;
        }

        LinkEntry<SuperClusterTopology.BoundaryLinks> entry = new LinkEntry<>(
                priority, key.target().signature());
        addLinkWaiter(entry, waiter, () -> clearBaseLinkIfOwned(owner, slot, entry));
        owner.links[slot] = entry;
        removeIdleBase(owner);
        entry.task = SUBMITTING_TASK;
        submitOutsideRuntimeLock(
                () -> taskExecutor.submit(priority,
                        () -> buildBaseBoundaryLinks(key, entry)),
                submitted -> {
                    if (owner.links[slot] != entry || entry.task != SUBMITTING_TASK) {
                        afterRuntimeLock(() -> submitted.cancelWhenQueued(ignored -> {
                        }));
                        return;
                    }
                    entry.task = submitted;
                    reprioritizeTask(submitted, entry.priority);
                },
                failure -> {
                    if (entry.task != SUBMITTING_TASK) return;
                    entry.task = UNTRACKED_TASK;
                    if (owner.links[slot] == entry) {
                        owner.links[slot] = null;
                        touchBase(owner);
                        pruneView(owner);
                    }
                    completeLinkWaiters(entry, null, failure);
                });
    }

    private void buildBaseBoundaryLinks(
            BaseBoundaryCacheKey key,
            LinkEntry<SuperClusterTopology.BoundaryLinks> expected) {
        try {
            boolean captured = runtimeTransition(() -> beginBaseBoundaryBuild(key, expected));
            if (!captured) return;
            SuperClusterTopology.BoundaryLinks links = SuperClusterTopology.boundaryLinks(
                    key.source(),
                    key.target(),
                    key.face()
            );
            runRuntimeTransition(() -> publishBaseBoundaryLinks(key, expected, links));
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable failure) {
            runRuntimeTransition(() -> failBaseBoundaryLinks(key, expected, failure));
        }
    }

    private boolean beginBaseBoundaryBuild(
            BaseBoundaryCacheKey key,
            LinkEntry<SuperClusterTopology.BoundaryLinks> expected) {
        ViewEntry owner = baseView(key.dimension(), key.source());
        int slot = baseLinkSlot(key.source().section(), key.target().section(), key.face());
        if (closed || owner == null || owner.links[slot] != expected
                || expected.task == UNTRACKED_TASK || expected.waiters.isEmpty()
                || !baseBoundaryKeyCurrent(key)) {
            failBaseBoundaryLinks(key, expected,
                    new StaleTopologyException("base boundary input is no longer current"));
            return false;
        }
        if (captureLinkBaseInputs(expected, key.dimension(),
                new BaseClusterTopology[]{key.source(), key.target()})) return true;
        failBaseBoundaryLinks(key, expected,
                new StaleTopologyException("base boundary input is no longer current"));
        return false;
    }

    private void publishBaseBoundaryLinks(
            BaseBoundaryCacheKey key,
            LinkEntry<SuperClusterTopology.BoundaryLinks> expected,
            SuperClusterTopology.BoundaryLinks links) {
        requireRuntimeLock();
        ViewEntry owner = baseView(key.dimension(), key.source());
        int slot = baseLinkSlot(key.source().section(), key.target().section(), key.face());
        boolean current = !closed && owner != null && owner.links[slot] == expected
                && expected.targetSignature == key.target().signature()
                && baseBoundaryKeyCurrent(key);
        expected.task = UNTRACKED_TASK;
        releaseLinkInputs(expected);
        if (!current) {
            if (owner != null && owner.links[slot] == expected) {
                owner.links[slot] = null;
                touchBase(owner);
                pruneView(owner);
            }
            completeLinkWaiters(expected, null, new StaleTopologyException(
                    "base boundary topology changed while links were building"));
            return;
        }
        expected.value = links;
        touchBase(owner);
        completeLinkWaiters(expected, links, null);
        evictBaseCache();
    }

    private void failBaseBoundaryLinks(
            BaseBoundaryCacheKey key,
            LinkEntry<SuperClusterTopology.BoundaryLinks> expected,
            Throwable failure) {
        requireRuntimeLock();
        expected.task = UNTRACKED_TASK;
        releaseLinkInputs(expected);
        ViewEntry owner = baseView(key.dimension(), key.source());
        if (owner != null) {
            int slot = baseLinkSlot(key.source().section(), key.target().section(), key.face());
            if (owner.links[slot] == expected) {
                owner.links[slot] = null;
                touchBase(owner);
                pruneView(owner);
            }
        }
        completeLinkWaiters(expected, null, failure);
    }

    private void requestSuperBoundaryLinks(
            ResourceKey<Level> dimension,
            SuperClusterTopology source,
            SuperClusterTopology target,
            Direction face,
            NavigationScheduler.Priority priority,
            TopologyWaiter<SuperClusterTopology.CrossingIndex> waiter) {
        requireRuntimeLock();
        ensureOpen();
        SuperBoundaryCacheKey key = new SuperBoundaryCacheKey(
                dimension,
                source,
                target,
                face
        );
        SuperEntry owner = superView(key.dimension(), key.source());
        if (owner == null) {
            waiter.complete(null, new StaleTopologyException(
                    "parent boundary source is no longer current"));
            return;
        }
        int slot = superLinkSlot(key.source().origin(), key.target().origin(), key.face());
        LinkEntry<SuperClusterTopology.CrossingIndex> current = owner.links[slot];
        if (current != null && current.targetSignature != key.target().signature()) {
            clearSuperLink(owner, slot, "parent boundary target changed");
        }
        LinkEntry<SuperClusterTopology.CrossingIndex> existing = owner.links[slot];
        if (existing != null) {
            touchSuper(owner);
            if (existing.value != null) {
                waiter.complete(existing.value, null);
                return;
            }
            addLinkWaiter(existing, waiter, () -> clearSuperLinkIfOwned(
                    owner, slot, existing));
            existing.promote(priority);
            return;
        }

        LinkEntry<SuperClusterTopology.CrossingIndex> entry = new LinkEntry<>(
                priority, key.target().signature());
        addLinkWaiter(entry, waiter, () -> clearSuperLinkIfOwned(owner, slot, entry));
        owner.links[slot] = entry;
        removeIdleSuper(owner);
        SuperCacheKey sourceKey = new SuperCacheKey(dimension, source.origin(),
                source.geometry(), source.movement());
        SuperCacheKey targetKey = new SuperCacheKey(dimension, target.origin(),
                target.geometry(), target.movement());
        BaseClusterTopology[] sourceChildren = currentChildTopologies(sourceKey);
        BaseClusterTopology[] targetChildren = currentChildTopologies(targetKey);
        if (sourceChildren == null || targetChildren == null) {
            beginSuperBoundaryChildren(key, entry, sourceKey, targetKey);
            return;
        }
        submitSuperBoundaryBuild(key, entry);
    }

    private void beginSuperBoundaryChildren(SuperBoundaryCacheKey key,
                                            LinkEntry<SuperClusterTopology.CrossingIndex> entry,
                                            SuperCacheKey sourceKey,
                                            SuperCacheKey targetKey) {
        List<TopologyWaiter<BaseClusterTopology>> children = new ArrayList<>(16);
        entry.collectingChildren = true;
        entry.children = children;
        try {
            for (SectionPos child : SuperClusterTopology.childSections(sourceKey.origin())) {
                TopologyWaiter<BaseClusterTopology> waiter = new TopologyWaiter<>(entry.priority,
                        ignored -> completeSuperBoundaryChildren(
                                key, entry, sourceKey, targetKey));
                children.add(waiter);
                requestClusterDependency(key.dimension(), child,
                        sourceKey.geometry(), entry.priority, false, waiter);
            }
            for (SectionPos child : SuperClusterTopology.childSections(targetKey.origin())) {
                TopologyWaiter<BaseClusterTopology> waiter = new TopologyWaiter<>(entry.priority,
                        ignored -> completeSuperBoundaryChildren(
                                key, entry, sourceKey, targetKey));
                children.add(waiter);
                requestClusterDependency(key.dimension(), child,
                        targetKey.geometry(), entry.priority, false, waiter);
            }
        } catch (RuntimeException failure) {
            children.forEach(TopologyWaiter::cancel);
            entry.collectingChildren = false;
            entry.children = List.of();
            failSuperBoundaryLinks(key, entry, failure);
            return;
        }
        entry.collectingChildren = false;
        entry.children = List.copyOf(children);
        completeSuperBoundaryChildren(key, entry, sourceKey, targetKey);
    }

    private void completeSuperBoundaryChildren(
            SuperBoundaryCacheKey key,
            LinkEntry<SuperClusterTopology.CrossingIndex> entry,
            SuperCacheKey sourceKey,
            SuperCacheKey targetKey) {
        if (entry.retired || entry.collectingChildren
                || entry.children.stream().anyMatch(waiter -> waiter.active)) return;
        Throwable failure = entry.children.stream().map(waiter -> waiter.failure)
                .filter(Objects::nonNull).findFirst().orElse(null);
        entry.children = List.of();
        if (failure != null) {
            failSuperBoundaryLinks(key, entry, failure);
            return;
        }
        BaseClusterTopology[] source = currentChildTopologies(sourceKey);
        BaseClusterTopology[] target = currentChildTopologies(targetKey);
        if (source == null || target == null) {
            failSuperBoundaryLinks(key, entry,
                    new StaleTopologyException("parent boundary children changed"));
        } else {
            submitSuperBoundaryBuild(key, entry);
        }
    }

    private void submitSuperBoundaryBuild(SuperBoundaryCacheKey key,
                                          LinkEntry<SuperClusterTopology.CrossingIndex> entry) {
        entry.task = SUBMITTING_TASK;
        submitOutsideRuntimeLock(
                () -> taskExecutor.submit(entry.priority,
                        () -> buildSuperBoundaryLinks(key, entry)),
                submitted -> {
                    if (entry.task != SUBMITTING_TASK || entry.retired) {
                        afterRuntimeLock(() -> submitted.cancelWhenQueued(ignored -> {
                        }));
                        return;
                    }
                    entry.task = submitted;
                    reprioritizeTask(submitted, entry.priority);
                },
                failure -> {
                    if (entry.task == SUBMITTING_TASK) {
                        entry.task = UNTRACKED_TASK;
                        failSuperBoundaryLinks(key, entry, failure);
                    }
                });
    }

    private void buildSuperBoundaryLinks(
            SuperBoundaryCacheKey key,
            LinkEntry<SuperClusterTopology.CrossingIndex> expected) {
        try {
            SuperBoundaryBuildInput input = runtimeTransition(
                    () -> beginSuperBoundaryBuild(key, expected));
            if (input == null) return;
            SuperClusterTopology.CrossingIndex links = key.source().crossingIndex(
                    key.face(), key.target(), input.sourceChildren(), input.targetChildren());
            runRuntimeTransition(() -> publishSuperBoundaryLinks(key, expected, links));
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable failure) {
            runRuntimeTransition(() -> failSuperBoundaryLinks(key, expected, failure));
        }
    }

    @Nullable
    private SuperBoundaryBuildInput beginSuperBoundaryBuild(
            SuperBoundaryCacheKey key,
            LinkEntry<SuperClusterTopology.CrossingIndex> expected) {
        SuperEntry owner = superView(key.dimension(), key.source());
        int slot = superLinkSlot(key.source().origin(), key.target().origin(), key.face());
        if (closed || owner == null || owner.links[slot] != expected || expected.retired
                || expected.task == UNTRACKED_TASK || expected.waiters.isEmpty()
                || !superBoundaryKeyCurrent(key)) {
            failSuperBoundaryLinks(key, expected,
                    new StaleTopologyException("parent boundary input is no longer current"));
            return null;
        }
        SuperCacheKey sourceKey = new SuperCacheKey(key.dimension(), key.source().origin(),
                key.source().geometry(), key.source().movement());
        SuperCacheKey targetKey = new SuperCacheKey(key.dimension(), key.target().origin(),
                key.target().geometry(), key.target().movement());
        BaseClusterTopology[] source = currentChildTopologies(sourceKey);
        BaseClusterTopology[] target = currentChildTopologies(targetKey);
        if (source == null || target == null
                || !key.source().matchesChildren(source)
                || !key.target().matchesChildren(target)
                || !captureLinkSuperInputs(expected, key.dimension(), key.source(), key.target())) {
            failSuperBoundaryLinks(key, expected,
                    new StaleTopologyException("parent boundary input is no longer current"));
            return null;
        }
        BaseClusterTopology[] inputs = Arrays.copyOf(source, source.length + target.length);
        System.arraycopy(target, 0, inputs, source.length, target.length);
        if (!captureLinkBaseInputs(expected, key.dimension(), inputs)) {
            releaseLinkInputs(expected);
            failSuperBoundaryLinks(key, expected,
                    new StaleTopologyException("parent boundary children changed"));
            return null;
        }
        return new SuperBoundaryBuildInput(source, target);
    }

    private void publishSuperBoundaryLinks(
            SuperBoundaryCacheKey key,
            LinkEntry<SuperClusterTopology.CrossingIndex> expected,
            SuperClusterTopology.CrossingIndex links) {
        requireRuntimeLock();
        SuperEntry owner = superView(key.dimension(), key.source());
        int slot = superLinkSlot(key.source().origin(), key.target().origin(), key.face());
        boolean current = !closed && owner != null && owner.links[slot] == expected
                && expected.targetSignature == key.target().signature()
                && superBoundaryKeyCurrent(key);
        expected.task = UNTRACKED_TASK;
        releaseLinkInputs(expected);
        if (!current) {
            if (owner != null && owner.links[slot] == expected) {
                owner.links[slot] = null;
                touchSuper(owner);
                pruneSuperEntry(owner);
            }
            completeLinkWaiters(expected, null, new StaleTopologyException(
                    "super boundary topology changed while links were building"));
            return;
        }
        expected.value = links;
        touchSuper(owner);
        completeLinkWaiters(expected, links, null);
        evictSuperCache();
    }

    private void failSuperBoundaryLinks(
            SuperBoundaryCacheKey key,
            LinkEntry<SuperClusterTopology.CrossingIndex> expected,
            Throwable failure) {
        requireRuntimeLock();
        expected.task = UNTRACKED_TASK;
        releaseLinkInputs(expected);
        SuperEntry owner = superView(key.dimension(), key.source());
        if (owner != null) {
            int slot = superLinkSlot(key.source().origin(), key.target().origin(), key.face());
            if (owner.links[slot] == expected) {
                owner.links[slot] = null;
                touchSuper(owner);
                pruneSuperEntry(owner);
            }
        }
        completeLinkWaiters(expected, null, failure);
    }

    @Nullable
    private LinkEntry<SuperClusterTopology.BoundaryLinks> baseLinkEntry(BaseBoundaryCacheKey key) {
        ViewEntry owner = baseView(key.dimension(), key.source());
        int slot = baseLinkSlot(key.source().section(), key.target().section(), key.face());
        LinkEntry<SuperClusterTopology.BoundaryLinks> entry =
                owner == null ? null : owner.links[slot];
        if (entry == null || entry.targetSignature != key.target().signature()) {
            return null;
        }
        touchBase(owner);
        return entry;
    }

    private boolean baseBoundaryKeyCurrent(BaseBoundaryCacheKey key) {
        ClusterEntry source = clusters.get(new ClusterKey(key.dimension(), key.source().section()));
        ClusterEntry target = clusters.get(new ClusterKey(key.dimension(), key.target().section()));
        BaseClusterTopology currentTarget = target == null
                ? null : target.topology(key.target().geometry());
        return source != null && source.topology(key.source().geometry()) == key.source()
                && currentTarget != null && currentTarget.signature() == key.target().signature();
    }

    @Nullable
    private ViewEntry baseView(ResourceKey<Level> dimension, BaseClusterTopology topology) {
        ClusterEntry entry = clusters.get(new ClusterKey(dimension, topology.section()));
        ViewEntry view = entry == null ? null : entry.views.get(topology.geometry());
        return entry != null && entry.current() && view != null
                && view.topologyValidity == view.validity && view.topology == topology
                ? view : null;
    }

    private static int baseLinkSlot(SectionPos source, SectionPos target, Direction face) {
        if (face.getAxis().isVertical()) return face == Direction.DOWN ? 12 : 13;
        int direction = switch (face) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> throw new IllegalArgumentException("horizontal face required");
        };
        int yShift = target.y() - source.y();
        if (yShift < -1 || yShift > 1) {
            throw new IllegalArgumentException("horizontal boundary Y shift is outside -1..1");
        }
        return direction * 3 + yShift + 1;
    }

    private <T> void addLinkWaiter(LinkEntry<T> entry,
                                   TopologyWaiter<T> waiter,
                                   Runnable clearIfUnused) {
        waiter.cancellation = () -> cancelLinkWaiter(entry, waiter, clearIfUnused);
        waiter.reprioritization = requested -> reconcileLinkWaiter(entry, waiter, requested);
        entry.waiters.add(waiter);
    }

    private <T> void cancelLinkWaiter(LinkEntry<T> entry,
                                      TopologyWaiter<T> waiter,
                                      Runnable clearIfUnused) {
        requireRuntimeLock();
        if (!waiter.deactivate()) return;
        if (!entry.waiters.remove(waiter)) return;
        if (entry.waiters.isEmpty() && entry.value == null) {
            finishBuildDemand();
            clearIfUnused.run();
            return;
        }
        reconcileLinkPriority(entry);
    }

    private <T> void reconcileLinkWaiter(LinkEntry<T> entry,
                                         TopologyWaiter<T> waiter,
                                         NavigationScheduler.Priority requested) {
        requireRuntimeLock();
        if (!waiter.active || !entry.waiters.contains(waiter)) return;
        waiter.priority = requested;
        reconcileLinkPriority(entry);
    }

    private void reconcileLinkPriority(LinkEntry<?> entry) {
        NavigationScheduler.Priority priority = entry.waiters.stream()
                .map(candidate -> candidate.priority)
                .reduce(NavigationScheduler.Priority.BACKGROUND,
                        TopologyWorkerRuntime::higherPriority);
        entry.priority = priority;
        reprioritizeTask(entry.task, priority);
        entry.children.forEach(child -> child.reprioritize(priority));
    }

    private <T> void completeLinkWaiters(LinkEntry<T> entry,
                                         @Nullable T value,
                                         @Nullable Throwable failure) {
        if (!entry.waiters.isEmpty()) finishBuildDemand();
        List<TopologyWaiter<T>> waiters = List.copyOf(entry.waiters);
        entry.waiters.clear();
        waiters.forEach(waiter -> waiter.complete(value, failure));
    }

    private void clearBaseLinkIfOwned(ViewEntry owner, int slot,
                                      LinkEntry<SuperClusterTopology.BoundaryLinks> entry) {
        if (owner.links[slot] == entry) clearBaseLink(owner, slot,
                "base boundary no longer has a consumer");
    }

    private void clearSuperLinkIfOwned(SuperEntry owner, int slot,
                                       LinkEntry<SuperClusterTopology.CrossingIndex> entry) {
        if (owner.links[slot] == entry) clearSuperLink(owner, slot,
                "parent boundary no longer has a consumer");
    }

    private void clearBaseLink(ViewEntry owner, int slot, String reason) {
        LinkEntry<SuperClusterTopology.BoundaryLinks> entry = owner.links[slot];
        if (entry == null) return;
        removeIdleBase(owner);
        owner.links[slot] = null;
        entry.retired = true;
        cancelLinkBuild(entry);
        List<TopologyWaiter<BaseClusterTopology>> children = entry.children;
        entry.children = List.of();
        children.forEach(TopologyWaiter::cancel);
        if (entry.value != null) {
            if (entry.pins == 0) releaseLinkValue(entry);
        }
        completeLinkWaiters(entry, null, new StaleTopologyException(reason));
        touchBase(owner);
    }

    private boolean superBoundaryKeyCurrent(SuperBoundaryCacheKey key) {
        SuperEntry source = superClusters.get(new SuperCacheKey(
                key.dimension(),
                key.source().origin(),
                key.source().geometry(),
                key.source().movement()
        ));
        SuperEntry target = superClusters.get(new SuperCacheKey(
                key.dimension(),
                key.target().origin(),
                key.target().geometry(),
                key.target().movement()
        ));
        return source != null && source.topology == key.source() && superEntryCurrent(source)
                && target != null && superEntryCurrent(target)
                && target.topology.signature() == key.target().signature();
    }

    @Nullable
    private SuperEntry superView(ResourceKey<Level> dimension,
                                 SuperClusterTopology topology) {
        SuperCacheKey key = new SuperCacheKey(
                dimension, topology.origin(), topology.geometry(), topology.movement()
        );
        SuperEntry entry = superClusters.get(key);
        return entry != null && entry.topology == topology && superEntryCurrent(entry)
                ? entry : null;
    }

    private static int superLinkSlot(SectionPos source, SectionPos target, Direction face) {
        if (face.getAxis().isVertical()) return face == Direction.DOWN ? 12 : 13;
        int ySections = target.y() - source.y();
        if (ySections % SuperClusterTopology.CHILDREN_PER_AXIS != 0) {
            throw new IllegalArgumentException("parent boundary is not parent-grid aligned");
        }
        SectionPos normalizedTarget = SectionPos.of(
                target.x(), source.y() + ySections / SuperClusterTopology.CHILDREN_PER_AXIS,
                target.z()
        );
        SectionPos normalizedSource = SectionPos.of(source.x(), source.y(), source.z());
        return baseLinkSlot(normalizedSource, normalizedTarget, face);
    }

    @Nullable
    private LinkEntry<SuperClusterTopology.CrossingIndex> superLinkEntry(
            SuperBoundaryCacheKey key) {
        SuperEntry owner = superView(key.dimension(), key.source());
        int slot = superLinkSlot(key.source().origin(), key.target().origin(), key.face());
        LinkEntry<SuperClusterTopology.CrossingIndex> entry =
                owner == null ? null : owner.links[slot];
        if (entry == null || entry.targetSignature != key.target().signature()) {
            return null;
        }
        touchSuper(owner);
        return entry;
    }

    private void clearSuperLink(SuperEntry owner, int slot, String reason) {
        LinkEntry<SuperClusterTopology.CrossingIndex> entry = owner.links[slot];
        if (entry == null) return;
        removeIdleSuper(owner);
        owner.links[slot] = null;
        entry.retired = true;
        cancelLinkBuild(entry);
        List<TopologyWaiter<BaseClusterTopology>> children = entry.children;
        entry.children = List.of();
        children.forEach(TopologyWaiter::cancel);
        if (entry.value != null) {
            if (entry.pins == 0) releaseLinkValue(entry);
        }
        completeLinkWaiters(entry, null, new StaleTopologyException(reason));
        touchSuper(owner);
    }

    private void prepareDemand(ClusterEntry entry, TopologyDemand demand) {
        ViewEntry view = entry.views.get(demand.geometry);
        if (view == null || view.demand != demand
                || demand.waiters.isEmpty() && !demand.prewarmSlot
                || demand.buildTask != UNTRACKED_TASK) return;
        if (demandFactsReady(entry, demand)) submitDemandBuild(demand);
    }

    private boolean demandFactsReady(ClusterEntry entry, TopologyDemand demand) {
        clearFactWaits(demand);
        if (!factsReady(demand.key, entry, demand, true)) return false;
        int horizontal = demand.geometry.widthCells() == 1 ? 0 : 1;
        int lower = demand.geometry.channel() == BaseClusterTopology.Channel.GROUND ? -1 : 0;
        int upper = demand.geometry.heightCells() == 1 ? 0 : 1;
        for (int dx = -horizontal; dx <= horizontal; dx++) {
            for (int dy = lower; dy <= upper; dy++) {
                for (int dz = -horizontal; dz <= horizontal; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    SectionPos section = SectionPos.of(demand.key.section().x() + dx,
                            demand.key.section().y() + dy, demand.key.section().z() + dz);
                    ClusterKey neighborKey = new ClusterKey(demand.key.dimension(), section);
                    ClusterEntry neighbor = clusters.get(neighborKey);
                    if (neighbor == null || neighbor.factState == FactState.UNLOADED) continue;
                    factsReady(neighborKey, neighbor, demand, false);
                }
            }
        }
        return demand.waitingFacts.isEmpty();
    }

    private boolean factsReady(ClusterKey key,
                               ClusterEntry entry,
                               TopologyDemand demand,
                               boolean required) {
        if (eventUnavailable(entry.latest.state())) {
            if (required) {
                failDemand(entry, demand,
                        entry.latest.state() == FactState.RECOVERY_FAILED
                                ? new FactsRecoveryException(key)
                                : new StaleTopologyException(key));
            }
            return false;
        }
        if (!entry.current()) {
            waitForFacts(key, entry, demand);
            return false;
        }
        if (entry.factState == FactState.AVAILABLE && entry.facts != null) {
            return true;
        }
        if (entry.factState == FactState.PENDING) {
            waitForFacts(key, entry, demand);
            return false;
        }
        if (required) failDemand(entry, demand, new StaleTopologyException(key));
        return false;
    }

    private void holdFacts(ClusterEntry entry, TopologyDemand demand) {
        BaseClusterTopology.PackedFacts facts = entry.facts;
        if (!entry.current() || entry.factState != FactState.AVAILABLE || facts == null) return;
        removeIdleFact(entry);
        if (demand.heldFacts.put(entry, facts) == null) {
            entry.pinnedFacts.merge(facts, 1, Integer::sum);
            addActiveReference();
        }
    }

    private void waitForFacts(ClusterKey key, ClusterEntry entry, TopologyDemand demand) {
        if (!demand.waitingFacts.add(entry)) return;
        entry.factWaiters.add(demand);
        if (!demand.prewarmSlot && entry.current()
                && entry.factState == FactState.PENDING) {
            long loadIdentity = entry.latest.loadIdentity();
            demand.demandedFacts.put(entry, loadIdentity);
            // The listener only records a server-thread delta; keep +/- order under
            // runtimeLock instead of deferring it to an unrelated worker turn.
            factDemandListener.changed(key, loadIdentity, 1);
        }
    }

    private void clearFactWaits(TopologyDemand demand) {
        for (ClusterEntry entry : List.copyOf(demand.waitingFacts)) {
            if (!entry.factWaiters.remove(demand)) continue;
            Long loadIdentity = demand.demandedFacts.remove(entry);
            if (loadIdentity != null) {
                factDemandListener.changed(entry.key, loadIdentity, -1);
            }
        }
        demand.waitingFacts.clear();
        demand.demandedFacts.clear();
    }

    private void submitDemandBuild(TopologyDemand demand) {
        demand.buildTask = SUBMITTING_TASK;
        submitOutsideRuntimeLock(
                () -> demand.prewarmSlot
                        ? taskExecutor.submitPrewarm(() -> build(demand))
                        : taskExecutor.submit(demand.priority,
                        () -> build(demand)),
                submitted -> {
                    if (demand.buildTask != SUBMITTING_TASK) {
                        afterRuntimeLock(() -> submitted.cancelWhenQueued(ignored -> {
                        }));
                        return;
                    }
                    demand.buildTask = submitted;
                    afterRuntimeLock(() -> {
                        if (!demand.prewarmSlot) submitted.promoteBuild();
                        submitted.reprioritize(demand.priority);
                    });
                },
                failure -> {
            if (demand.buildTask != SUBMITTING_TASK) return;
            demand.buildTask = UNTRACKED_TASK;
            ClusterEntry entry = clusters.get(demand.key);
            ViewEntry view = entry == null ? null : entry.views.get(demand.geometry);
            if (view != null && view.demand == demand) {
                failDemand(entry, demand, failure);
            }
        });
    }

    private void releaseUnusedFacts(ClusterEntry entry) {
        if (entry.facts == null || entry.pinnedFacts.containsKey(entry.facts)) return;
        removeIdleFact(entry);
        if (idleBaseEntries.add(entry)) {
            baseRetainedBytes += entry.facts.retainedBytes();
            recordBaseCachePeak();
        }
    }

    private void touchBase(ViewEntry view) {
        removeIdleBase(view);
        if (baseIdle(view) && idleBaseEntries.add(view)) {
            baseRetainedBytes += baseIdleBytes(view);
            recordBaseCachePeak();
        }
    }

    private boolean baseIdle(ViewEntry view) {
        return view.topology != null && view.demand == null
                && view.pinnedTopologies.isEmpty()
                && topologyTick > view.handoffUntilTick && !hasBuildingLink(view.links);
    }

    private void touchSuper(SuperEntry entry) {
        removeIdleSuper(entry);
        if (superIdle(entry) && idleSuperEntries.add(entry)) {
            superRetainedBytes += superIdleBytes(entry);
            recordSuperCachePeak();
        }
    }

    private void removeIdleFact(ClusterEntry entry) {
        if (!idleBaseEntries.remove(entry)) return;
        baseRetainedBytes -= Objects.requireNonNull(entry.facts, "idle facts").retainedBytes();
    }

    private void removeIdleBase(ViewEntry view) {
        if (!idleBaseEntries.remove(view)) return;
        baseRetainedBytes -= baseIdleBytes(view);
    }

    private void removeIdleSuper(SuperEntry entry) {
        if (!idleSuperEntries.remove(entry)) return;
        superRetainedBytes -= superIdleBytes(entry);
    }

    private static long baseIdleBytes(ViewEntry view) {
        long bytes = Objects.requireNonNull(view.topology, "idle base topology").retainedBytes();
        bytes += retainedStampBytes(view.topologyStamps);
        for (LinkEntry<SuperClusterTopology.BoundaryLinks> link : view.links) {
            if (link != null && link.value != null) bytes += link.value.retainedBytes();
        }
        return bytes;
    }

    private static long superIdleBytes(SuperEntry entry) {
        long bytes = Objects.requireNonNull(entry.topology, "idle parent topology").retainedBytes();
        bytes += retainedStampBytes(entry.topologyStamps);
        for (LinkEntry<SuperClusterTopology.CrossingIndex> link : entry.links) {
            if (link != null && link.value != null) bytes += link.value.retainedBytes();
        }
        return bytes;
    }

    private static long retainedStampBytes(List<SectionStamp> stamps) {
        return stamps.isEmpty() ? 0L : 24L + stamps.size() * 64L;
    }

    private boolean superIdle(SuperEntry entry) {
        return entry.topology != null && entry.waiters.isEmpty() && !entry.attemptRunning
                && entry.pinnedTopologies.isEmpty()
                && topologyTick > entry.handoffUntilTick
                && !hasBuildingLink(entry.links);
    }

    private static boolean hasBuildingLink(LinkEntry<?>[] links) {
        for (LinkEntry<?> link : links) {
            if (link != null && link.value == null) return true;
        }
        return false;
    }

    private void releaseHandoffs() {
        for (Iterator<ViewEntry> iterator = baseHandoffs.iterator(); iterator.hasNext();) {
            ViewEntry view = iterator.next();
            if (topologyTick <= view.handoffUntilTick) continue;
            iterator.remove();
            touchBase(view);
            pruneView(view);
        }
        for (Iterator<SuperEntry> iterator = superHandoffs.iterator(); iterator.hasNext();) {
            SuperEntry entry = iterator.next();
            if (topologyTick <= entry.handoffUntilTick) continue;
            iterator.remove();
            touchSuper(entry);
            pruneSuperEntry(entry);
        }
    }

    private void pruneView(ViewEntry view) {
        if (!viewRemovable(view)) return;
        // The owner carries no historical signature; removal is safe only after every
        // active graph, demand, boundary and one-tick handoff has ended.
        removeIdleBase(view);
        view.owner.views.remove(view.geometry, view);
    }

    private boolean viewRemovable(ViewEntry view) {
        if (view.topology != null || view.demand != null
                || !view.pinnedTopologies.isEmpty() || baseHandoffs.contains(view)) return false;
        for (LinkEntry<SuperClusterTopology.BoundaryLinks> link : view.links) {
            if (link != null) return false;
        }
        return true;
    }

    private void pruneViews(ClusterEntry entry) {
        Iterator<Map.Entry<BaseClusterTopology.GeometryKey, ViewEntry>> iterator =
                entry.views.entrySet().iterator();
        while (iterator.hasNext()) {
            ViewEntry view = iterator.next().getValue();
            if (!viewRemovable(view)) continue;
            removeIdleBase(view);
            iterator.remove();
        }
    }

    private void pruneCluster(ClusterEntry entry) {
        if (entry.factState == FactState.UNLOADED && entry.facts == null
                && entry.pinnedFacts.isEmpty() && entry.factWaiters.isEmpty()
                && entry.views.isEmpty()) {
            removeIdleFact(entry);
            clusters.remove(entry.key, entry);
        }
    }


    private BaseClusterTopology.BuildInput buildInput(ClusterKey key,
                                                      BaseClusterTopology.PackedFacts center) {
        byte[] offsets = new byte[26];
        BaseClusterTopology.PackedFacts[] facts = new BaseClusterTopology.PackedFacts[26];
        long[] revisions = new long[26];
        long[] fingerprints = new long[26];
        int count = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    SectionPos section = SectionPos.of(
                            key.section().x() + dx,
                            key.section().y() + dy,
                            key.section().z() + dz
                    );
                    ClusterEntry neighbor = clusters.get(new ClusterKey(key.dimension(), section));
                    if (neighbor == null || !neighbor.current()
                            || neighbor.factState != FactState.AVAILABLE
                            || neighbor.facts == null) continue;
                    offsets[count] = (byte) BaseClusterTopology.haloIndex(dx, dy, dz);
                    facts[count] = neighbor.facts;
                    revisions[count] = neighbor.revision;
                    fingerprints[count] = neighbor.facts.fingerprint();
                    count++;
                }
            }
        }
        return new BaseClusterTopology.BuildInput(
                center,
                Arrays.copyOf(offsets, count),
                Arrays.copyOf(facts, count),
                Arrays.copyOf(revisions, count),
                Arrays.copyOf(fingerprints, count)
        );
    }

    private void invalidateHaloDependents(ClusterKey source) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    ClusterKey key = new ClusterKey(source.dimension(), SectionPos.of(
                            source.section().x() + dx,
                            source.section().y() + dy,
                            source.section().z() + dz
                    ));
                    ClusterEntry entry = clusters.get(key);
                    if (entry == null) continue;
                    for (ViewEntry view : entry.views.values()) {
                        boolean affected = view.topology != null
                                && view.topologyValidity != view.validity;
                        if (!affected) continue;
                        BaseClusterTopology stale = view.topology;
                        invalidateBaseBoundaryLinks(key, stale);
                        retireBaseTopology(view);
                        view.topology = null;
                    }
                    invalidateHaloSuperParents(key, -dx, -dy, -dz);
                    pruneViews(entry);
                }
            }
        }
    }

    private static boolean requiresHalo(BaseClusterTopology.GeometryKey geometry,
                                        int dx,
                                        int dy,
                                        int dz) {
        int horizontal = geometry.widthCells() == 1 ? 0 : 1;
        int lower = geometry.channel() == BaseClusterTopology.Channel.GROUND ? -1 : 0;
        int upper = geometry.heightCells() == 1 ? 0 : 1;
        return (dx | dy | dz) != 0 && Math.abs(dx) <= horizontal
                && Math.abs(dz) <= horizontal && dy >= lower && dy <= upper;
    }

    private static boolean referencesHalo(BaseClusterTopology topology,
                                          int dx,
                                          int dy,
                                          int dz) {
        int encoded = BaseClusterTopology.haloIndex(dx, dy, dz);
        for (int index = 0; index < topology.haloStampCount(); index++) {
            if (Byte.toUnsignedInt(topology.haloOffset(index)) == encoded) return true;
        }
        return false;
    }

    private boolean haloCurrent(ClusterKey key, BaseClusterTopology topology) {
        for (int index = 0; index < topology.haloStampCount(); index++) {
            int encoded = Byte.toUnsignedInt(topology.haloOffset(index));
            SectionPos section = SectionPos.of(
                    key.section().x() + BaseClusterTopology.haloX(encoded),
                    key.section().y() + BaseClusterTopology.haloY(encoded),
                    key.section().z() + BaseClusterTopology.haloZ(encoded)
            );
            ClusterEntry neighbor = clusters.get(new ClusterKey(key.dimension(), section));
            if (neighbor == null || !neighbor.current()
                    || neighbor.revision != topology.haloRevision(index)
                    || neighbor.factState != FactState.AVAILABLE || neighbor.facts == null
                    || neighbor.facts.fingerprint() != topology.haloFingerprint(index)) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private BaseClusterTopology topology(ClusterKey key,
                                         BaseClusterTopology.GeometryKey geometry) {
        requireRuntimeLock();
        ClusterEntry entry = clusters.get(Objects.requireNonNull(key, "key"));
        BaseClusterTopology topology = entry == null ? null : entry.topology(geometry);
        ViewEntry view = topology == null ? null : entry.views.get(geometry);
        if (view == null) return null;
        touchBase(view);
        return topology;
    }

    private boolean isCurrent(List<SectionStamp> stamps) {
        for (SectionStamp stamp : stamps) {
            if (!stamp.current()) return false;
        }
        return true;
    }

    @Nullable
    private List<SectionStamp> captureBaseStamps(ClusterKey key,
                                                  BaseClusterTopology topology) {
        Map<ClusterKey, SectionStamp> stamps = new HashMap<>();
        SectionStamp center = currentFactStamp(
                key, topology.revision(), topology.sourceFingerprint());
        if (center == null) return null;
        stamps.put(key, center);
        for (int index = 0; index < topology.haloStampCount(); index++) {
            int encoded = Byte.toUnsignedInt(topology.haloOffset(index));
            ClusterKey halo = new ClusterKey(key.dimension(), SectionPos.of(
                    key.section().x() + BaseClusterTopology.haloX(encoded),
                    key.section().y() + BaseClusterTopology.haloY(encoded),
                    key.section().z() + BaseClusterTopology.haloZ(encoded)
            ));
            SectionStamp stamp = currentFactStamp(
                    halo, topology.haloRevision(index), topology.haloFingerprint(index));
            if (stamp == null) return null;
            stamps.put(halo, stamp);
        }
        return stamps.values().stream()
                .sorted(Comparator.comparingLong(stamp -> stamp.key().section().asLong()))
                .toList();
    }

    @Nullable
    private SectionStamp currentFactStamp(ClusterKey key,
                                          long revision,
                                          long fingerprint) {
        ClusterEntry entry = clusters.get(key);
        if (entry == null || !entry.current()
                || entry.latest.state() != FactState.AVAILABLE
                || entry.revision != revision
                || entry.facts != null && entry.facts.fingerprint() != fingerprint
                || entry.facts == null && entry.factFingerprintKnown
                && entry.factFingerprint != fingerprint) {
            return null;
        }
        return new SectionStamp(key, entry.latest, entry.latest.get());
    }

    @Nullable
    private List<SectionStamp> mergeBaseStamps(ResourceKey<Level> dimension,
                                                BaseClusterTopology[] topologies) {
        Map<ClusterKey, SectionStamp> merged = new HashMap<>();
        for (BaseClusterTopology topology : topologies) {
            ViewEntry owner = baseView(dimension, topology);
            if (owner == null || owner.topologyStamps.isEmpty()
                    || !isCurrent(owner.topologyStamps)) return null;
            for (SectionStamp stamp : owner.topologyStamps) merged.put(stamp.key(), stamp);
        }
        return merged.values().stream()
                .sorted(Comparator.comparingLong(stamp -> stamp.key().section().asLong()))
                .toList();
    }

    MacroRequest requestMacroQuery(ResourceKey<Level> dimension,
                                   BlockPos start,
                                   BlockPos goal,
                                   BaseClusterTopology.Channel channel,
                                   BaseClusterTopology.TraversalProfile profile,
                                   NavigationScheduler.Priority priority) {
        Objects.requireNonNull(dimension, "dimension");
        MacroRequest request = new MacroRequest(
                dimension,
                Objects.requireNonNull(start, "start").immutable(),
                Objects.requireNonNull(goal, "goal").immutable(),
                Objects.requireNonNull(channel, "channel"),
                Objects.requireNonNull(profile, "profile"),
                Objects.requireNonNull(priority, "priority"),
                MacroSearch.DEFAULT_WEIGHT
        );
        publishRequestEvent(request, RequestEvent.SUBMIT);
        return request;
    }

    private void publishRequestEvent(MacroRequest request, RequestEvent event) {
        boolean schedule;
        boolean reject;
        boolean cancelWithoutWorker = false;
        CompletableFuture<WorkerResult> displaced = null;
        CompletableFuture<WorkerResult> duplicate = null;
        synchronized (runtimeLock) {
            reject = event.kind() != RequestEventKind.CANCEL && (closed || stopRequested);
            if (event.kind() == RequestEventKind.CANCEL) {
                request.cancelPublished = true;
                RequestEvent pending = pendingRequestEvents.remove(request);
                displaced = pending == null ? null : pending.response();
                boolean processing = processingRequestEvents.containsKey(request);
                if (macroRequests.contains(request)) {
                    pendingRequestEvents.put(request, RequestEvent.CANCEL);
                    prewarmAllowed.set(false);
                } else if (!processing) {
                    cancelWithoutWorker = true;
                }
            } else if (!reject && request.cancelPublished) {
                reject = true;
            } else if (!reject) {
                RequestEvent previous = pendingRequestEvents.get(request);
                if (previous != null && previous.kind() == RequestEventKind.FINAL_STALE
                        && event.kind() == RequestEventKind.FINAL_STALE
                        && previous.attempt() == event.attempt()) {
                    duplicate = previous.response();
                } else {
                    reject = previous != null && previous.kind() == RequestEventKind.CANCEL;
                }
                if (!reject && duplicate == null) {
                    displaced = previous == null ? null : previous.response();
                    pendingRequestEvents.put(request, event);
                    prewarmAllowed.set(false);
                }
            }
            if (pendingRequestEvents.containsKey(request)) recordPendingEvent();
            schedule = !reject && !cancelWithoutWorker && duplicate == null
                    && !eventTaskOutstanding
                    && pendingRequestEvents.containsKey(request);
            if (schedule) eventTaskOutstanding = true;
        }
        if (duplicate != null && event.response() != null) {
            duplicate.whenComplete((value, failure) -> {
                if (failure == null) event.response().complete(value);
                else event.response().completeExceptionally(failure);
            });
            return;
        }
        if (displaced != null) displaced.completeExceptionally(
                new CancellationException("macro request event was superseded"));
        if (cancelWithoutWorker) {
            request.cancelBeforeAccept();
            return;
        }
        if (reject) {
            RejectedExecutionException failure = new RejectedExecutionException(
                    "macro request event was rejected");
            if (event.response() != null) event.response().completeExceptionally(failure);
            else request.completeRejected(failure);
            return;
        }
        if (schedule) scheduleEventTask();
    }

    private void applyRequestEvent(MacroRequest request, RequestEvent event) {
        if (request.cancelPublished || event.kind() == RequestEventKind.CANCEL) {
            if (event.response() != null) finishFuture(event.response(), null,
                    new CancellationException("macro request was cancelled"));
            request.cancelInternal();
        } else if (event.kind() == RequestEventKind.FINAL_STALE) {
            request.retryAfterFinalValidation(event.attempt(), event.response());
        } else {
            acceptMacroRequest(request);
        }
    }

    private void acceptMacroRequest(MacroRequest request) {
        requireRuntimeLock();
        ensureOpen();
        macroRequests.add(request);
        highestLogicalRequests = Math.max(highestLogicalRequests, macroRequests.size());
        try {
            joinResolve(request);
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable failure) {
            request.finishExceptionally(failure);
        }
    }

    private static int queryNodeBudget(BlockPos start,
                                       BlockPos goal,
                                       boolean hierarchical) {
        double directDistance = Math.sqrt(start.distSqr(goal));
        return Math.max(
                hierarchical
                        ? MIN_HIERARCHICAL_QUERY_VISITED_NODES
                        : MIN_QUERY_VISITED_NODES,
                Math.min(
                        MAX_QUERY_VISITED_NODES,
                        (int) Math.ceil(directDistance * QUERY_VISITED_NODES_PER_BLOCK)
                )
        );
    }


    void beginStopping() {
        boolean schedule;
        synchronized (runtimeLock) {
            if (closed || stopRequested) return;
            stopRequested = true;
            stopEventPending = true;
            recordPendingEvent();
            schedule = !eventTaskOutstanding;
            if (schedule) eventTaskOutstanding = true;
        }
        if (schedule) scheduleEventTask();
    }

    boolean awaitStopped(long timeout, TimeUnit unit) {
        beginStopping();
        return taskExecutor.awaitTermination(timeout, unit);
    }

    private void closeState() {
        requireRuntimeLock();
        if (closed) {
            return;
        }
        closed = true;
        stopEventPending = false;
        IllegalStateException stopped = new IllegalStateException("topology service stopped");
        for (MacroRequest request : List.copyOf(macroRequests)) {
            request.cancelInternal();
        }
        macroRequests.clear();
        macroFlights.clear();
        completedCorridors.clear();
        completedCorridorBytes = 0L;
        for (ClusterEntry entry : List.copyOf(clusters.values())) {
            for (ViewEntry view : List.copyOf(entry.views.values())) {
                for (int slot = 0; slot < view.links.length; slot++) {
                    clearBaseLink(view, slot, "topology service stopped");
                }
                if (view.demand != null) failDemand(entry, view.demand, stopped);
                view.topology = null;
                view.topologyStamps = List.of();
            }
            entry.facts = null;
        }
        for (SuperEntry entry : superClusters.values()) {
            for (int slot = 0; slot < entry.links.length; slot++) {
                clearSuperLink(entry, slot, "topology service stopped");
            }
            cancelSuperBuild(entry);
            cancelSuperChildren(entry);
            boolean demanded = !entry.waiters.isEmpty();
            List<TopologyWaiter<SuperClusterTopology>> waiters = List.copyOf(entry.waiters);
            entry.waiters.clear();
            if (demanded) finishBuildDemand();
            waiters.forEach(waiter -> waiter.complete(null, stopped));
            entry.requestPriority = null;
            entry.topology = null;
            entry.topologyStamps = List.of();
            entry.buildStamps = List.of();
        }
        resolveFlights.clear();
        deferredSearchResumes.clear();
        eventBatchActive = false;
        clusters.clear();
        superClusters.clear();
        superKeysByOrigin.clear();
        idleBaseEntries.clear();
        baseHandoffs.clear();
        idleSuperEntries.clear();
        superHandoffs.clear();
        prewarmCandidates.clear();
        if (pendingExecutorSubmissions == 0) {
            afterRuntimeLock(taskExecutor::shutdown);
        }
        prewarmAdmitted = 0;
        baseRetainedBytes = 0L;
        superRetainedBytes = 0L;
    }

    private void build(TopologyDemand demand) {
        try {
            BaseClusterTopology.BuildInput input = runtimeTransition(() -> beginBuild(demand));
            if (input == null) return;
            BaseClusterTopology topology = BaseClusterTopology.build(
                    demand.key.section(),
                    demand.generation,
                    input,
                    demand.geometry,
                    buildScratch
            );
            runRuntimeTransition(() -> publish(demand, topology));
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable exception) {
            runRuntimeTransition(() -> failBuild(demand, exception));
        }
    }

    @Nullable
    private BaseClusterTopology.BuildInput beginBuild(TopologyDemand demand) {
        ClusterEntry entry = clusters.get(demand.key);
        ViewEntry view = entry == null ? null : entry.views.get(demand.geometry);
        if (closed || entry == null || view == null || view.demand != demand
                || demand.buildTask == UNTRACKED_TASK
                || demand.waiters.isEmpty() && !demand.prewarmSlot) return null;
        if (!demandFactsReady(entry, demand)) {
            demand.buildTask = UNTRACKED_TASK;
            return null;
        }
        holdFacts(entry, demand);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx | dy | dz) == 0) continue;
                    ClusterEntry neighbor = clusters.get(new ClusterKey(demand.key.dimension(),
                            SectionPos.of(demand.key.section().x() + dx,
                                    demand.key.section().y() + dy,
                                    demand.key.section().z() + dz)));
                    if (neighbor != null) holdFacts(neighbor, demand);
                }
            }
        }
        demand.fingerprint = entry.facts.fingerprint();
        return buildInput(demand.key, entry.facts);
    }

    private void publish(TopologyDemand demand, BaseClusterTopology topology) {
        requireRuntimeLock();
        ClusterKey key = demand.key;
        ClusterEntry entry = clusters.get(key);
        ViewEntry view = entry == null ? null : entry.views.get(demand.geometry);
        demand.buildTask = UNTRACKED_TASK;
        releaseHeldFacts(demand);
        if (closed || entry == null || !entry.current()
                || entry.revision != demand.generation
                || view == null || view.demand != demand) {
            return;
        }
        if (topology.sourceFingerprint() != demand.fingerprint || !haloCurrent(key, topology)) {
            prepareDemand(entry, demand);
            return;
        }
        List<SectionStamp> topologyStamps = captureBaseStamps(key, topology);
        if (topologyStamps == null) {
            prepareDemand(entry, demand);
            return;
        }

        BaseClusterTopology replaced = view.topology;
        if (replaced != null) {
            invalidateBaseBoundaryLinks(key, replaced);
            retireBaseTopology(view);
            view.topology = null;
        }
        view.topology = topology;
        view.topologyStamps = topologyStamps;
        view.topologyValidity = view.validity;
        view.handoffUntilTick = topologyTick + 1L;
        baseHandoffs.add(view);
        if (replaced != null) {
            invalidateSuperParents(key, false,
                    parent -> parent.geometry().equals(demand.geometry));
        }
        completeDemand(entry, demand, topology);
        evictBaseCache();
    }

    private void failBuild(TopologyDemand demand, Throwable exception) {
        requireRuntimeLock();
        demand.buildTask = UNTRACKED_TASK;
        releaseHeldFacts(demand);
        ClusterEntry entry = clusters.get(demand.key);
        ViewEntry view = entry == null ? null : entry.views.get(demand.geometry);
        if (view != null && view.demand == demand) {
            failDemand(entry, demand, exception);
        }
    }

    private void requireRuntimeLock() {
        if (!Thread.holdsLock(runtimeLock)) {
            throw new IllegalStateException("topology state must be accessed while holding the runtime lock");
        }
    }

    private void completeDemand(ClusterEntry entry,
                                TopologyDemand demand,
                                BaseClusterTopology topology) {
        ViewEntry view = entry.views.get(demand.geometry);
        if (view != null && view.demand == demand) view.demand = null;
        releasePrewarmSlot(demand);
        finishBuildDemand();
        List<TopologyWaiter<BaseClusterTopology>> waiters = List.copyOf(demand.waiters);
        demand.waiters.clear();
        waiters.forEach(waiter -> waiter.complete(topology, null));
        pruneView(view);
    }

    private void failDemand(ClusterEntry entry, TopologyDemand demand, Throwable failure) {
        ViewEntry view = entry.views.get(demand.geometry);
        if (view != null && view.demand == demand) view.demand = null;
        stopDemandWork(demand);
        List<TopologyWaiter<BaseClusterTopology>> waiters = List.copyOf(demand.waiters);
        demand.waiters.clear();
        waiters.forEach(waiter -> waiter.complete(null, failure));
        if (view != null) pruneView(view);
        pruneCluster(entry);
    }

    private void cancelClusterWaiter(TopologyWaiter<BaseClusterTopology> waiter) {
        requireRuntimeLock();
        if (!waiter.deactivate()) return;
        TopologyDemand demand = waiter.demand;
        waiter.demand = null;
        if (demand == null || !demand.waiters.remove(waiter)) {
            return;
        }
        if (demand.waiters.isEmpty() && !demand.prewarmSlot) {
            ClusterEntry entry = clusters.get(demand.key);
            ViewEntry view = entry == null ? null : entry.views.get(demand.geometry);
            if (view != null && view.demand == demand) {
                view.demand = null;
            }
            stopDemandWork(demand);
            if (view != null) pruneView(view);
            if (entry != null) pruneCluster(entry);
            return;
        }
        demand.priority = demand.waiters.stream()
                .map(candidate -> candidate.priority)
                .reduce(NavigationScheduler.Priority.BACKGROUND, TopologyWorkerRuntime::higherPriority);
        reprioritizeTask(demand.buildTask, demand.priority);
    }

    private void reconcileClusterWaiter(
            TopologyWaiter<BaseClusterTopology> waiter,
            NavigationScheduler.Priority requested) {
        if (!waiter.active) return;
        TopologyDemand demand = waiter.demand;
        waiter.priority = requested;
        if (demand == null) {
            return;
        }
        demand.priority = demand.waiters.stream()
                .map(candidate -> candidate.priority)
                .reduce(NavigationScheduler.Priority.BACKGROUND, TopologyWorkerRuntime::higherPriority);
        reprioritizeTask(demand.buildTask, demand.priority);
    }

    private void cancelSuperWaiter(
            SuperCacheKey key,
            SuperEntry entry,
            TopologyWaiter<SuperClusterTopology> waiter) {
        requireRuntimeLock();
        if (!waiter.active || !entry.waiters.remove(waiter)) {
            return;
        }
        waiter.deactivate();
        if (entry.waiters.isEmpty()) {
            finishBuildDemand();
            entry.attempt++;
            entry.attemptRunning = false;
            cancelSuperBuild(entry);
            cancelSuperChildren(entry);
            entry.requestPriority = null;
            if (entry.topology == null) {
                pruneSuperEntry(entry);
            }
            return;
        }
        entry.requestPriority = entry.waiters.stream()
                .map(candidate -> candidate.priority)
                .reduce(NavigationScheduler.Priority.BACKGROUND, TopologyWorkerRuntime::higherPriority);
        if (entry.buildTask != null) {
            reprioritizeTask(entry.buildTask, entry.requestPriority);
        }
        reconcileSuperChildren(entry);
    }

    private void reconcileSuperWaiter(
            SuperEntry entry,
            TopologyWaiter<SuperClusterTopology> waiter,
            NavigationScheduler.Priority requested) {
        requireRuntimeLock();
        if (!waiter.active || !entry.waiters.contains(waiter)) {
            return;
        }
        waiter.priority = requested;
        NavigationScheduler.Priority previous = entry.requestPriority;
        entry.requestPriority = entry.waiters.stream()
                .map(candidate -> candidate.priority)
                .reduce(NavigationScheduler.Priority.BACKGROUND,
                        TopologyWorkerRuntime::higherPriority);
        if (entry.buildTask != null && previous != entry.requestPriority) {
            reprioritizeTask(entry.buildTask, entry.requestPriority);
        }
        if (previous != entry.requestPriority) {
            reconcileSuperChildren(entry);
        }
    }

    private void cancelSuperChildren(SuperEntry entry) {
        entry.children.forEach(TopologyWaiter::cancel);
        entry.children = List.of();
    }

    private void reconcileSuperChildren(SuperEntry entry) {
        entry.children.forEach(child -> reconcileClusterWaiter(
                child,
                entry.requestPriority
        ));
    }

    private void releasePrewarmSlot(TopologyDemand demand) {
        if (!demand.prewarmSlot) return;
        demand.prewarmSlot = false;
        prewarmAdmitted--;
        if (prewarmAdmitted < 0) {
            throw new IllegalStateException("prewarm admission count became negative");
        }
    }

    private void stopDemandWork(TopologyDemand demand) {
        finishBuildDemand();
        clearFactWaits(demand);
        TopologyTaskExecutor.TaskHandle task = demand.buildTask;
        demand.buildTask = UNTRACKED_TASK;
        if (task == UNTRACKED_TASK) releaseHeldFacts(demand);
        else cancelQueuedTask(task, cancelled -> {
            if (cancelled) releaseHeldFacts(demand);
        });
        releasePrewarmSlot(demand);
    }

    private void releaseHeldFacts(TopologyDemand demand) {
        for (Map.Entry<ClusterEntry, BaseClusterTopology.PackedFacts> pin
                : demand.heldFacts.entrySet()) {
            ClusterEntry entry = pin.getKey();
            BaseClusterTopology.PackedFacts facts = pin.getValue();
            Integer count = entry.pinnedFacts.remove(facts);
            if (count == null) throw new IllegalStateException("fact pin is not owned");
            if (count > 1) entry.pinnedFacts.put(facts, count - 1);
            removeActiveReference();
            releaseUnusedFacts(entry);
            pruneCluster(entry);
        }
        demand.heldFacts.clear();
        evictBaseCache();
    }

    private static NavigationScheduler.Priority higherPriority(
            @Nullable NavigationScheduler.Priority current,
            NavigationScheduler.Priority requested) {
        return current == null || requested.higherThan(current) ? requested : current;
    }

    private SuperEntry superEntry(SuperCacheKey key) {
        SuperEntry entry = superClusters.get(key);
        if (entry != null) return entry;
        entry = new SuperEntry(key);
        superClusters.put(key, entry);
        superKeysByOrigin.computeIfAbsent(
                new SuperOriginKey(key.dimension(), key.origin()), ignored -> new HashSet<>())
                .add(key);
        return entry;
    }

    private boolean removeSuperEntry(SuperCacheKey key, SuperEntry entry) {
        if (entry.topology != null || entry.attemptRunning || !entry.waiters.isEmpty()
                || !entry.pinnedTopologies.isEmpty() || entry.buildInputs != null
                || superHandoffs.contains(entry)) {
            return false;
        }
        for (LinkEntry<SuperClusterTopology.CrossingIndex> link : entry.links) {
            if (link != null) return false;
        }
        if (!superClusters.remove(key, entry)) return false;
        removeIdleSuper(entry);
        superHandoffs.remove(entry);
        SuperOriginKey origin = new SuperOriginKey(key.dimension(), key.origin());
        Set<SuperCacheKey> keys = superKeysByOrigin.get(origin);
        if (keys != null && keys.remove(key) && keys.isEmpty()) superKeysByOrigin.remove(origin);
        return true;
    }

    private void pruneSuperEntry(SuperEntry entry) {
        if (entry.topology != null || entry.attemptRunning || !entry.waiters.isEmpty()
                || !entry.pinnedTopologies.isEmpty() || entry.buildInputs != null
                || superHandoffs.contains(entry)) return;
        for (LinkEntry<SuperClusterTopology.CrossingIndex> link : entry.links) {
            if (link != null) return;
        }
        removeSuperEntry(entry.key, entry);
    }

    private static boolean retryableAttemptFailure(@Nullable Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof StaleTopologyException
                    || current instanceof CancellationException
                    || current instanceof RejectedExecutionException) return true;
        }
        return false;
    }

    void enqueuePrewarm(ResourceKey<Level> dimension,
                        ChunkPos chunk,
                        long loadIdentity,
                        List<Integer> nonAirSections) {
        PrewarmKey key = new PrewarmKey(dimension, chunk.toLong());
        synchronized (runtimeLock) {
            if (!closed && !stopRequested) {
                // Chunk loading is a correctness transition. Close the admission
                // window before its section events are published below.
                prewarmAllowed.set(false);
                PrewarmCandidate previous = prewarmCandidates.get(key);
                if (previous != null && previous.loadIdentity > loadIdentity) return;
                if (previous != null && previous.loadIdentity != loadIdentity) {
                    prewarmCandidates.remove(key);
                }
                if (nonAirSections.isEmpty()) {
                    prewarmCandidates.remove(key);
                } else {
                    prewarmCandidates.put(key,
                            new PrewarmCandidate(key, loadIdentity, nonAirSections));
                    highestPrewarmCandidates = Math.max(
                            highestPrewarmCandidates, prewarmCandidates.size());
                }
            }
        }
    }

    private void admitPrewarm() {
        if (closed || stopRequested || foregroundWorkPresent()
                || prewarmAdmitted >= MAX_PREWARM_ADMITTED || prewarmCandidates.isEmpty()) {
            return;
        }
        int turns = Math.min(prewarmCandidates.size(),
                MAX_PREWARM_ADMITTED - prewarmAdmitted);
        while (prewarmAdmitted < MAX_PREWARM_ADMITTED && turns-- > 0
                && !prewarmCandidates.isEmpty() && !foregroundWorkPresent()) {
            Map.Entry<PrewarmKey, PrewarmCandidate> first =
                    prewarmCandidates.entrySet().iterator().next();
            PrewarmCandidate candidate = first.getValue();
            prewarmCandidates.remove(first.getKey());
            Integer sectionY = candidate.currentSection();
            if (sectionY == null) continue;
            ChunkPos chunk = new ChunkPos(candidate.key.chunkLong);
            SectionPos section = SectionPos.of(chunk.x, sectionY, chunk.z);
            ClusterKey clusterKey = new ClusterKey(candidate.key.dimension, section);
            ClusterEntry cluster = clusters.get(clusterKey);
            if (cluster == null || cluster.latest.loadIdentity() < candidate.loadIdentity) {
                prewarmCandidates.put(candidate.key, candidate);
                continue;
            }
            if (cluster.latest.loadIdentity() > candidate.loadIdentity) {
                continue;
            }
            if (cluster.loadIdentity != candidate.loadIdentity) {
                if (eventUnavailable(cluster.latest.state())) candidate.advance();
                if (candidate.hasNext()) prewarmCandidates.put(candidate.key, candidate);
                continue;
            }
            candidate.advance();
            if (candidate.hasNext()) prewarmCandidates.put(candidate.key, candidate);
            if (cluster.factState == FactState.UNLOADED) continue;
            ViewEntry existing = cluster.views.get(DEFAULT_GEOMETRY);
            if (existing != null && (existing.topology != null || existing.demand != null)) continue;
            prewarmAdmitted++;
            highestPrewarmAdmitted = Math.max(
                    highestPrewarmAdmitted, prewarmAdmitted);
            if (!requestClusterDependency(candidate.key.dimension, section, DEFAULT_GEOMETRY,
                    NavigationScheduler.Priority.BACKGROUND, true, null)) {
                prewarmAdmitted--;
            }
        }
    }

    private boolean foregroundWorkPresent() {
        return !macroRequests.isEmpty()
                || !pendingRequestEvents.isEmpty()
                || !processingRequestEvents.isEmpty()
                || !pendingSectionEvents.isEmpty()
                || !processingSectionEvents.isEmpty()
                || !pendingChunkUnloads.isEmpty()
                || tickPending;
    }

    private void removePrewarm(ResourceKey<Level> dimension,
                               long chunkLong,
                               long loadIdentity) {
        PrewarmKey key = new PrewarmKey(dimension, chunkLong);
        PrewarmCandidate candidate = prewarmCandidates.get(key);
        if (candidate != null && candidate.loadIdentity == loadIdentity) {
            prewarmCandidates.remove(key, candidate);
        }
    }

    @Nullable
    private SuperClusterTopology superTopology(SuperCacheKey key) {
        requireRuntimeLock();
        SuperEntry entry = superClusters.get(key);
        if (entry == null || !superEntryCurrent(entry)) {
            return null;
        }
        touchSuper(entry);
        return entry.topology;
    }

    @Nullable
    private BaseClusterTopology[] currentChildTopologies(SuperCacheKey key) {
        BaseClusterTopology[] result = new BaseClusterTopology[8];
        BaseClusterTopology.GeometryKey geometry = key.geometry();
        int index = 0;
        for (SectionPos child : SuperClusterTopology.childSections(key.origin())) {
            ClusterEntry entry = clusters.get(new ClusterKey(key.dimension(), child));
            BaseClusterTopology topology = entry == null ? null : entry.topology(geometry);
            if (topology == null || topology.revision() != entry.revision) {
                return null;
            }
            result[index++] = topology;
        }
        return result;
    }

    private boolean superEntryCurrent(SuperEntry entry) {
        return entry.topology != null && entry.topologyValidity == entry.validity
                && !entry.topologyStamps.isEmpty() && isCurrent(entry.topologyStamps);
    }

    @Nullable
    private List<SectionStamp> currentSuperStamps(SuperCacheKey key,
                                                  SuperClusterTopology topology) {
        SuperEntry entry = superClusters.get(key);
        return entry != null && entry.topology == topology && superEntryCurrent(entry)
                ? entry.topologyStamps : null;
    }

    private boolean superClusterAvailable(ResourceKey<Level> dimension, SectionPos origin) {
        for (SectionPos child : SuperClusterTopology.childSections(origin)) {
            if (!clusterLoaded(new ClusterKey(dimension, child))) return false;
        }
        return true;
    }

    private void invalidateSuperParent(ClusterKey child, boolean unavailable) {
        invalidateSuperParents(child, unavailable, ignored -> true);
    }

    private void invalidateHaloSuperParents(ClusterKey child, int dx, int dy, int dz) {
        invalidateSuperParents(child, false,
                key -> requiresHalo(key.geometry(), dx, dy, dz));
    }

    private void invalidateSuperParents(ClusterKey child,
                                        boolean unavailable,
                                        Predicate<SuperCacheKey> affectedKey) {
        SectionPos origin = SuperClusterTopology.originOf(child.section());
        List<Map.Entry<SuperCacheKey, SuperEntry>> restart = new ArrayList<>();
        Set<SuperCacheKey> affected = superKeysByOrigin.get(
                new SuperOriginKey(child.dimension(), origin));
        if (affected == null) return;
        for (SuperCacheKey key : List.copyOf(affected)) {
            if (!affectedKey.test(key)) continue;
            SuperEntry entry = superClusters.get(key);
            if (entry == null) continue;
            removeSuperTopology(key, entry);
            if (unavailable) {
                entry.attempt++;
                failSuperRequest(key, entry, new StaleTopologyException(child));
            } else if (!entry.waiters.isEmpty() && !entry.attemptRunning) {
                restart.add(Map.entry(key, entry));
            } else if (entry.waiters.isEmpty()) {
                pruneSuperEntry(entry);
            }
        }
        for (Map.Entry<SuperCacheKey, SuperEntry> cached : restart) {
            if (superClusterAvailable(cached.getKey().dimension(), cached.getKey().origin())) {
                beginSuperRequest(cached.getKey(), cached.getValue());
            } else {
                failSuperRequest(
                        cached.getKey(),
                        cached.getValue(),
                        new StaleTopologyException(child)
                );
            }
        }
    }

    private void evictBaseCache() {
        while (baseRetainedBytes > MAX_BASE_RETAINED_BYTES) {
            BaseIdleEntry candidate = first(idleBaseEntries);
            if (candidate == null) return;
            if (candidate instanceof ClusterEntry fact) {
                removeIdleFact(fact);
                BaseClusterTopology.PackedFacts removed = fact.facts;
                if (removed == null || fact.pinnedFacts.containsKey(removed)) continue;
                fact.facts = null;
                if (fact.factState == FactState.AVAILABLE) fact.factState = FactState.PENDING;
                continue;
            }
            ViewEntry view = (ViewEntry) candidate;
            removeIdleBase(view);
            BaseClusterTopology removed = view.topology;
            if (removed == null || !baseIdle(view)) continue;
            invalidateBaseBoundaryLinks(view.owner.key, removed);
            retireBaseTopology(view);
            view.topology = null;
            pruneView(view);
        }
    }

    @Nullable
    private static <T> T first(LinkedHashSet<T> values) {
        Iterator<T> iterator = values.iterator();
        return iterator.hasNext() ? iterator.next() : null;
    }

    private void evictSuperCache() {
        while (idleSuperEntries.size() > MAX_SUPER_CACHE_ENTRIES
                || superRetainedBytes > MAX_SUPER_RETAINED_BYTES) {
            SuperEntry entry = first(idleSuperEntries);
            if (entry == null) return;
            removeIdleSuper(entry);
            if (!superIdle(entry)) continue;
            removeSuperTopology(entry.key, entry);
            removeSuperEntry(entry.key, entry);
        }
    }

    private void removeSuperTopology(SuperCacheKey key, SuperEntry entry) {
        if (entry.topology == null) {
            entry.topologyStamps = List.of();
            return;
        }
        SuperClusterTopology removed = entry.topology;
        invalidateSuperBoundaryLinks(key, removed);
        retireSuperTopology(entry);
        entry.topology = null;
        entry.topologyStamps = List.of();
    }

    private void invalidateBaseBoundaryLinks(ClusterKey key, BaseClusterTopology topology) {
        ClusterEntry source = clusters.get(key);
        ViewEntry owner = source == null ? null : source.views.get(topology.geometry());
        if (owner != null && owner.topology == topology) {
            clearOwnedBaseLinks(owner, "base boundary source changed");
            touchBase(owner);
        }
        // Incoming slots belong to their source view and are keyed by this target's
        // structural signature. A matching replacement can reuse them; a mismatch
        // is removed when that source next requests the slot.
    }

    private void invalidateSuperBoundaryLinks(SuperCacheKey key,
                                              SuperClusterTopology topology) {
        SuperEntry owner = superClusters.get(key);
        if (owner != null && owner.topology == topology) {
            clearOwnedSuperLinks(owner, "parent boundary source changed");
            touchSuper(owner);
        }
        // Parent incoming slots follow the same source-owned signature contract.
    }


    private void pinBase(ViewEntry view, BaseClusterTopology topology) {
        removeIdleBase(view);
        view.pinnedTopologies.merge(topology, 1, Integer::sum);
        addActiveReference();
    }

    @Nullable
    private List<ViewEntry> pinBaseInputs(ResourceKey<Level> dimension,
                                          BaseClusterTopology[] inputs) {
        List<ViewEntry> owners = new ArrayList<>(inputs.length);
        for (BaseClusterTopology input : inputs) {
            ViewEntry owner = baseView(dimension, input);
            if (owner == null) {
                releaseBaseInputs(owners, inputs);
                return null;
            }
            pinBase(owner, input);
            owners.add(owner);
        }
        return List.copyOf(owners);
    }

    private void releaseBaseInputs(List<ViewEntry> owners,
                                   BaseClusterTopology[] inputs) {
        for (int index = owners.size() - 1; index >= 0; index--) {
            releaseBasePin(owners.get(index), inputs[index]);
        }
    }

    private void releaseSuperBuildInputs(SuperEntry entry) {
        if (entry.buildInputs == null) {
            entry.buildStamps = List.of();
            return;
        }
        releaseBaseInputs(entry.buildInputOwners, entry.buildInputs);
        entry.buildInputs = null;
        entry.buildInputOwners = List.of();
        entry.buildStamps = List.of();
    }

    private void cancelSuperBuild(SuperEntry entry) {
        TopologyTaskExecutor.TaskHandle task = entry.buildTask;
        entry.buildTask = null;
        if (task == null) releaseSuperBuildInputs(entry);
        else cancelQueuedTask(task, cancelled -> {
            if (cancelled) {
                releaseSuperBuildInputs(entry);
                pruneSuperEntry(entry);
            }
        });
    }

    private void cancelLinkBuild(LinkEntry<?> entry) {
        TopologyTaskExecutor.TaskHandle task = entry.task;
        entry.task = UNTRACKED_TASK;
        if (task == UNTRACKED_TASK) releaseLinkInputs(entry);
        else cancelQueuedTask(task, cancelled -> {
            if (cancelled) releaseLinkInputs(entry);
        });
    }

    private boolean captureLinkBaseInputs(LinkEntry<?> entry,
                                          ResourceKey<Level> dimension,
                                          BaseClusterTopology[] inputs) {
        List<ViewEntry> owners = pinBaseInputs(dimension, inputs);
        if (owners == null) return false;
        entry.baseInputs = inputs;
        entry.baseInputOwners = owners;
        return true;
    }

    private boolean captureLinkSuperInputs(LinkEntry<?> entry,
                                           ResourceKey<Level> dimension,
                                           SuperClusterTopology source,
                                           SuperClusterTopology target) {
        SuperEntry sourceOwner = superView(dimension, source);
        SuperEntry targetOwner = superView(dimension, target);
        if (sourceOwner == null || targetOwner == null) return false;
        pinSuper(sourceOwner, source);
        pinSuper(targetOwner, target);
        entry.superInputs = new SuperClusterTopology[]{source, target};
        entry.superInputOwners = List.of(sourceOwner, targetOwner);
        return true;
    }

    private void releaseLinkInputs(LinkEntry<?> entry) {
        if (entry.baseInputs != null) {
            releaseBaseInputs(entry.baseInputOwners, entry.baseInputs);
            entry.baseInputs = null;
            entry.baseInputOwners = List.of();
        }
        if (entry.superInputs != null) {
            for (int index = 1; index >= 0; index--) {
                releaseSuperPin(entry.superInputOwners.get(index), entry.superInputs[index]);
            }
            entry.superInputs = null;
            entry.superInputOwners = List.of();
        }
    }

    private void releaseLinkPin(LinkEntry<?> entry) {
        if (--entry.pins < 0) throw new IllegalStateException("negative boundary pins");
        removeActiveReference();
        if (entry.pins == 0 && entry.retired) releaseLinkValue(entry);
    }

    private void releaseLinkValue(LinkEntry<?> entry) {
        entry.value = null;
    }

    private void releaseBasePin(ViewEntry view, BaseClusterTopology topology) {
        Integer count = view.pinnedTopologies.remove(topology);
        if (count == null) throw new IllegalStateException("base topology pin is not owned");
        if (count > 1) view.pinnedTopologies.put(topology, count - 1);
        removeActiveReference();
        touchBase(view);
        pruneView(view);
        pruneCluster(view.owner);
        evictBaseCache();
    }

    private void retireBaseTopology(ViewEntry view) {
        removeIdleBase(view);
        baseHandoffs.remove(view);
        view.topologyStamps = List.of();
    }

    private void pinSuper(SuperEntry entry, SuperClusterTopology topology) {
        removeIdleSuper(entry);
        entry.pinnedTopologies.merge(topology, 1, Integer::sum);
        addActiveReference();
    }

    private void releaseSuperPin(SuperEntry entry, SuperClusterTopology topology) {
        Integer count = entry.pinnedTopologies.remove(topology);
        if (count == null) throw new IllegalStateException("super topology pin is not owned");
        if (count > 1) entry.pinnedTopologies.put(topology, count - 1);
        removeActiveReference();
        touchSuper(entry);
        pruneSuperEntry(entry);
        evictSuperCache();
    }

    private void retireSuperTopology(SuperEntry entry) {
        removeIdleSuper(entry);
        superHandoffs.remove(entry);
    }

    private void ensureOpen() {
        if (closed || stopRequested) {
            throw new IllegalStateException("topology service is stopped");
        }
    }

    record ClusterKey(ResourceKey<Level> dimension, SectionPos section) {
        ClusterKey {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(section, "section");
        }
    }

    static final class SectionStamp {
        private final ClusterKey key;
        private final LatestFactsRef latest;
        private final LatestFacts expected;

        private SectionStamp(ClusterKey key, LatestFactsRef latest, LatestFacts expected) {
            this.key = key;
            this.latest = latest;
            this.expected = expected;
        }

        ClusterKey key() {
            return key;
        }

        long loadIdentity() {
            return expected.loadIdentity();
        }

        long version() {
            return expected.revision();
        }

        boolean current() {
            return latest.get() == expected && expected.state() == FactState.AVAILABLE;
        }
    }

    private record LatestFacts(long loadIdentity, long revision, FactState state) {
    }

    private static final class LatestFactsRef {
        private volatile LatestFacts value = new LatestFacts(0L, 0L, FactState.UNLOADED);

        private LatestFacts get() {
            return value;
        }

        private long loadIdentity() {
            return value.loadIdentity();
        }

        private long revision() {
            return value.revision();
        }

        private FactState state() {
            return value.state();
        }

        private void set(long loadIdentity, long revision, FactState state) {
            value = new LatestFacts(loadIdentity, revision, state);
        }
    }

    record WorkerResult(@Nullable MacroSearch.Corridor corridor,
                        MacroSearch.Progress progress,
                        List<SectionStamp> stamps,
                        boolean completedFromCache,
                        int staleRetries,
                        long attempt) {
        WorkerResult {
            stamps = List.copyOf(stamps);
        }
    }

    private record ChunkKey(ResourceKey<Level> dimension, long chunkLong) {
        private ChunkKey {
            Objects.requireNonNull(dimension, "dimension");
        }
    }

    enum FactState {
        PENDING,
        AVAILABLE,
        RECOVERY_FAILED,
        UNLOADED
    }

    private enum RequestEventKind {
        SUBMIT,
        FINAL_STALE,
        CANCEL
    }

    private record RequestEvent(RequestEventKind kind, long attempt,
                                @Nullable CompletableFuture<WorkerResult> response) {
        private static final RequestEvent SUBMIT =
                new RequestEvent(RequestEventKind.SUBMIT, 0L, null);
        private static final RequestEvent CANCEL =
                new RequestEvent(RequestEventKind.CANCEL, 0L, null);

        private static RequestEvent finalStale(long attempt,
                                               CompletableFuture<WorkerResult> response) {
            return new RequestEvent(RequestEventKind.FINAL_STALE, attempt, response);
        }
    }

    record SectionEvent(ClusterKey key,
                        long loadIdentity,
                        long previousVersion,
                        long version,
                        FactState state,
                        @Nullable BaseClusterTopology.PackedFacts facts,
                        Map<Integer, Byte> changes) {
        SectionEvent {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(changes, "changes");
            changes = Map.copyOf(changes);
            if (loadIdentity <= 0L || previousVersion < 0L || version < 0L) {
                throw new IllegalArgumentException("section identity and version must be valid");
            }
            if (state == FactState.AVAILABLE && facts == null && changes.isEmpty()
                    || state != FactState.AVAILABLE && (facts != null || !changes.isEmpty())) {
                throw new IllegalArgumentException("event data does not match its fact state");
            }
        }
    }

    @FunctionalInterface
    interface FactDemandListener {
        void changed(ClusterKey key, long loadIdentity, int delta);
    }

    private sealed interface BaseIdleEntry permits ClusterEntry, ViewEntry {
    }

    private final class ClusterEntry implements BaseIdleEntry {
        private final ClusterKey key;
        private long loadIdentity;
        private long revision;
        private final LatestFactsRef latest = new LatestFactsRef();
        private FactState factState = FactState.UNLOADED;
        private BaseClusterTopology.PackedFacts facts;
        private long factFingerprint;
        private boolean factFingerprintKnown;
        private final IdentityHashMap<BaseClusterTopology.PackedFacts, Integer> pinnedFacts =
                new IdentityHashMap<>();
        private final Set<TopologyDemand> factWaiters =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<BaseClusterTopology.GeometryKey, ViewEntry> views = new HashMap<>();

        private ClusterEntry(ClusterKey key) {
            this.key = key;
        }

        private ViewEntry view(BaseClusterTopology.GeometryKey geometry) {
            return views.computeIfAbsent(geometry,
                    ignored -> new ViewEntry(this, geometry));
        }

        @Nullable
        private BaseClusterTopology topology(BaseClusterTopology.GeometryKey geometry) {
            if (!current()) return null;
            ViewEntry view = views.get(geometry);
            return view == null || view.topologyValidity != view.validity
                    ? null : view.topology;
        }

        private boolean current() {
            LatestFacts value = latest.get();
            return loadIdentity == value.loadIdentity() && revision == value.revision()
                    && value.state() != FactState.UNLOADED
                    && value.state() != FactState.RECOVERY_FAILED;
        }
    }

    private void clearOwnedBaseLinks(ViewEntry owner, String reason) {
        for (int slot = 0; slot < owner.links.length; slot++) clearBaseLink(owner, slot, reason);
    }

    private void clearOwnedSuperLinks(SuperEntry owner, String reason) {
        for (int slot = 0; slot < owner.links.length; slot++) clearSuperLink(owner, slot, reason);
    }

    private final class ViewEntry implements BaseIdleEntry {
        private final ClusterEntry owner;
        private final BaseClusterTopology.GeometryKey geometry;
        private BaseClusterTopology topology;
        private List<SectionStamp> topologyStamps = List.of();
        private TopologyDemand demand;
        private volatile long validity;
        private long topologyValidity;
        private long handoffUntilTick = Long.MIN_VALUE;
        private final IdentityHashMap<BaseClusterTopology, Integer> pinnedTopologies =
                new IdentityHashMap<>();
        @SuppressWarnings("unchecked")
        private final LinkEntry<SuperClusterTopology.BoundaryLinks>[] links =
                (LinkEntry<SuperClusterTopology.BoundaryLinks>[]) new LinkEntry<?>[14];

        private ViewEntry(ClusterEntry owner, BaseClusterTopology.GeometryKey geometry) {
            this.owner = owner;
            this.geometry = geometry;
        }
    }

    private final class TopologyDemand {
        private final ClusterKey key;
        private final BaseClusterTopology.GeometryKey geometry;
        private final long generation;
        private final Set<TopologyWaiter<BaseClusterTopology>> waiters =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<ClusterEntry> waitingFacts =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private final IdentityHashMap<ClusterEntry, Long> demandedFacts =
                new IdentityHashMap<>();
        private final IdentityHashMap<ClusterEntry, BaseClusterTopology.PackedFacts> heldFacts =
                new IdentityHashMap<>();
        private NavigationScheduler.Priority priority = NavigationScheduler.Priority.BACKGROUND;
        private TopologyTaskExecutor.TaskHandle buildTask = UNTRACKED_TASK;
        private long fingerprint;
        private boolean prewarmSlot;

        private TopologyDemand(ClusterKey key,
                               BaseClusterTopology.GeometryKey geometry,
                               long generation) {
            this.key = key;
            this.geometry = geometry;
            this.generation = generation;
            beginBuildDemand();
        }

    }

    private final class TopologyWaiter<T> {
        private final Consumer<TopologyWaiter<T>> completion;
        private NavigationScheduler.Priority priority;
        private TopologyDemand demand;
        private Runnable cancellation = () -> {
        };
        private Consumer<NavigationScheduler.Priority> reprioritization = ignored -> {
        };
        private T value;
        private Throwable failure;
        private boolean active = true;

        private TopologyWaiter(NavigationScheduler.Priority priority,
                               Consumer<TopologyWaiter<T>> completion) {
            this.priority = Objects.requireNonNull(priority, "priority");
            this.completion = Objects.requireNonNull(completion, "completion");
            beginDependencyConsumer();
        }

        private void complete(@Nullable T completedValue, @Nullable Throwable completedFailure) {
            if (!deactivate()) return;
            demand = null;
            value = completedValue;
            failure = completedFailure;
            completion.accept(this);
        }

        private boolean deactivate() {
            if (!active) return false;
            active = false;
            finishDependencyConsumer();
            return true;
        }

        private void cancel() {
            if (!active) return;
            cancellation.run();
            if (active) deactivate();
        }

        private void reprioritize(NavigationScheduler.Priority requested) {
            if (active && priority != requested) {
                reprioritization.accept(requested);
            }
        }

    }

    private final class SuperEntry {
        private final SuperCacheKey key;
        private long attempt;
        private boolean attemptRunning;
        private SuperClusterTopology topology;
        private List<SectionStamp> topologyStamps = List.of();
        private List<SectionStamp> buildStamps = List.of();
        private volatile long validity;
        private long topologyValidity;
        private long handoffUntilTick = Long.MIN_VALUE;
        private final IdentityHashMap<SuperClusterTopology, Integer> pinnedTopologies =
                new IdentityHashMap<>();
        private final Set<TopologyWaiter<SuperClusterTopology>> waiters =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private List<TopologyWaiter<BaseClusterTopology>> children = List.of();
        private boolean collectingChildren;
        private NavigationScheduler.Priority requestPriority;
        private TopologyTaskExecutor.TaskHandle buildTask;
        private BaseClusterTopology[] buildInputs;
        private List<ViewEntry> buildInputOwners = List.of();
        @SuppressWarnings("unchecked")
        private final LinkEntry<SuperClusterTopology.CrossingIndex>[] links =
                (LinkEntry<SuperClusterTopology.CrossingIndex>[]) new LinkEntry<?>[14];

        private SuperEntry(SuperCacheKey key) {
            this.key = key;
        }
    }

    private record SuperCacheKey(ResourceKey<Level> dimension,
                                 SectionPos origin,
                                 BaseClusterTopology.GeometryKey geometry,
                                 BaseClusterTopology.MovementKey movement) {
        private SuperCacheKey(ResourceKey<Level> dimension,
                              SectionPos origin,
                              BaseClusterTopology.Channel channel,
                              BaseClusterTopology.TraversalProfile profile) {
            this(dimension, origin, profile.geometry(channel), profile.movement(channel));
        }

        private SuperCacheKey {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(geometry, "geometry");
            Objects.requireNonNull(movement, "movement");
        }

    }

    private record SuperOriginKey(ResourceKey<Level> dimension, SectionPos origin) {
    }

    private record BaseBoundaryCacheKey(
            ResourceKey<Level> dimension,
            BaseClusterTopology source,
            BaseClusterTopology target,
            Direction face) {

        private BaseBoundaryCacheKey {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(face, "face");
            if (!source.geometry().equals(target.geometry())) {
                throw new IllegalArgumentException("base boundary geometries differ");
            }
            SectionPos direct = SuperClusterTopology.offset(source.section(), face, 1);
            if (target.section().x() != direct.x() || target.section().z() != direct.z()
                    || (face.getAxis().isVertical() && target.section().y() != direct.y())
                    || (!face.getAxis().isVertical()
                    && Math.abs(target.section().y() - source.section().y()) > 1)) {
                throw new IllegalArgumentException("base boundary cache key is not adjacent");
            }
        }

    }

    private record SuperBoundaryCacheKey(ResourceKey<Level> dimension,
                                         SuperClusterTopology source,
                                         SuperClusterTopology target,
                                         Direction face) {
        private SuperBoundaryCacheKey {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(face, "face");
            SectionPos direct = SuperClusterTopology.offset(
                    source.origin(), face, SuperClusterTopology.CHILDREN_PER_AXIS
            );
            boolean adjacent = face.getAxis().isVertical()
                    ? target.origin().equals(direct)
                    : target.origin().x() == direct.x() && target.origin().z() == direct.z()
                    && Math.abs(target.origin().y() - source.origin().y())
                    <= SuperClusterTopology.CHILDREN_PER_AXIS;
            if (!adjacent || !target.geometry().equals(source.geometry())
                    || !target.movement().equals(source.movement())) {
                throw new IllegalArgumentException("super boundary cache key is not compatible");
            }
        }
    }

    private record SuperBoundaryBuildInput(BaseClusterTopology[] sourceChildren,
                                           BaseClusterTopology[] targetChildren) {
    }

    private final class LinkEntry<T> {
        private final long targetSignature;
        private final Set<TopologyWaiter<T>> waiters =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private volatile NavigationScheduler.Priority priority;
        private TopologyTaskExecutor.TaskHandle task = UNTRACKED_TASK;
        private List<TopologyWaiter<BaseClusterTopology>> children = List.of();
        private boolean collectingChildren;
        private BaseClusterTopology[] baseInputs;
        private List<ViewEntry> baseInputOwners = List.of();
        private SuperClusterTopology[] superInputs;
        private List<SuperEntry> superInputOwners = List.of();
        private T value;
        private int pins;
        private boolean retired;

        private LinkEntry(NavigationScheduler.Priority priority, long targetSignature) {
            this.priority = Objects.requireNonNull(priority, "priority");
            this.targetSignature = targetSignature;
            beginBuildDemand();
        }

        private void promote(NavigationScheduler.Priority requested) {
            if (requested.higherThan(priority)) {
                priority = requested;
                reprioritizeTask(task, requested);
                children.forEach(child -> child.reprioritize(requested));
            }
        }

    }

    private void joinResolve(MacroRequest request) {
        RawQueryKey key = new RawQueryKey(request.dimension, request.startPosition,
                request.goalPosition, request.channel, request.profile,
                Float.floatToRawIntBits(request.weight));
        ResolveFlight flight = resolveFlights.get(key);
        if (flight == null) {
            flight = new ResolveFlight(key);
            resolveFlights.put(key, flight);
            highestEndpointResolutions = Math.max(
                    highestEndpointResolutions, resolveFlights.size());
        }
        flight.add(request);
        if (flight.dependencies.isEmpty() && flight.task == UNTRACKED_TASK) flight.request();
    }

    private void joinResolved(MacroRequest request,
                              CandidateResolution starts,
                              CandidateResolution goals) {
        if (starts.candidates.isEmpty() || goals.candidates.isEmpty()) {
            CandidateResolution missing = starts.candidates.isEmpty() ? starts : goals;
            MacroSearch.Failure failure = missing.unavailable == null
                    ? MacroSearch.Failure.NO_STRUCTURAL_ROUTE
                    : missing.unavailableFailure;
            SectionPos blocked = missing.unavailable == null
                    ? SectionPos.of(starts.candidates.isEmpty()
                    ? request.startPosition : request.goalPosition) : missing.unavailable;
            request.finish(null, failure, blocked, List.of());
            return;
        }
        boolean hierarchical = !request.forceBaseSearch
                && shouldUseSuperGraph(starts.candidates, goals.candidates)
                && endpointParentsAvailable(request.dimension, starts.candidates)
                && endpointParentsAvailable(request.dimension, goals.candidates);
        joinCanonical(request, starts.candidates, goals.candidates, hierarchical);
    }

    private void joinCanonical(MacroRequest request,
                               List<MacroComponentKey> starts,
                               List<MacroComponentKey> goals,
                               boolean hierarchical) {
        MacroQueryKey key = new MacroQueryKey(request.dimension, request.channel,
                request.profile, hierarchical, Float.floatToRawIntBits(request.weight),
                queryNodeBudget(request.startPosition, request.goalPosition, hierarchical),
                starts, goals);
        joinCanonical(request, key);
    }

    private void joinCanonical(MacroRequest request, MacroQueryKey key) {
        CachedCorridor cached = completedCorridors.get(key);
        if (cached != null) {
            replayCompletedCorridor(request, key, cached);
            return;
        }
        MacroFlight flight = macroFlights.get(key);
        if (flight == null) {
            flight = new MacroFlight(key, request);
            macroFlights.put(key, flight);
            highestPhysicalSearches = Math.max(
                    highestPhysicalSearches, macroFlights.size());
            flight.start();
        } else flight.add(request);
    }

    private void replayCompletedCorridor(MacroRequest request,
                                         MacroQueryKey key,
                                         CachedCorridor cached) {
        requireRuntimeLock();
        afterRuntimeLock(() -> {
            boolean current = isCurrent(cached.stamps);
            MacroSearch.Corridor replayed = null;
            Throwable replayFailure = null;
            if (current) {
                try {
                    replayed = rematerializeCorridor(cached.corridor,
                            request.startPosition, request.goalPosition);
                } catch (VirtualMachineError | ThreadDeath fatal) {
                    throw fatal;
                } catch (Throwable failure) {
                    replayFailure = failure;
                }
            }
            MacroSearch.Corridor result = replayed;
            Throwable failure = replayFailure;
            runRuntimeTransition(() -> {
                if (!request.active) return;
                if (!current) {
                    if (completedCorridors.remove(key, cached)) {
                        completedCorridorBytes -= cached.retainedBytes;
                    }
                    joinCanonical(request, key);
                } else if (failure != null) {
                    request.finishExceptionally(failure);
                } else {
                    request.completedFromCache = true;
                    completedCacheHits++;
                    request.finish(result, null, null, cached.stamps);
                }
            });
        });
    }

    private void finishCorridors(List<MacroRequest> requests,
                                 MacroSearch.Corridor corridor,
                                 List<SectionStamp> stamps) {
        requireRuntimeLock();
        List<MacroRequest> completing = List.copyOf(requests);
        List<SectionStamp> resultStamps = List.copyOf(stamps);
        afterRuntimeLock(() -> {
            List<MacroSearch.Corridor> results = new ArrayList<>(completing.size());
            Throwable materializationFailure = null;
            try {
                for (MacroRequest request : completing) {
                    results.add(rematerializeCorridor(corridor, request.startPosition,
                            request.goalPosition));
                }
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable failure) {
                materializationFailure = failure;
            }
            Throwable failure = materializationFailure;
            runRuntimeTransition(() -> {
                if (failure != null) {
                    completing.forEach(request -> request.finishExceptionally(failure));
                    return;
                }
                for (int index = 0; index < completing.size(); index++) {
                    completing.get(index).finish(results.get(index), null, null, resultStamps);
                }
            });
        });
    }

    private void retryStaleRequest(MacroRequest request) {
        try {
            request.retryStale();
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable failure) {
            request.finishExceptionally(failure);
        }
    }

    private final class ResolveFlight {
        private final RawQueryKey key;
        private final Set<MacroRequest> waiters =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private List<TopologyWaiter<BaseClusterTopology>> dependencies = List.of();
        private List<EndpointTopology> held = List.of();
        private TopologyTaskExecutor.TaskHandle task = UNTRACKED_TASK;
        private NavigationScheduler.Priority priority = NavigationScheduler.Priority.BACKGROUND;
        private boolean fallbackStart;
        private boolean fallbackGoal;
        private boolean collectingDependencies;

        private ResolveFlight(RawQueryKey key) { this.key = key; }

        private void add(MacroRequest request) {
            waiters.add(request);
            request.resolver = this;
            updatePriority();
        }

        private void request() {
            Set<SectionPos> sections = new HashSet<>();
            if (fallbackStart) sections.addAll(candidateSections(key.start));
            else sections.add(SectionPos.of(key.start));
            if (fallbackGoal) sections.addAll(candidateSections(key.goal));
            else sections.add(SectionPos.of(key.goal));
            List<TopologyWaiter<BaseClusterTopology>> requested = new ArrayList<>();
            collectingDependencies = true;
            dependencies = requested;
            for (SectionPos section : sections.stream()
                    .sorted(Comparator.comparingLong(SectionPos::asLong)).toList()) {
                if (clusterLoaded(new ClusterKey(key.dimension, section))) {
                    TopologyWaiter<BaseClusterTopology> waiter = new TopologyWaiter<>(
                            priority, ignored -> dependenciesReady());
                    requested.add(waiter);
                    requestClusterDependency(key.dimension, section,
                            key.profile.geometry(key.channel), priority, false, waiter);
                }
            }
            collectingDependencies = false;
            dependencies = List.copyOf(requested);
            dependenciesReady();
        }

        private void dependenciesReady() {
            if (resolveFlights.get(key) != this || waiters.isEmpty()
                    || collectingDependencies
                    || dependencies.stream().anyMatch(waiter -> waiter.active)) return;
            List<TopologyWaiter<BaseClusterTopology>> ready = dependencies;
            dependencies = List.of();
            Set<SectionPos> recoveryFailures = new HashSet<>();
            Throwable failure = null;
            for (TopologyWaiter<BaseClusterTopology> waiter : ready) {
                if (waiter.failure == null) continue;
                Throwable root = rootFailure(waiter.failure);
                if (root instanceof FactsRecoveryException facts) {
                    recoveryFailures.add(facts.key.section());
                } else {
                    failure = waiter.failure;
                    break;
                }
            }
            if (failure != null) {
                completeFailure(failure);
                return;
            }
            List<EndpointTopology> captured = new ArrayList<>(ready.size());
            for (TopologyWaiter<BaseClusterTopology> waiter : ready) {
                if (waiter.failure != null) continue;
                BaseClusterTopology topology = waiter.value;
                ViewEntry owner = baseView(key.dimension, topology);
                if (owner == null) {
                    release(captured);
                    retryAll();
                    return;
                }
                pinBase(owner, topology);
                captured.add(new EndpointTopology(topology, owner, owner.validity));
            }
            held = List.copyOf(captured);
            task = SUBMITTING_TASK;
            submitOutsideRuntimeLock(
                    () -> taskExecutor.submitSearch(priority,
                            TopologyTaskExecutor.WorkKind.QUICK_SEARCH,
                            () -> resolveOnWorker(held, Set.copyOf(recoveryFailures))),
                    submitted -> {
                        if (task != SUBMITTING_TASK || resolveFlights.get(key) != this
                                || waiters.isEmpty()) {
                            afterRuntimeLock(() -> submitted.cancelWhenQueued(cancelled -> {
                                if (cancelled) runRuntimeTransition(this::releaseHeld);
                            }));
                            return;
                        }
                        task = submitted;
                        afterRuntimeLock(() -> submitted.reprioritize(priority));
                    },
                    rejected -> {
                        if (task != SUBMITTING_TASK) {
                            if (waiters.isEmpty()) releaseHeld();
                            return;
                        }
                        task = UNTRACKED_TASK;
                        releaseHeld();
                        completeFailure(rejected);
                    });
        }

        private void resolveOnWorker(List<EndpointTopology> input,
                                     Set<SectionPos> recoveryFailures) {
            try {
                Map<Long, BaseClusterTopology> topologies = new HashMap<>(input.size());
                input.forEach(captured -> topologies.put(captured.topology.section().asLong(),
                        captured.topology));
                CandidateResolution starts = resolveEndpoint(key.start, fallbackStart,
                        key.channel, topologies, recoveryFailures);
                CandidateResolution goals = resolveEndpoint(key.goal, fallbackGoal,
                        key.channel, topologies, recoveryFailures);
                runRuntimeTransition(() -> resolved(starts, goals));
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable failure) {
                runRuntimeTransition(() -> resolutionFailed(failure));
            }
        }

        private void resolutionFailed(Throwable failure) {
            task = UNTRACKED_TASK;
            releaseHeld();
            if (resolveFlights.get(key) == this && !waiters.isEmpty()) {
                completeFailure(failure);
            }
        }

        private void resolved(CandidateResolution starts, CandidateResolution goals) {
            task = UNTRACKED_TASK;
            boolean current = held.stream().allMatch(input ->
                    input.owner.validity == input.validity
                            && baseView(key.dimension, input.topology) == input.owner);
            releaseHeld();
            if (resolveFlights.get(key) != this || waiters.isEmpty()) return;
            if (!current) {
                retryAll();
                return;
            }
            boolean retryStart = !fallbackStart && starts.candidates.isEmpty()
                    && starts.unavailable == null;
            boolean retryGoal = !fallbackGoal && goals.candidates.isEmpty()
                    && goals.unavailable == null;
            if (retryStart || retryGoal) {
                fallbackStart |= retryStart;
                fallbackGoal |= retryGoal;
                request();
                return;
            }
            resolveFlights.remove(key, this);
            List<MacroRequest> resolved = List.copyOf(waiters);
            waiters.clear();
            for (MacroRequest request : resolved) {
                request.resolver = null;
                try {
                    joinResolved(request, starts, goals);
                } catch (VirtualMachineError | ThreadDeath fatal) {
                    throw fatal;
                } catch (Throwable failure) {
                    request.finishExceptionally(failure);
                }
            }
        }

        private void completeFailure(Throwable failure) {
            Throwable root = rootFailure(failure);
            if (staleTopologyFailure(failure)) {
                retryAll();
                return;
            }
            resolveFlights.remove(key, this);
            List<MacroRequest> failed = List.copyOf(waiters);
            waiters.clear();
            for (MacroRequest request : failed) {
                request.resolver = null;
                if (root instanceof FactsRecoveryException facts) {
                    request.finish(null, MacroSearch.Failure.FACTS_RECOVERY_FAILED,
                            facts.key.section(), List.of());
                } else request.finishExceptionally(root);
            }
        }

        private void retryAll() {
            resolveFlights.remove(key, this);
            List<MacroRequest> retrying = List.copyOf(waiters);
            waiters.clear();
            retrying.forEach(request -> {
                request.resolver = null;
                retryStaleRequest(request);
            });
        }

        private void remove(MacroRequest request) {
            if (!waiters.remove(request) || !waiters.isEmpty()) {
                updatePriority();
                return;
            }
            resolveFlights.remove(key, this);
            dependencies.forEach(TopologyWaiter::cancel);
            dependencies = List.of();
            TopologyTaskExecutor.TaskHandle activeTask = task;
            task = UNTRACKED_TASK;
            if (activeTask == UNTRACKED_TASK) releaseHeld();
            else cancelQueuedTask(activeTask, cancelled -> { if (cancelled) releaseHeld(); });
        }

        private void updatePriority() {
            priority = waiters.stream().map(request -> request.priority)
                    .reduce(NavigationScheduler.Priority.BACKGROUND,
                            TopologyWorkerRuntime::higherPriority);
            dependencies.forEach(dependency -> dependency.reprioritize(priority));
            reprioritizeTask(task, priority);
        }

        private void releaseHeld() {
            release(held);
            held = List.of();
        }

        private void release(List<EndpointTopology> inputs) {
            for (int index = inputs.size() - 1; index >= 0; index--) {
                EndpointTopology input = inputs.get(index);
                releaseBasePin(input.owner, input.topology);
            }
        }
    }

    private static CandidateResolution resolveEndpoint(
            BlockPos position,
            boolean fallback,
            BaseClusterTopology.Channel channel,
            Map<Long, BaseClusterTopology> topologies,
            Set<SectionPos> recoveryFailures) {
        Set<MacroComponentKey> candidates = new HashSet<>();
        SectionPos unavailable = null;
        MacroSearch.Failure unavailableFailure = null;
        for (BlockPos anchor : fallback ? candidateAnchors(position) : List.of(position)) {
            SectionPos section = SectionPos.of(anchor);
            BaseClusterTopology topology = topologies.get(section.asLong());
            if (topology == null) {
                boolean recoveryFailed = recoveryFailures.contains(section);
                if (unavailable == null || recoveryFailed
                        && unavailableFailure != MacroSearch.Failure.FACTS_RECOVERY_FAILED) {
                    unavailable = section;
                    unavailableFailure = recoveryFailed
                            ? MacroSearch.Failure.FACTS_RECOVERY_FAILED
                            : MacroSearch.Failure.UNAVAILABLE_CHUNK;
                }
                continue;
            }
            int component = directComponent(topology, anchor, channel);
            if (component >= 0) candidates.add(new MacroComponentKey(
                    section, component, topology.signature()));
        }
        return new CandidateResolution(candidates.stream()
                .sorted(MacroComponentKey.ORDER).toList(), unavailable, unavailableFailure);
    }

    private record CapturedBoundary<T>(T value, LinkEntry<T> owner) {
    }

    private static final class ProgressHolder {
        private volatile MacroSearch.Progress value = MacroSearch.Progress.PENDING;
    }

    private record RequestProgress(MacroSearch.Progress completed,
                                   ProgressHolder current) {
    }

    final class MacroRequest {
        private final ResourceKey<Level> dimension;
        private final BlockPos startPosition;
        private final BlockPos goalPosition;
        private final BaseClusterTopology.Channel channel;
        private final BaseClusterTopology.TraversalProfile profile;
        private final NavigationScheduler.Priority priority;
        private final float weight;
        private CompletableFuture<WorkerResult> future = new CompletableFuture<>();

        private boolean active = true;
        private boolean cancelPublished;
        private boolean completedFromCache;
        private boolean forceBaseSearch;
        private int staleRetries;
        private long attempt = 1L;
        private ResolveFlight resolver;
        private MacroFlight flight;
        private volatile RequestProgress progress = new RequestProgress(
                MacroSearch.Progress.PENDING, new ProgressHolder());

        private MacroRequest(ResourceKey<Level> dimension,
                             BlockPos startPosition,
                             BlockPos goalPosition,
                             BaseClusterTopology.Channel channel,
                             BaseClusterTopology.TraversalProfile profile,
                             NavigationScheduler.Priority priority,
                             float weight) {
            this.dimension = dimension;
            this.startPosition = startPosition;
            this.goalPosition = goalPosition;
            this.channel = channel;
            this.profile = profile;
            this.priority = priority;
            this.weight = weight;
        }

        CompletableFuture<WorkerResult> future() {
            return future;
        }

        MacroSearch.Progress progress() {
            RequestProgress snapshot = progress;
            return snapshot.current.value.after(snapshot.completed);
        }

        CompletableFuture<WorkerResult> rejectFinalStaleResult(long rejectedAttempt) {
            CompletableFuture<WorkerResult> response = new CompletableFuture<>();
            publishRequestEvent(this, RequestEvent.finalStale(rejectedAttempt, response));
            return response;
        }

        void cancel() {
            publishRequestEvent(this, RequestEvent.CANCEL);
        }

        private void retryAfterFinalValidation(long rejectedAttempt,
                                               @Nullable CompletableFuture<WorkerResult> response) {
            requireRuntimeLock();
            if (response == null) return;
            if (active || rejectedAttempt != attempt) {
                forwardFuture(future, response);
                return;
            }
            RequestProgress previous = progress;
            MacroSearch.Progress stale = previous.current.value.withOutcome(Status.FAILED,
                    MacroSearch.Failure.STALE_WORLD,
                    previous.current.value.blockedSection());
            if (staleRetries != 0) {
                staleRetryExhaustions++;
                finishFuture(response, new WorkerResult(null,
                        stale.after(previous.completed), List.of(),
                        completedFromCache, staleRetries, attempt), null);
                return;
            }
            future = response;
            progress = new RequestProgress(stale.after(previous.completed),
                    new ProgressHolder());
            completedFromCache = false;
            staleRetries = 1;
            automaticStaleRetries++;
            attempt++;
            active = true;
            acceptMacroRequest(this);
        }

        private void completeRejected(Throwable rejection) {
            active = false;
            future.completeExceptionally(rejection);
        }

        private void cancelBeforeAccept() {
            active = false;
            future.cancel(false);
        }

        private void finish(@Nullable MacroSearch.Corridor result,
                             @Nullable MacroSearch.Failure resultFailure,
                             @Nullable SectionPos blockedSection,
                             List<SectionStamp> stamps) {
            if (!active) {
                return;
            }
            active = false;
            detach();
            RequestProgress currentProgress = progress;
            MacroSearch.Failure outcome = resultFailure == null
                    ? result == null ? currentProgress.current.value.failure()
                    : MacroSearch.Failure.NONE
                    : resultFailure;
            currentProgress.current.value = currentProgress.current.value.withOutcome(
                    result == null ? Status.FAILED : Status.SUCCEEDED,
                    outcome, blockedSection);
            macroRequests.remove(this);
            finishFuture(future, new WorkerResult(result, progress(), stamps,
                    completedFromCache, staleRetries, attempt), null);
        }

        private void finishExceptionally(Throwable resultFailure) {
            if (!active) {
                return;
            }
            active = false;
            detach();
            macroRequests.remove(this);
            finishFuture(future, null, resultFailure);
        }

        private void detach() {
            ResolveFlight resolving = resolver;
            resolver = null;
            if (resolving != null) resolving.remove(this);
            MacroFlight current = flight;
            flight = null;
            if (current != null) current.remove(this);
        }

        private void cancelInternal() {
            requireRuntimeLock();
            if (!active) {
                afterRuntimeLock(() -> future.cancel(false));
                return;
            }
            active = false;
            detach();
            macroRequests.remove(this);
            afterRuntimeLock(() -> future.cancel(false));
        }

        private void retryStale() {
            if (!active) return;
            workerStaleResults++;
            RequestProgress previous = progress;
            MacroSearch.Progress stale = previous.current.value.withOutcome(Status.FAILED,
                    MacroSearch.Failure.STALE_WORLD,
                    previous.current.value.blockedSection());
            if (staleRetries == 0) {
                staleRetries = 1;
                automaticStaleRetries++;
                progress = new RequestProgress(stale.after(previous.completed),
                        new ProgressHolder());
                completedFromCache = false;
                attempt++;
                joinResolve(this);
            } else {
                staleRetryExhaustions++;
                previous.current.value = stale;
                finish(null, MacroSearch.Failure.STALE_WORLD,
                        stale.blockedSection(), List.of());
            }
        }

        private void beginParentFallback() {
            RequestProgress previous = progress;
            progress = new RequestProgress(previous.current.value.after(previous.completed),
                    new ProgressHolder());
            completedFromCache = false;
            forceBaseSearch = true;
        }
    }

    private final class MacroFlight {
        private final MacroQueryKey key;
        private final Set<MacroRequest> waiters =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private final ProgressHolder progress = new ProgressHolder();
        private volatile NavigationScheduler.Priority priority;
        private MacroQuery query;
        private volatile TopologyTaskExecutor.TaskHandle searchTask = UNTRACKED_TASK;
        private volatile long taskGeneration;

        private MacroFlight(MacroQueryKey key, MacroRequest first) {
            this.key = key;
            add(first);
        }

        private void start() {
            MacroRequest representative = waiters.iterator().next();
            query = new MacroQuery(
                    representative.dimension,
                    representative.startPosition,
                    representative.goalPosition,
                    representative.channel,
                    representative.profile,
                    priority,
                    representative.weight,
                    key,
                    this::requestResume
            );
            resume();
        }

        private void requestResume() {
            requireRuntimeLock();
            if (eventBatchActive) {
                deferredSearchResumes.add(this);
            } else {
                resume();
            }
        }

        private void resume() {
            requireRuntimeLock();
            if (query == null || query.status != ResumableSearch.Status.RUNNING
                    || waiters.isEmpty() || searchTask != UNTRACKED_TASK) {
                return;
            }
            try {
                if (!query.prepareForWorker()) {
                    if (query.status != ResumableSearch.Status.RUNNING) {
                        complete(null, query.failureCause());
                    } else if (query.parentFallbackPending) {
                        fallbackToBase();
                    }
                    return;
                }
            } catch (Throwable failure) {
                if (failure instanceof VirtualMachineError fatal) {
                    throw fatal;
                }
                if (failure instanceof ThreadDeath fatal) {
                    throw fatal;
                }
                failSearch(failure);
                return;
            }
            TopologyTaskExecutor.WorkKind kind = query.longContinuation
                    ? TopologyTaskExecutor.WorkKind.LONG_SEARCH
                    : TopologyTaskExecutor.WorkKind.QUICK_SEARCH;
            int budget = query.longContinuation ? MAX_LONG_SEARCH_NODES : MAX_FAST_SEARCH_NODES;
            long generation = ++taskGeneration;
            searchTask = SUBMITTING_TASK;
            submitOutsideRuntimeLock(
                    () -> taskExecutor.submitSearch(priority, kind,
                            () -> runWorkerTask(generation, budget)),
                    submitted -> {
                        if (generation != taskGeneration || searchTask != SUBMITTING_TASK) {
                            afterRuntimeLock(() -> submitted.cancelWhenQueued(ignored -> {
                            }));
                            return;
                        }
                        searchTask = submitted;
                        if (query.cancelRequested) {
                            cancelQueuedTask(submitted, cancelled -> {
                                if (!cancelled || searchTask != submitted) return;
                                searchTask = UNTRACKED_TASK;
                                query.finishOnOwner();
                            });
                        } else {
                            afterRuntimeLock(() -> submitted.reprioritize(priority));
                        }
                    },
                    failure -> {
                        if (generation != taskGeneration || searchTask != SUBMITTING_TASK) return;
                        completeWorker(generation, null, failure);
                    });
        }

        private void runWorkerTask(long generation, int budget) {
            ResumableSearch.Status status = null;
            Throwable failure = null;
            try {
                status = query.runWorkerSlice(budget);
                progress.value = query.progress();
                if (status == ResumableSearch.Status.RUNNING
                        && requeueWorkerContinuation(generation, budget)) {
                    return;
                }
                if (status == ResumableSearch.Status.SUCCEEDED) {
                    query.prepareWorkerResult();
                }
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable exception) {
                failure = exception;
            }
            ResumableSearch.Status resultStatus = status;
            Throwable resultFailure = failure;
            runRuntimeTransition(() -> completeWorker(
                    generation, resultStatus, resultFailure));
        }

        private boolean requeueWorkerContinuation(long generation, int completedBudget) {
            if (generation != taskGeneration
                    || query == null
                    || query.status != ResumableSearch.Status.RUNNING
                    || query.cancelRequested
                    || query.recoveryPreparationPending
                    || query.needsDependencyResolution()) {
                return false;
            }
            if (query.lastWorkerExpanded >= completedBudget) {
                query.longContinuation = true;
            }
            int nextBudget = query.longContinuation
                    ? MAX_LONG_SEARCH_NODES : MAX_FAST_SEARCH_NODES;
            long nextGeneration = generation + 1L;
            taskGeneration = nextGeneration;
            TopologyTaskExecutor.WorkKind nextKind = query.longContinuation
                    ? TopologyTaskExecutor.WorkKind.LONG_SEARCH
                    : TopologyTaskExecutor.WorkKind.QUICK_SEARCH;
            if (taskExecutor.requeueCurrent(priority, nextKind,
                    () -> runWorkerTask(nextGeneration, nextBudget))) {
                return true;
            }
            runRuntimeTransition(() -> completeWorker(
                    nextGeneration,
                    null,
                    new RejectedExecutionException("topology worker continuation rejected")
            ));
            return true;
        }

        private void completeWorker(long generation,
                                    @Nullable ResumableSearch.Status status,
                                    @Nullable Throwable searchFailure) {
            requireRuntimeLock();
            if (generation != taskGeneration) {
                return;
            }
            searchTask = UNTRACKED_TASK;
            if (macroFlights.get(key) != this || waiters.isEmpty()) {
                query.requestCancel();
                query.finishOnOwner();
                return;
            }
            try {
                query.drainDeferredGraphs();
                if (searchFailure != null) {
                    failSearch(searchFailure);
                    return;
                }
                if (status == null) {
                    IllegalStateException failure =
                            new IllegalStateException("worker returned no search status");
                    query.failFromWorker(failure);
                    complete(null, failure);
                    return;
                }
                if (status == ResumableSearch.Status.RUNNING) {
                    if (query.recoveryPreparationPending) {
                        query.preparePendingRecovery();
                        resume();
                        return;
                    }
                    if (query.needsDependencyResolution()) {
                        query.requestPendingSections();
                        if (query.waitingForBuild()) return;
                    }
                    int completedBudget = query.longContinuation
                            ? MAX_LONG_SEARCH_NODES : MAX_FAST_SEARCH_NODES;
                    if (query.lastWorkerExpanded >= completedBudget
                            && !query.needsDependencyResolution()) {
                        query.longContinuation = true;
                    }
                    resume();
                    return;
                }
                query.finishOnOwner();
                complete(
                        status == ResumableSearch.Status.SUCCEEDED ? query.result : null,
                        null
                );
            } catch (Throwable failure) {
                if (failure instanceof VirtualMachineError fatal) {
                    throw fatal;
                }
                if (failure instanceof ThreadDeath fatal) {
                    throw fatal;
                }
                failSearch(failure);
            }
        }

        private void failSearch(Throwable searchFailure) {
            if (staleTopologyFailure(searchFailure)) {
                query.restartStaleSearch();
                complete(null, null);
            } else {
                query.failFromWorker(searchFailure);
                complete(null, searchFailure);
            }
        }

        private void fallbackToBase() {
            requireRuntimeLock();
            if (!macroFlights.remove(key, this)) return;
            query.finishOnOwner();
            progress.value = query.progress();
            List<MacroRequest> fallback = List.copyOf(waiters);
            waiters.clear();
            for (MacroRequest request : fallback) {
                request.flight = null;
                request.beginParentFallback();
                try {
                    joinCanonical(request, key.starts, key.goals, false);
                } catch (VirtualMachineError | ThreadDeath fatal) {
                    throw fatal;
                } catch (Throwable failure) {
                    request.finishExceptionally(failure);
                }
            }
        }

        private void add(MacroRequest waiter) {
            waiters.add(waiter);
            waiter.flight = this;
            RequestProgress waiterProgress = waiter.progress;
            waiter.progress = new RequestProgress(waiterProgress.completed, progress);
            updatePriority();
        }

        private void remove(MacroRequest waiter) {
            if (!waiters.remove(waiter)) {
                return;
            }
            if (waiters.isEmpty()) {
                macroFlights.remove(key, this);
                stopSearch();
            } else {
                updatePriority();
            }
        }

        private void cancel() {
            requireRuntimeLock();
            if (query == null) {
                macroFlights.remove(key, this);
                return;
            }
            macroFlights.remove(key, this);
            stopSearch();
        }

        private void stopSearch() {
            if (query == null) return;
            query.requestCancel();
            TopologyTaskExecutor.TaskHandle task = searchTask;
            if (task == UNTRACKED_TASK) {
                if (!query.workerRunning) query.finishOnOwner();
                return;
            }
            if (task == SUBMITTING_TASK) {
                return;
            }
            cancelQueuedTask(task, cancelled -> {
                if (!cancelled || searchTask != task) return;
                searchTask = UNTRACKED_TASK;
                query.finishOnOwner();
            });
        }

        private void updatePriority() {
            NavigationScheduler.Priority requested = waiters.stream()
                    .map(waiter -> waiter.priority)
                    .reduce(NavigationScheduler.Priority.BACKGROUND,
                            TopologyWorkerRuntime::higherPriority);
            if (requested == priority) {
                return;
            }
            priority = requested;
            if (query != null) {
                query.reprioritize(priority);
                reprioritizeTask(searchTask, priority);
            }
        }

        private void complete(@Nullable MacroSearch.Corridor corridor,
                              @Nullable Throwable searchFailure) {
            requireRuntimeLock();
            if (!macroFlights.remove(key, this)) {
                return;
            }
            List<MacroRequest> completing = List.copyOf(waiters);
            waiters.clear();
            completing.forEach(waiter -> waiter.flight = null);
            List<SectionStamp> stamps;
            try {
                query.finishMeasurement();
                progress.value = query.progress();
                stamps = corridor == null ? List.of() : query.resultStamps();
                if (searchFailure == null && corridor != null
                        && !query.resultCurrentAtWorkerCompletion()) {
                    progress.value = progress.value.withOutcome(Status.FAILED,
                            MacroSearch.Failure.STALE_WORLD, progress.value.blockedSection());
                    completing.forEach(TopologyWorkerRuntime.this::retryStaleRequest);
                    return;
                }
                if (searchFailure == null && corridor != null) {
                    cacheCompletedCorridor(key, corridor, stamps,
                            query.resultRetainedBytes());
                }
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable failure) {
                completing.forEach(waiter -> waiter.finishExceptionally(failure));
                return;
            }
            for (MacroRequest waiter : completing) {
                if (searchFailure != null) {
                    waiter.finishExceptionally(searchFailure);
                } else if (corridor == null) {
                    if (query.failure == MacroSearch.Failure.STALE_WORLD) {
                        retryStaleRequest(waiter);
                    }
                    else {
                        waiter.finish(null, query.failure, query.blockedSection(), stamps);
                    }
                }
            }
            if (searchFailure == null && corridor != null) {
                finishCorridors(completing, corridor, stamps);
            }
        }
    }

    private void cacheCompletedCorridor(MacroQueryKey key,
                                        MacroSearch.Corridor corridor,
                                        List<SectionStamp> stamps,
                                        long bytes) {
        if (bytes > MAX_COMPLETED_CORRIDOR_BYTES) {
            return;
        }
        CachedCorridor previous = completedCorridors.put(
                key,
                new CachedCorridor(corridor, List.copyOf(stamps), bytes)
        );
        if (previous != null) {
            completedCorridorBytes -= previous.retainedBytes;
        }
        completedCorridorBytes += bytes;
        recordCorridorCachePeak();
        while (completedCorridors.size() > MAX_COMPLETED_CORRIDORS
                || completedCorridorBytes > MAX_COMPLETED_CORRIDOR_BYTES) {
            Iterator<Map.Entry<MacroQueryKey, CachedCorridor>> iterator =
                    completedCorridors.entrySet().iterator();
            if (!iterator.hasNext()) {
                break;
            }
            CachedCorridor evicted = iterator.next().getValue();
            iterator.remove();
            completedCorridorBytes -= evicted.retainedBytes;
        }
    }

    private static long estimateCorridorBytes(MacroSearch.Corridor corridor) {
        long bytes = 96L + corridor.endpoints().size() * 56L
                + corridor.connections().size() * 72L;
        for (MacroSearch.Connection connection : corridor.connections()) {
            if (connection.transition() instanceof MacroSearch.BoundaryTransition boundary) {
                bytes += boundary.retainedBytes();
            }
        }
        return bytes;
    }

    private static long candidateSignature(List<MacroComponentKey> candidates) {
        long hash = 0xcbf29ce484222325L;
        for (MacroComponentKey candidate : candidates) {
            hash = (hash ^ candidate.section.asLong()) * 0x100000001b3L;
            hash = (hash ^ candidate.componentId) * 0x100000001b3L;
            hash = (hash ^ candidate.signature) * 0x100000001b3L;
        }
        return hash;
    }

    private static MacroSearch.Corridor rematerializeCorridor(
            MacroSearch.Corridor corridor,
            BlockPos startPosition,
            BlockPos goalPosition) {
        List<MacroSearch.Endpoint> endpoints = new ArrayList<>(corridor.endpoints());
        MacroSearch.ExactEndpoint oldStart = (MacroSearch.ExactEndpoint) endpoints.get(0);
        MacroSearch.ExactEndpoint oldGoal =
                (MacroSearch.ExactEndpoint) endpoints.get(endpoints.size() - 1);
        endpoints.set(0, new MacroSearch.ExactEndpoint(
                oldStart.id(),
                startPosition,
                oldStart.revision()
        ));
        endpoints.set(endpoints.size() - 1, new MacroSearch.ExactEndpoint(
                oldGoal.id(),
                goalPosition,
                oldGoal.revision()
        ));
        List<MacroSearch.Connection> connections = new ArrayList<>(corridor.connections());
        for (int index : connections.size() == 1
                ? new int[]{0}
                : new int[]{0, connections.size() - 1}) {
            MacroSearch.Connection connection = connections.get(index);
            connections.set(index, new MacroSearch.Connection(
                    connection.id(),
                    endpoints.get(index),
                    endpoints.get(index + 1),
                    connection.lowerBound(),
                    connection.transition()
            ));
        }
        return new MacroSearch.Corridor(endpoints, connections, corridor.cost());
    }

    private static int directComponent(
            BaseClusterTopology topology,
            BlockPos position,
            BaseClusterTopology.Channel channel) {
        if (topology.geometry().channel() != channel) return -1;
        int x = Math.floorMod(position.getX(), BaseClusterTopology.SIDE);
        int y = Math.floorMod(position.getY(), BaseClusterTopology.SIDE);
        int z = Math.floorMod(position.getZ(), BaseClusterTopology.SIDE);
        return topology.componentAt(x, y, z);
    }

    private static List<BlockPos> candidateAnchors(BlockPos center) {
        List<BlockPos> result = new ArrayList<>(25);
        for (int distance = 0; distance <= 2; distance++) {
            for (int dy = -distance; dy <= distance; dy++) {
                for (int dz = -distance; dz <= distance; dz++) {
                    int dx = distance - Math.abs(dy) - Math.abs(dz);
                    if (dx < 0) continue;
                    if (dx == 0) {
                        result.add(center.offset(0, dy, dz));
                    } else {
                        result.add(center.offset(-dx, dy, dz));
                        result.add(center.offset(dx, dy, dz));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<SectionPos> candidateSections(BlockPos center) {
        Set<SectionPos> sections = new HashSet<>();
        candidateAnchors(center).forEach(anchor -> sections.add(SectionPos.of(anchor)));
        return sections.stream().sorted(Comparator.comparingLong(SectionPos::asLong)).toList();
    }

    private static boolean shouldUseSuperGraph(List<MacroComponentKey> starts,
                                               List<MacroComponentKey> goals) {
        int maximum = 0;
        for (MacroComponentKey start : starts) {
            SectionPos startParent = SuperClusterTopology.originOf(start.section);
            for (MacroComponentKey goal : goals) {
                SectionPos goalParent = SuperClusterTopology.originOf(goal.section);
                maximum = Math.max(maximum, Math.max(
                        Math.abs(startParent.x() - goalParent.x()),
                        Math.max(Math.abs(startParent.y() - goalParent.y()),
                                Math.abs(startParent.z() - goalParent.z()))
                ) / SuperClusterTopology.CHILDREN_PER_AXIS);
            }
        }
        return maximum >= MIN_SUPER_CLUSTER_DISTANCE;
    }

    private boolean endpointParentsAvailable(ResourceKey<Level> dimension,
                                             List<MacroComponentKey> candidates) {
        for (MacroComponentKey candidate : candidates) {
            if (!superClusterAvailable(
                    dimension, SuperClusterTopology.originOf(candidate.section))) return false;
        }
        return true;
    }

    private static boolean staleTopologyFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof StaleTopologyException) {
                return true;
            }
        }
        return false;
    }

    private static Throwable rootFailure(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    private record MacroComponentKey(SectionPos section, int componentId, long signature) {
        private static final Comparator<MacroComponentKey> ORDER =
                Comparator.comparingLong((MacroComponentKey key) -> key.section.asLong())
                        .thenComparingInt(MacroComponentKey::componentId)
                        .thenComparingLong(MacroComponentKey::signature);
    }

    private record CandidateResolution(List<MacroComponentKey> candidates,
                                       @Nullable SectionPos unavailable,
                                       @Nullable MacroSearch.Failure unavailableFailure) {
        private CandidateResolution {
            candidates = List.copyOf(candidates);
            if ((unavailable == null) != (unavailableFailure == null)) {
                throw new IllegalArgumentException("endpoint unavailability is incomplete");
            }
        }

    }

    private record RawQueryKey(ResourceKey<Level> dimension, BlockPos start, BlockPos goal,
                               BaseClusterTopology.Channel channel,
                               BaseClusterTopology.TraversalProfile profile, int weightBits) {
    }

    private record EndpointTopology(BaseClusterTopology topology,
                                    ViewEntry owner,
                                    long validity) {
    }

    private static final class ResolvedDependency {
        private final MacroSearch.DependencyKey dependency;
        private final Object cacheKey;
        private final Object value;
        private final Object owner;
        private final Object stamps;
        private final long validity;
        private final MacroSearch.Failure unavailability;
        private volatile boolean transferred;

        private ResolvedDependency(MacroSearch.DependencyKey dependency, Object cacheKey,
                                   Object value, Object owner, Object stamps,
                                   long validity, MacroSearch.Failure unavailability) {
            this.dependency = dependency;
            this.cacheKey = cacheKey;
            this.value = value;
            this.owner = owner;
            this.stamps = stamps;
            this.validity = validity;
            this.unavailability = unavailability;
        }

        private static ResolvedDependency unavailable(MacroSearch.DependencyKey dependency,
                                                      MacroSearch.Failure reason) {
            return new ResolvedDependency(dependency, null, null, null, null, 0L, reason);
        }
    }

    private record MacroQueryKey(ResourceKey<Level> dimension,
                                 BaseClusterTopology.GeometryKey geometry,
                                 BaseClusterTopology.MovementKey movement,
                                 boolean hierarchical,
                                 int weightBits,
                                 int nodeBudget,
                                 List<MacroComponentKey> starts,
                                 List<MacroComponentKey> goals) {
        private MacroQueryKey {
            starts = List.copyOf(starts);
            goals = List.copyOf(goals);
        }

        private MacroQueryKey(ResourceKey<Level> dimension,
                              BaseClusterTopology.Channel channel,
                              BaseClusterTopology.TraversalProfile profile,
                              boolean hierarchical,
                              int weightBits,
                              int nodeBudget,
                              List<MacroComponentKey> starts,
                              List<MacroComponentKey> goals) {
            this(dimension, profile.geometry(channel), profile.movement(channel), hierarchical,
                    weightBits, nodeBudget, List.copyOf(starts), List.copyOf(goals));
        }
    }

    private record CachedCorridor(MacroSearch.Corridor corridor,
                                  List<SectionStamp> stamps,
                                  long retainedBytes) {
    }

    private record PrewarmKey(ResourceKey<Level> dimension,
                              long chunkLong) {
    }

    private static final class PrewarmCandidate {
        private final PrewarmKey key;
        private final long loadIdentity;
        private final List<Integer> sections;
        private int cursor;

        private PrewarmCandidate(PrewarmKey key, long loadIdentity, List<Integer> sections) {
            this.key = key;
            this.loadIdentity = loadIdentity;
            this.sections = List.copyOf(sections);
            this.cursor = sections.size() - 1;
        }

        @Nullable
        private Integer currentSection() {
            return cursor < 0 ? null : sections.get(cursor);
        }

        private void advance() {
            cursor--;
        }

        private boolean hasNext() {
            return cursor >= 0;
        }
    }

    /** Coordinates section availability around one pure, resumable macro search. */
    private final class MacroQuery {
        private final ResourceKey<Level> dimension;
        private final BlockPos startPosition;
        private final BlockPos goalPosition;
        private final BaseClusterTopology.Channel channel;
        private final BaseClusterTopology.TraversalProfile profile;
        private NavigationScheduler.Priority priority;
        private final float weight;
        private final Runnable runtimeContinuation;
        private final long submittedNanos = System.nanoTime();
        private boolean hierarchical;
        private final List<MacroComponentKey> startCandidates;
        private final List<MacroComponentKey> goalCandidates;
        private final Map<MacroSearch.DependencyKey, TopologyWaiter<?>> requests = new HashMap<>();
        private Map<MacroSearch.DependencyKey, ResolvedDependency> resolvedDependencies =
                new HashMap<>();
        private volatile List<MacroSearch.DependencyKey> pendingDependencies = List.of();

        private volatile Status status = Status.RUNNING;
        private MacroSearch.Failure failure = MacroSearch.Failure.NONE;
        private MacroSearch search;
        private MacroSearch superSearch;
        private SuperTopologyGraph superGraph;
        private TopologyGraph baseGraph;
        private boolean refining;
        private MacroSearch.Corridor result;
        private Throwable failureCause;
        private MacroSearch.Corridor aggregateCorridor;
        private int witnessConnectionIndex;
        private MacroSearch.Endpoint recoveryCurrent;
        private AggregateWitness pendingWitness;
        private MacroSearch.Connection pendingAggregateConnection;
        private long recoveryEndpointSequence = 0x1000_0000L;
        private final List<MacroSearch.Endpoint> recoveredEndpoints = new ArrayList<>();
        private final List<MacroSearch.Connection> recoveredConnections = new ArrayList<>();
        private float recoveredCost;
        private MacroSearch.Metrics completedSearchMetrics;
        private long workerTasks;
        private long dependencyWaits;
        private int witnessSegments;
        private SectionPos blockedEndpoint;
        private Throwable buildFailure;
        private boolean longContinuation;
        private volatile int lastWorkerExpanded;
        private volatile boolean cancelRequested;
        private boolean parentFallbackPending;
        private volatile boolean workerRunning;
        private volatile boolean recoveryPreparationPending;
        private MacroSearch.Endpoint pendingRecoveryFrom;
        private SectionPos pendingRecoveryOrigin;
        private int pendingRecoveryAggregate;
        private SectionPos recoveryAggregateOrigin;
        private int recoveryAggregate;
        private MacroComponentKey pendingRecoveryTarget;
        private boolean pendingRecoveryFinalGoal;
        private PreparedWitness preparedWitness;
        private final List<TopologyGraph> deferredBaseGraphs = new ArrayList<>();
        private final List<SuperTopologyGraph> deferredSuperGraphs = new ArrayList<>();
        private final Map<ClusterKey, SectionStamp> usedSections = new HashMap<>();
        private List<SectionStamp> preparedResultStamps = List.of();
        private boolean preparedResultCurrent;
        private long preparedResultRetainedBytes;
        private long firstWorkerNanos;
        private long queueNanos;
        private long wallNanos;
        private boolean measurementFinished;

        private MacroQuery(ResourceKey<Level> dimension,
                           BlockPos startPosition,
                           BlockPos goalPosition,
                           BaseClusterTopology.Channel channel,
                           BaseClusterTopology.TraversalProfile profile,
                           NavigationScheduler.Priority priority,
                           float weight,
                           MacroQueryKey key,
                           Runnable runtimeContinuation) {
            if (!Float.isFinite(weight) || weight < 1.0F) {
                throw new IllegalArgumentException("weight must be finite and at least 1.0");
            }
            this.dimension = dimension;
            this.startPosition = startPosition;
            this.goalPosition = goalPosition;
            this.channel = channel;
            this.profile = profile;
            this.priority = priority;
            this.weight = weight;
            this.runtimeContinuation = Objects.requireNonNull(runtimeContinuation,
                    "runtimeContinuation");
            this.hierarchical = key.hierarchical;
            this.startCandidates = key.starts;
            this.goalCandidates = key.goals;
        }

        private boolean prepareForWorker() {
            requireRuntimeLock();
            if (status != Status.RUNNING) {
                return false;
            }
            if (cancelRequested) {
                status = Status.FAILED;
                failure = MacroSearch.Failure.CANCELLED;
                clearRequests();
                releaseGraphs();
                return false;
            }
            if (parentFallbackPending) {
                return false;
            }
            if (buildFailure != null) {
                status = Status.FAILED;
                Throwable root = rootFailure(buildFailure);
                if (root instanceof FactsRecoveryException facts) {
                    failure = MacroSearch.Failure.FACTS_RECOVERY_FAILED;
                    blockedEndpoint = facts.key.section();
                } else if (staleTopologyFailure(root)) failure = MacroSearch.Failure.STALE_WORLD;
                else failureCause = root;
                clearRequests();
                releaseGraphs();
                return false;
            }
            if (search == null && !refining && !prepareEndpoints()) {
                return false;
            }
            if (search == null && baseGraph == null && superGraph == null) {
                prepareSearchGraph();
            }
            return true;
        }

        private void prepareSearchGraph() {
            if (hierarchical && !refining) {
                superGraph = new SuperTopologyGraph(
                        dimension,
                        startPosition,
                        goalPosition,
                        channel,
                        profile,
                        startCandidates,
                        goalCandidates
                );
            } else {
                baseGraph = new TopologyGraph(
                        dimension, startPosition, goalPosition,
                        channel, profile, startCandidates, goalCandidates);
            }
        }

        private void createSearchOnWorker() {
            int maxVisitedNodes = queryNodeBudget(startPosition, goalPosition, hierarchical);
            if (refining) {
                baseGraph.initializeBindings();
                search = new MacroSearch(new AggregateTopologyGraph(baseGraph, superGraph,
                        recoveryAggregateOrigin, recoveryAggregate), weight,
                        MAX_LOCAL_WITNESS_NODES);
            } else if (hierarchical) {
                superGraph.initializeBindings();
                search = new MacroSearch(superGraph, weight, maxVisitedNodes);
            } else {
                baseGraph.initializeBindings();
                search = new MacroSearch(baseGraph, weight, maxVisitedNodes);
            }
        }

        private Status runWorkerSlice(int expansionBudget) {
            if (status != Status.RUNNING) {
                return status;
            }
            markPhysicalSearchStarted();
            workerTasks++;
            List<ResolvedDependency> resolutions = peekResolvedDependencies();
            if (cancelRequested) {
                runRuntimeTransition(() -> resolutions.forEach(dependency -> {
                    if (resolvedDependencies.remove(dependency.dependency, dependency)) {
                        adjustLiveSearchDependencies(-1);
                        releaseResolved(dependency);
                    }
                }));
                status = Status.FAILED;
                failure = MacroSearch.Failure.CANCELLED;
                return status;
            }
            long expandedBefore = expansionWorkUnits();
            workerRunning = true;
            try {
                if (search == null) createSearchOnWorker();
                applyResolvedDependencies(resolutions);
                Status resultStatus = stepSearch(expansionBudget, expandedBefore);
                if (status == Status.RUNNING && search != null
                        && search.waitingForTopology()) {
                    pendingDependencies = mergePendingDependencies(
                            pendingDependencies,
                            search.pendingDependencies(MAX_LIVE_SEARCH_DEPENDENCIES));
                } else {
                    pendingDependencies = List.of();
                }
                dependencyWaits += pendingDependencies.size();
                if (cancelRequested) {
                    status = Status.FAILED;
                    failure = MacroSearch.Failure.CANCELLED;
                }
                return resultStatus == Status.RUNNING && status != Status.RUNNING
                        ? status : resultStatus;
            } finally {
                long expandedAfter = expansionWorkUnits();
                lastWorkerExpanded = (int) Math.min(Integer.MAX_VALUE,
                        Math.max(0L, expandedAfter - expandedBefore));
                workerRunning = false;
            }
        }

        private Status stepSearch(int expansionBudget, long expandedBefore) {
            if (!workerRunning) {
                throw new IllegalStateException("macro search must run on a topology worker");
            }
            if (status != Status.RUNNING) {
                return status;
            }
            if (search == null) {
                throw new IllegalStateException("worker search started without a prepared graph");
            }
            if (refining) {
                return stepWitnessRecovery(expansionBudget, expandedBefore);
            }

            Status searchStatus = search.step(expansionBudget);
                if (searchStatus == Status.SUCCEEDED) {
                    MacroSearch.Corridor candidate = search.result();
                    if (hierarchical && !refining) {
                        if (candidate == null || superGraph == null || !superGraph.revisionsValid()) {
                            restartStaleSearch();
                        } else {
                            superSearch = search;
                            search = null;
                            beginWitnessRecovery(candidate);
                            refining = true;
                            return stepWitnessRecovery(expansionBudget, expandedBefore);
                        }
                    } else if (candidate != null) {
                        result = candidate;
                        status = Status.SUCCEEDED;
                        clearRequests();
                        releaseGraphs();
                    } else {
                        restartStaleSearch();
                    }
                } else if (searchStatus == Status.FAILED) {
                    if (search.failure() == MacroSearch.Failure.STALE_WORLD) {
                        restartStaleSearch();
                    } else {
                        failure = search.failure();
                        status = Status.FAILED;
                        clearRequests();
                        releaseGraphs();
                    }
                }
            return status;
        }

        private void beginWitnessRecovery(MacroSearch.Corridor corridor) {
            aggregateCorridor = corridor;
            witnessConnectionIndex = 0;
            recoveryCurrent = corridor.endpoints().get(0);
            pendingWitness = null;
            pendingAggregateConnection = null;
            recoveredEndpoints.clear();
            recoveredConnections.clear();
            recoveredCost = 0.0F;
        }

        private Status stepWitnessRecovery(int expansionBudget, long expandedBefore) {
            while (true) {
                if (search != null) {
                    long consumed = Math.max(0L, expansionWorkUnits() - expandedBefore);
                    int remaining = (int) Math.max(0L, expansionBudget - consumed);
                    if (remaining == 0) {
                        return status;
                    }
                    Status searchStatus;
                    try {
                        searchStatus = search.step(remaining);
                    } catch (StaleTopologyException stale) {
                        restartStaleSearch();
                        return status;
                    }
                    if (searchStatus == Status.RUNNING) {
                        return status;
                    }
                    if (searchStatus == Status.FAILED) {
                        if (search.failure() == MacroSearch.Failure.STALE_WORLD) {
                            restartStaleSearch();
                        } else {
                            return failWitness(search.failure());
                        }
                        return status;
                    }
                    MacroSearch.Corridor segment = search.result();
                    if (segment == null) {
                        return failWitness(MacroSearch.Failure.NO_STRUCTURAL_ROUTE);
                    }
                    completedSearchMetrics = completedSearchMetrics == null
                            ? search.metrics()
                            : completedSearchMetrics.plus(search.metrics());
                    appendSegment(segment);
                    baseGraph.collectStamps(usedSections);
                    deferredBaseGraphs.add(baseGraph);
                    baseGraph = null;
                    search = null;
                    if (pendingWitness != null) {
                        PreparedWitness prepared = Objects.requireNonNull(preparedWitness,
                                "witness handoff was not prepared under the runtime lock");
                        appendWitness(prepared);
                        pendingWitness = null;
                        pendingAggregateConnection = null;
                        preparedWitness = null;
                    }
                    continue;
                }
                if (aggregateCorridor == null
                        || witnessConnectionIndex >= aggregateCorridor.connections().size()) {
                    result = new MacroSearch.Corridor(
                            recoveredEndpoints,
                            recoveredConnections,
                            recoveredCost
                    );
                    status = Status.SUCCEEDED;
                    clearRequests();
                    releaseGraphs();
                    refining = false;
                    aggregateCorridor = null;
                    return status;
                }
                MacroSearch.Connection connection = aggregateCorridor.connections()
                        .get(witnessConnectionIndex);
                if (connection.transition() instanceof MacroSearch.MembershipTransition) {
                    if (connection.to() instanceof MacroSearch.ExactEndpoint) {
                        MacroSearch.AggregateEndpoint aggregate =
                                connection.from() instanceof MacroSearch.AggregateEndpoint endpoint
                                        ? endpoint : null;
                        if (aggregate == null) {
                            return failWitness(MacroSearch.Failure.NO_STRUCTURAL_ROUTE);
                        }
                        boolean prepared = beginLocalRecovery(
                                recoveryCurrent,
                                aggregate.origin(),
                                aggregate.aggregateId(),
                                null,
                                true
                        );
                        witnessConnectionIndex++;
                        if (!prepared) {
                            return status;
                        }
                    } else {
                        // The initial exact-to-aggregate membership edge is a
                        // zero-cost witness marker; it still consumes this
                        // aggregate-corridor connection.
                        witnessConnectionIndex++;
                    }
                    continue;
                }
                if (!(connection.transition() instanceof MacroSearch.AggregateTransition)) {
                    return failWitness(MacroSearch.Failure.NO_STRUCTURAL_ROUTE);
                }
                AggregateWitness witness = superGraph == null
                        ? null : superGraph.witness(connection.id());
                if (witness == null) {
                    return failWitness(MacroSearch.Failure.NO_STRUCTURAL_ROUTE);
                }
                boolean prepared = beginLocalRecovery(
                        recoveryCurrent,
                        SuperClusterTopology.originOf(witness.source().section()),
                        aggregateId(witness.source()),
                        witness.source(),
                        false
                );
                pendingWitness = witness;
                pendingAggregateConnection = connection;
                witnessConnectionIndex++;
                if (!prepared) {
                    return status;
                }
            }
        }
        private Status failWitness(MacroSearch.Failure reason) {
            failure = reason;
            status = Status.FAILED;
            clearRequests();
            releaseGraphs();
            return status;
        }
        private int aggregateId(MacroComponentKey component) {
            if (superGraph == null) return -1;
            SuperClusterTopology topology = superGraph.capturedTopology(
                    SuperClusterTopology.originOf(component.section()));
            return topology == null ? -1 : topology.aggregateId(component.section(), component.componentId());
        }
        private boolean beginLocalRecovery(MacroSearch.Endpoint from, SectionPos aggregateOrigin,
                                           int aggregate, @Nullable MacroComponentKey target,
                                           boolean finalGoal) {
            if (aggregate < 0) throw new StaleTopologyException("aggregate witness mapping changed");
            if (workerRunning) {
                recoveryPreparationPending = true;
                pendingRecoveryFrom = from;
                pendingRecoveryOrigin = aggregateOrigin;
                pendingRecoveryAggregate = aggregate;
                pendingRecoveryTarget = target;
                pendingRecoveryFinalGoal = finalGoal;
                return false;
            }
            requireRuntimeLock();
            List<MacroComponentKey> starts = from instanceof MacroSearch.ComponentEndpoint component
                    ? List.of(new MacroComponentKey(component.section(), component.componentId(),
                    component.revision()))
                    : startCandidates;
            List<MacroComponentKey> goals = finalGoal ? goalCandidates
                    : List.of(Objects.requireNonNull(target));
            BlockPos goalAnchor = finalGoal ? goalPosition : componentAnchorFor(target);
            MacroSearch.Endpoint goalEndpoint = finalGoal
                    ? new MacroSearch.ExactEndpoint(1L, goalAnchor, candidateSignature(goals))
                    : new MacroSearch.ComponentEndpoint(
                            recoveryEndpointSequence++,
                            goalAnchor,
                            Objects.requireNonNull(target).signature(),
                            target.section(),
                            channel,
                            target.componentId()
                    );
            baseGraph = new TopologyGraph(dimension, from, goalEndpoint, channel, profile,
                    starts, goals);
            recoveryAggregateOrigin = aggregateOrigin;
            recoveryAggregate = aggregate;
            return true;
        }

        private void preparePendingRecovery() {
            requireRuntimeLock();
            if (!recoveryPreparationPending) {
                return;
            }
            MacroSearch.Endpoint from = pendingRecoveryFrom;
            SectionPos origin = pendingRecoveryOrigin;
            int aggregate = pendingRecoveryAggregate;
            MacroComponentKey target = pendingRecoveryTarget;
            boolean finalGoal = pendingRecoveryFinalGoal;
            recoveryPreparationPending = false;
            pendingRecoveryFrom = null;
            pendingRecoveryOrigin = null;
            pendingRecoveryTarget = null;
            BlockPos sourceAnchor = null;
            MacroSearch.ComponentEndpoint targetEndpoint = null;
            if (pendingWitness != null) {
                sourceAnchor = componentAnchorFor(pendingWitness.source());
                targetEndpoint = componentEndpoint(pendingWitness.target());
            }
            beginLocalRecovery(from, origin, aggregate, target, finalGoal);
            if (pendingWitness != null) {
                preparedWitness = new PreparedWitness(
                        Objects.requireNonNull(sourceAnchor),
                        Objects.requireNonNull(targetEndpoint),
                        Objects.requireNonNull(pendingAggregateConnection)
                );
            }
        }

        private BlockPos componentAnchorFor(MacroComponentKey component) {
            requireRuntimeLock();
            BaseClusterTopology topology = TopologyWorkerRuntime.this.topology(
                    new ClusterKey(dimension, component.section()), profile.geometry(channel));
            if (topology == null)
                throw new StaleTopologyException("witness component topology is unavailable");
            return componentAnchor(component.section(), topology, component.componentId());
        }

        private void appendSegment(MacroSearch.Corridor segment) {
            witnessSegments++;
            if (recoveredEndpoints.isEmpty()) {
                recoveredEndpoints.addAll(segment.endpoints());
                recoveredConnections.addAll(segment.connections());
                recoveredCost += segment.cost();
                recoveryCurrent = recoveredEndpoints.get(recoveredEndpoints.size() - 1);
                return;
            }
            List<MacroSearch.Endpoint> endpoints = segment.endpoints();
            for (int index = 1; index < endpoints.size(); index++) {
                recoveredEndpoints.add(endpoints.get(index));
            }
            for (int index = 0; index < segment.connections().size(); index++) {
                MacroSearch.Connection connection = segment.connections().get(index);
                MacroSearch.Endpoint from = index == 0
                        ? recoveredEndpoints.get(recoveredEndpoints.size()
                        - segment.connections().size() - 1)
                        : connection.from();
                recoveredConnections.add(new MacroSearch.Connection(connection.id(), from,
                        connection.to(), connection.lowerBound(), connection.transition()));
            }
            recoveredCost += segment.cost();
            recoveryCurrent = recoveredEndpoints.get(recoveredEndpoints.size() - 1);
        }

        private void appendWitness(PreparedWitness prepared) {
            MacroSearch.Endpoint source = recoveryCurrent;
            MacroSearch.ComponentEndpoint target = prepared.target();
            if (!source.anchor().equals(prepared.sourceAnchor()))
                throw new IllegalStateException("local witness recovery ended at wrong component");
            recoveredConnections.add(new MacroSearch.Connection(edgeId(source, target), source,
                    target, prepared.aggregateConnection().lowerBound(),
                    new MacroSearch.LocalTransition()));
            recoveredEndpoints.add(target);
            recoveredCost += prepared.aggregateConnection().lowerBound();
            recoveryCurrent = target;
        }

        private MacroSearch.ComponentEndpoint componentEndpoint(MacroComponentKey component) {
            requireRuntimeLock();
            return new MacroSearch.ComponentEndpoint(recoveryEndpointSequence++,
                    componentAnchorFor(component), component.signature(), component.section(),
                    channel, component.componentId());
        }

        private void restartStaleSearch() {
            if (search != null) {
                completedSearchMetrics = completedSearchMetrics == null
                        ? search.metrics() : completedSearchMetrics.plus(search.metrics());
            }
            clearRequests();
            releaseGraphs();
            usedSections.clear();
            search = null;
            result = null;
            status = Status.FAILED;
            failure = MacroSearch.Failure.STALE_WORLD;
        }

        private void releaseGraphs() {
            if (baseGraph != null) baseGraph.collectStamps(usedSections);
            if (superGraph != null) superGraph.collectStamps(usedSections);
            if (workerRunning) {
                if (baseGraph != null) {
                    deferredBaseGraphs.add(baseGraph);
                    baseGraph = null;
                }
                if (superGraph != null) {
                    deferredSuperGraphs.add(superGraph);
                    superGraph = null;
                }
                return;
            }
            if (baseGraph != null) {
                baseGraph.close();
                baseGraph = null;
            }
            if (superGraph != null) {
                superGraph.close();
                superGraph = null;
            }
        }

        private void prepareWorkerResult() {
            if (result == null) {
                throw new IllegalStateException("successful macro search has no corridor");
            }
            preparedResultStamps = usedSections.values().stream()
                    .sorted(Comparator.comparingLong(stamp -> stamp.key().section().asLong()))
                    .toList();
            if (preparedResultStamps.isEmpty()) {
                throw new IllegalStateException("successful macro search has no fact stamps");
            }
            preparedResultCurrent = isCurrent(preparedResultStamps);
            preparedResultRetainedBytes = estimateCorridorBytes(result)
                    + retainedStampBytes(preparedResultStamps);
        }

        private List<SectionStamp> resultStamps() {
            return preparedResultStamps;
        }

        private boolean resultCurrentAtWorkerCompletion() {
            return preparedResultCurrent;
        }

        private long resultRetainedBytes() {
            return preparedResultRetainedBytes;
        }

        private void drainDeferredGraphs() {
            requireRuntimeLock();
            for (TopologyGraph graph : deferredBaseGraphs) {
                graph.close();
            }
            deferredBaseGraphs.clear();
            for (SuperTopologyGraph graph : deferredSuperGraphs) {
                graph.close();
            }
            deferredSuperGraphs.clear();
        }

        private boolean prepareEndpoints() {
            boolean ready = true;
            if (hierarchical && !refining) {
                Set<SectionPos> endpointParents = new HashSet<>();
                startCandidates.forEach(candidate -> endpointParents.add(
                        SuperClusterTopology.originOf(candidate.section)));
                goalCandidates.forEach(candidate -> endpointParents.add(
                        SuperClusterTopology.originOf(candidate.section)));
                for (SectionPos origin : endpointParents.stream()
                        .sorted(Comparator.comparingLong(SectionPos::asLong)).toList()) {
                    SuperCacheKey key = new SuperCacheKey(
                            dimension,
                            origin,
                            channel,
                            profile
                    );
                    if (superTopology(key) != null) {
                        continue;
                    }
                    ready = false;
                    if (!superClusterAvailable(dimension, origin)) {
                        parentFallbackPending = true;
                        return false;
                    }
                    requestDependency(MacroSearch.DependencyKey.superCluster(origin));
                }
                return ready;
            }

            Set<SectionPos> endpointSections = new HashSet<>();
            startCandidates.forEach(candidate -> endpointSections.add(candidate.section));
            goalCandidates.forEach(candidate -> endpointSections.add(candidate.section));
            for (SectionPos section : endpointSections.stream()
                    .sorted(Comparator.comparingLong(SectionPos::asLong)).toList()) {
                ClusterKey key = new ClusterKey(dimension, section);
                if (topology(key, profile.geometry(channel)) != null) {
                    continue;
                }
                ready = false;
                if (!clusterLoaded(key)) {
                    failure = MacroSearch.Failure.UNAVAILABLE_CHUNK;
                    blockedEndpoint = section;
                    status = Status.FAILED;
                    clearRequests();
                    return false;
                }
                requestDependency(new MacroSearch.DependencyKey(
                        MacroSearch.DependencyKind.BASE_CLUSTER, section));
            }
            return ready;
        }

        private void requestPendingSections() {
            List<MacroSearch.DependencyKey> pending = pendingDependencies;
            List<MacroSearch.DependencyKey> remaining = new ArrayList<>();
            for (MacroSearch.DependencyKey key : pending) {
                if (requests.containsKey(key) || resolvedDependencies.containsKey(key)) continue;
                if (liveDependencyCount() >= MAX_LIVE_SEARCH_DEPENDENCIES) {
                    remaining.add(key);
                    continue;
                }
                ResolvedDependency ready = captureDependency(key);
                if (ready != null) retainResolvedDependency(key, ready);
                else if (dependencyAvailableInWorld(key)) requestDependency(key);
                else retainResolvedDependency(key, ResolvedDependency.unavailable(
                        key, MacroSearch.Failure.UNAVAILABLE_CHUNK));
            }
            pendingDependencies = List.copyOf(remaining);
        }

        private static List<MacroSearch.DependencyKey> mergePendingDependencies(
                List<MacroSearch.DependencyKey> retained,
                List<MacroSearch.DependencyKey> discovered) {
            if (retained.isEmpty()) return List.copyOf(discovered);
            LinkedHashSet<MacroSearch.DependencyKey> merged = new LinkedHashSet<>(discovered);
            merged.addAll(retained);
            if (merged.size() <= MAX_LIVE_SEARCH_DEPENDENCIES) {
                return List.copyOf(merged);
            }
            return List.copyOf(new ArrayList<>(merged).subList(
                    0, MAX_LIVE_SEARCH_DEPENDENCIES));
        }

        @Nullable
        private ResolvedDependency captureDependency(MacroSearch.DependencyKey dependency) {
            Object cacheKey;
            Object value;
            Object owner;
            Object stamps = null;
            long validity = 0L;
            switch (dependency.kind()) {
                case BASE_CLUSTER -> {
                    ClusterKey key = new ClusterKey(dimension, dependency.position());
                    BaseClusterTopology topology = TopologyWorkerRuntime.this.topology(
                            key, profile.geometry(channel));
                    ViewEntry view = topology == null ? null : baseView(dimension, topology);
                    if (view == null || view.topologyStamps.isEmpty()
                            || !isCurrent(view.topologyStamps)) return null;
                    pinBase(view, topology);
                    cacheKey = key;
                    value = topology;
                    owner = view;
                    stamps = view.topologyStamps;
                    validity = view.validity;
                }
                case SUPER_CLUSTER -> {
                    SuperCacheKey key = new SuperCacheKey(
                            dimension, dependency.position(), channel, profile);
                    SuperClusterTopology topology = superTopology(key);
                    SuperEntry entry = topology == null ? null : superView(dimension, topology);
                    List<SectionStamp> current = topology == null
                            ? null : currentSuperStamps(key, topology);
                    if (entry == null || current == null) return null;
                    pinSuper(entry, topology);
                    cacheKey = key;
                    value = topology;
                    owner = entry;
                    stamps = current;
                    validity = entry.validity;
                }
                case BASE_BOUNDARY -> {
                    BaseBoundaryCacheKey key = baseBoundaryKey(dependency);
                    LinkEntry<SuperClusterTopology.BoundaryLinks> entry =
                            key == null ? null : baseLinkEntry(key);
                    if (entry == null || entry.value == null) return null;
                    entry.pins++;
                    addActiveReference();
                    cacheKey = key;
                    value = entry.value;
                    owner = entry;
                }
                case SUPER_BOUNDARY -> {
                    SuperBoundaryCacheKey key = superBoundaryKey(dependency);
                    LinkEntry<SuperClusterTopology.CrossingIndex> entry =
                            key == null ? null : superLinkEntry(key);
                    if (entry == null || entry.value == null) return null;
                    entry.pins++;
                    addActiveReference();
                    cacheKey = key;
                    value = entry.value;
                    owner = entry;
                }
                default -> throw new IllegalStateException("unknown dependency kind");
            }
            return new ResolvedDependency(
                    dependency, cacheKey, value, owner, stamps, validity, null);
        }

        private boolean dependencyAvailableInWorld(MacroSearch.DependencyKey dependency) {
            return switch (dependency.kind()) {
                case BASE_CLUSTER -> clusterLoaded(new ClusterKey(
                        dimension,
                        dependency.position()
                ));
                case SUPER_CLUSTER -> superClusterAvailable(
                        dimension, dependency.position());
                case BASE_BOUNDARY -> dependency.target() != null
                        && clusterLoaded(new ClusterKey(dimension, dependency.position()))
                        && clusterLoaded(new ClusterKey(dimension, dependency.target()));
                case SUPER_BOUNDARY -> dependency.target() != null
                        && superClusterAvailable(dimension, dependency.position())
                        && superClusterAvailable(dimension, dependency.target());
            };
        }

        @Nullable
        private BaseBoundaryCacheKey baseBoundaryKey(MacroSearch.DependencyKey dependency) {
            if (dependency.kind() != MacroSearch.DependencyKind.BASE_BOUNDARY
                    || dependency.target() == null || dependency.face() == null) {
                return null;
            }
            BaseClusterTopology source = topology(new ClusterKey(
                    dimension,
                    dependency.position()
            ), profile.geometry(channel));
            BaseClusterTopology target = topology(new ClusterKey(
                    dimension,
                    dependency.target()
            ), profile.geometry(channel));
            return source == null || target == null
                    ? null
                    : new BaseBoundaryCacheKey(
                            dimension,
                            source,
                            target,
                            dependency.face()
                    );
        }

        @Nullable
        private SuperBoundaryCacheKey superBoundaryKey(MacroSearch.DependencyKey dependency) {
            if (dependency.kind() != MacroSearch.DependencyKind.SUPER_BOUNDARY
                    || dependency.target() == null || dependency.face() == null) {
                return null;
            }
            SuperClusterTopology source = superTopology(new SuperCacheKey(
                    dimension,
                    dependency.position(),
                    channel,
                    profile
            ));
            SuperClusterTopology target = superTopology(new SuperCacheKey(
                    dimension,
                    dependency.target(),
                    channel,
                    profile
            ));
            return source == null || target == null
                    ? null
                    : new SuperBoundaryCacheKey(
                            dimension,
                            source,
                            target,
                            dependency.face()
                    );
        }

        private void requestDependency(MacroSearch.DependencyKey key) {
            if (requests.containsKey(key) || resolvedDependencies.containsKey(key)
                    || liveDependencyCount() >= MAX_LIVE_SEARCH_DEPENDENCIES) {
                return;
            }
            switch (key.kind()) {
                case BASE_CLUSTER -> {
                    TopologyWaiter<BaseClusterTopology> waiter = dependencyWaiter(key);
                    requestClusterDependency(dimension, key.position(),
                            profile.geometry(channel), priority, false, waiter);
                }
                case SUPER_CLUSTER -> {
                    TopologyWaiter<SuperClusterTopology> waiter = dependencyWaiter(key);
                    requestSuperCluster(dimension, key.position(), channel, profile,
                            priority, waiter);
                }
                case BASE_BOUNDARY -> {
                    BaseBoundaryCacheKey boundary = baseBoundaryKey(key);
                    TopologyWaiter<SuperClusterTopology.BoundaryLinks> waiter =
                            dependencyWaiter(key);
                    if (boundary == null) waiter.complete(null, new StaleTopologyException(
                            "base boundary dependency lost a topology identity"));
                    else requestBaseBoundaryLinks(dimension, boundary.source(), boundary.target(),
                            boundary.face(), priority, waiter);
                }
                case SUPER_BOUNDARY -> {
                    SuperBoundaryCacheKey boundary = superBoundaryKey(key);
                    TopologyWaiter<SuperClusterTopology.CrossingIndex> waiter =
                            dependencyWaiter(key);
                    if (boundary == null) waiter.complete(null, new StaleTopologyException(
                            "super boundary dependency lost a topology identity"));
                    else requestSuperBoundaryLinks(dimension, boundary.source(), boundary.target(),
                            boundary.face(), priority, waiter);
                }
                default -> throw new IllegalStateException("unknown topology dependency " + key.kind());
            }
        }

        private <T> TopologyWaiter<T> dependencyWaiter(MacroSearch.DependencyKey dependency) {
            TopologyWaiter<T> waiter = new TopologyWaiter<>(priority,
                    completed -> completeDependencyRequest(dependency, completed));
            requests.put(dependency, waiter);
            adjustLiveSearchDependencies(1);
            return waiter;
        }

        private void retainResolvedDependency(MacroSearch.DependencyKey dependency,
                                              ResolvedDependency resolved) {
            if (resolvedDependencies.put(dependency, resolved) == null) {
                adjustLiveSearchDependencies(1);
            }
        }

        private int liveDependencyCount() {
            return requests.size() + resolvedDependencies.size();
        }

        private void completeDependencyRequest(MacroSearch.DependencyKey dependency,
                                               TopologyWaiter<?> request) {
            requireRuntimeLock();
            if (!requests.remove(dependency, request)) return;
            adjustLiveSearchDependencies(-1);
            if (status != Status.RUNNING) return;
            if (request.failure != null) {
                Throwable root = rootFailure(request.failure);
                if (!dependencyAvailableInWorld(dependency)) {
                    if (hasSearchState()) {
                        retainResolvedDependency(dependency, ResolvedDependency.unavailable(
                                dependency, MacroSearch.Failure.UNAVAILABLE_CHUNK));
                    }
                } else if (root instanceof FactsRecoveryException) {
                    if (hasSearchState()) {
                        retainResolvedDependency(dependency, ResolvedDependency.unavailable(
                                dependency, MacroSearch.Failure.FACTS_RECOVERY_FAILED));
                    } else if (dependency.kind() == MacroSearch.DependencyKind.SUPER_CLUSTER) {
                        parentFallbackPending = true;
                    } else {
                        buildFailure = root;
                    }
                } else if (dependency.kind() == MacroSearch.DependencyKind.SUPER_CLUSTER
                        && root instanceof RuntimeException) {
                    parentFallbackPending = true;
                } else buildFailure = root;
            } else {
                // Endpoint preparation only needs the published cache entry.  It has
                // no graph yet to receive a pin, so retaining a resolved handoff here
                // would consume the sixteen-item live limit and deadlock when endpoint
                // preparation needs the next batch.  Once a graph exists, every
                // completed dependency is captured and transferred below.
                if (hasSearchState()) {
                    ResolvedDependency ready = captureDependency(dependency);
                    if (ready == null) buildFailure = new StaleTopologyException(
                            "completed dependency is no longer current");
                    else retainResolvedDependency(dependency, ready);
                }
            }
            if (!workerRunning) runtimeContinuation.run();
        }

        private boolean hasSearchState() {
            return workerRunning || search != null || baseGraph != null || superGraph != null;
        }

        private void clearRequests() {
            if (workerRunning) return;
            pendingDependencies = List.of();
            List<TopologyWaiter<?>> active = List.copyOf(requests.values());
            adjustLiveSearchDependencies(-requests.size() - resolvedDependencies.size());
            requests.clear();
            active.forEach(TopologyWaiter::cancel);
            resolvedDependencies.values().forEach(this::releaseResolved);
            resolvedDependencies.clear();
        }

        private boolean needsDependencyResolution() {
            return !pendingDependencies.isEmpty();
        }

        private boolean waitingForBuild() {
            return !requests.isEmpty() && resolvedDependencies.isEmpty()
                    && buildFailure == null && !parentFallbackPending;
        }

        private long expansionWorkUnits() {
            long expanded = completedSearchMetrics == null
                    ? 0L : completedSearchMetrics.expansionWorkUnits();
            if (superSearch != null) {
                expanded += superSearch.metrics().expansionWorkUnits();
            }
            if (search != null) {
                expanded += search.metrics().expansionWorkUnits();
            }
            return expanded;
        }

        private MacroSearch.Progress progress() {
            MacroSearch.Metrics metrics = completedSearchMetrics == null
                    ? MacroSearch.Metrics.EMPTY : completedSearchMetrics;
            if (superSearch != null) metrics = metrics.plus(superSearch.metrics());
            if (search != null) metrics = metrics.plus(search.metrics());
            return new MacroSearch.Progress(status, failure, metrics, workerTasks,
                    dependencyWaits, pendingDependencies.size(),
                    hierarchical || superSearch != null, witnessSegments,
                    queueNanos, wallNanos, blockedSection());
        }

        private void markPhysicalSearchStarted() {
            if (firstWorkerNanos != 0L) return;
            long started = System.nanoTime();
            synchronized (runtimeLock) {
                if (firstWorkerNanos != 0L) return;
                firstWorkerNanos = started;
                queueNanos = Math.max(0L, started - submittedNanos);
                physicalSearchesStarted++;
            }
        }

        private void finishMeasurement() {
            requireRuntimeLock();
            if (measurementFinished) return;
            measurementFinished = true;
            wallNanos = Math.max(0L, System.nanoTime() - submittedNanos);
            if (firstWorkerNanos == 0L) {
                queueNanos = wallNanos;
                return;
            }
            if (status == Status.SUCCEEDED) physicalSearchesSucceeded++;
            else physicalSearchesFailed++;
        }

        private void requestCancel() {
            requireRuntimeLock();
            cancelRequested = true;
        }

        private void finishOnOwner() {
            requireRuntimeLock();
            if (cancelRequested && status == Status.RUNNING) {
                status = Status.FAILED;
                failure = MacroSearch.Failure.CANCELLED;
            }
            clearRequests();
            drainDeferredGraphs();
            releaseGraphs();
            finishMeasurement();
        }

        private void failFromWorker(Throwable workerFailure) {
            requireRuntimeLock();
            failureCause = Objects.requireNonNull(workerFailure, "workerFailure");
            status = Status.FAILED;
            clearRequests();
            releaseGraphs();
            finishMeasurement();
        }

        @Nullable
        private Throwable failureCause() {
            return failureCause;
        }

        private List<ResolvedDependency> peekResolvedDependencies() {
            synchronized (runtimeLock) {
                return List.copyOf(resolvedDependencies.values());
            }
        }

        private void applyResolvedDependencies(List<ResolvedDependency> resolved) {
            List<MacroSearch.DependencyKey> available = new ArrayList<>(resolved.size());
            try {
                for (ResolvedDependency dependency : resolved) {
                    if (dependency.unavailability != null) {
                        search.dependencyUnavailable(
                                dependency.dependency, dependency.unavailability);
                    } else if (attachResolved(dependency)) {
                        dependency.transferred = true;
                        available.add(dependency.dependency);
                    }
                }
                if (!available.isEmpty()) search.dependenciesAvailable(available);
            } finally {
                runRuntimeTransition(() -> resolved.forEach(dependency -> {
                    if (resolvedDependencies.remove(dependency.dependency, dependency)) {
                        adjustLiveSearchDependencies(-1);
                        releaseResolved(dependency);
                    }
                }));
            }
        }

        private boolean attachResolved(ResolvedDependency resolved) {
            return switch (resolved.dependency.kind()) {
                case BASE_CLUSTER -> {
                    if (baseGraph == null) yield false;
                    yield baseGraph.attachTopology((ClusterKey) resolved.cacheKey,
                            (BaseClusterTopology) resolved.value, (ViewEntry) resolved.owner,
                            castStamps(resolved.stamps), resolved.validity);
                }
                case SUPER_CLUSTER -> {
                    if (superGraph == null) yield false;
                    yield superGraph.attachTopology((SuperCacheKey) resolved.cacheKey,
                            (SuperClusterTopology) resolved.value, (SuperEntry) resolved.owner,
                            castStamps(resolved.stamps), resolved.validity);
                }
                case BASE_BOUNDARY -> {
                    if (baseGraph == null) yield false;
                    yield baseGraph.attachBoundary((BaseBoundaryCacheKey) resolved.cacheKey,
                            (SuperClusterTopology.BoundaryLinks) resolved.value,
                            castLink(resolved.owner));
                }
                case SUPER_BOUNDARY -> {
                    if (superGraph == null) yield false;
                    yield superGraph.attachBoundary((SuperBoundaryCacheKey) resolved.cacheKey,
                            (SuperClusterTopology.CrossingIndex) resolved.value,
                            castLink(resolved.owner));
                }
            };
        }

        @SuppressWarnings("unchecked")
        private List<SectionStamp> castStamps(Object value) {
            return (List<SectionStamp>) value;
        }

        @SuppressWarnings("unchecked")
        private <T> LinkEntry<T> castLink(Object value) {
            return (LinkEntry<T>) value;
        }

        private void releaseResolved(ResolvedDependency resolved) {
            if (resolved.unavailability != null || resolved.transferred) return;
            switch (resolved.dependency.kind()) {
                case BASE_CLUSTER -> releaseBasePin((ViewEntry) resolved.owner,
                        (BaseClusterTopology) resolved.value);
                case SUPER_CLUSTER -> releaseSuperPin((SuperEntry) resolved.owner,
                        (SuperClusterTopology) resolved.value);
                case BASE_BOUNDARY, SUPER_BOUNDARY -> releaseLinkPin(
                        (LinkEntry<?>) resolved.owner);
            }
        }

        @Nullable
        private SectionPos blockedSection() {
            if (search != null) {
                return search.blockedSection();
            }
            return blockedEndpoint;
        }

        private void reprioritize(NavigationScheduler.Priority requested) {
            requireRuntimeLock();
            if (status != Status.RUNNING || priority == requested) {
                return;
            }
            priority = requested;
            requests.values().forEach(request -> request.reprioritize(requested));
        }
    }

    private boolean clusterLoaded(ClusterKey key) {
        ClusterEntry entry = clusters.get(key);
        return entry != null && entry.latest.state() != FactState.UNLOADED;
    }

    private static final class StaleTopologyException extends RuntimeException {
        private StaleTopologyException(ClusterKey key) {
            super("topology build became stale for " + key);
        }

        private StaleTopologyException(String message) {
            super(message);
        }
    }

    private static final class FactsRecoveryException extends RuntimeException {
        private final ClusterKey key;

        private FactsRecoveryException(ClusterKey key) {
            super("basic facts recovery failed for " + key);
            this.key = key;
        }
    }

    /** Search view over profile-specific 32-cubed contractions. */
    private final class SuperTopologyGraph implements MacroSearch.Graph {
        private final ResourceKey<Level> dimension;
        private final MacroSearch.ExactEndpoint start;
        private final MacroSearch.ExactEndpoint goal;
        private final BaseClusterTopology.Channel channel;
        private final BaseClusterTopology.TraversalProfile profile;
        private final Long2ObjectOpenHashMap<CapturedSuper> topologySnapshot =
                new Long2ObjectOpenHashMap<>();
        private final Map<SuperBoundaryCacheKey, CapturedBoundary<SuperClusterTopology.CrossingIndex>>
                boundarySnapshot = new HashMap<>();
        private final Long2ObjectOpenHashMap<AggregateBinding[]> bindingsByCluster =
                new Long2ObjectOpenHashMap<>();
        private final Long2ObjectOpenHashMap<AggregateBinding> bindingsByEndpoint =
                new Long2ObjectOpenHashMap<>();
        private final Map<Long, AggregateWitness> witnesses = new HashMap<>();
        private List<MacroComponentKey> startCandidates;
        private List<MacroComponentKey> goalCandidates;
        private List<AggregateBinding> startBindings;
        private List<AggregateBinding> goalBindings;
        private long nextEndpointId = 2L;
        private volatile boolean closed;

        private SuperTopologyGraph(ResourceKey<Level> dimension,
                                   BlockPos startPosition,
                                   BlockPos goalPosition,
                                   BaseClusterTopology.Channel channel,
                                   BaseClusterTopology.TraversalProfile profile,
                                   List<MacroComponentKey> startCandidates,
                                   List<MacroComponentKey> goalCandidates) {
            this.dimension = dimension;
            this.channel = channel;
            this.profile = profile;
            this.start = new MacroSearch.ExactEndpoint(
                    0L, startPosition, candidateSignature(startCandidates));
            this.goal = new MacroSearch.ExactEndpoint(
                    1L, goalPosition, candidateSignature(goalCandidates));
            this.startCandidates = startCandidates;
            this.goalCandidates = goalCandidates;
            try {
                for (MacroComponentKey candidate : startCandidates) captureCandidateParent(candidate);
                for (MacroComponentKey candidate : goalCandidates) captureCandidateParent(candidate);
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        private void captureCandidateParent(MacroComponentKey candidate) {
            if (captureInitialTopology(key(SuperClusterTopology.originOf(candidate.section))) == null) {
                throw new StaleTopologyException("endpoint parent topology is unavailable");
            }
        }

        private void initializeBindings() {
            if (startBindings != null) return;
            startBindings = bindCandidates(startCandidates);
            goalBindings = bindCandidates(goalCandidates);
            startCandidates = List.of();
            goalCandidates = List.of();
        }

        @Override
        public MacroSearch.Endpoint start() {
            return start;
        }

        @Override
        public MacroSearch.Endpoint goal() {
            return goal;
        }

        @Override
        public void expandInto(MacroSearch.Endpoint from, MacroSearch.ExpansionBuffer output) {
            if (from.id() == goal.id()) {
                return;
            }
            if (from.id() == start.id()) {
                for (AggregateBinding startBinding : startBindings) {
                    output.addMembership(
                            edgeId(start, startBinding.endpoint()),
                            startBinding.endpoint(),
                            0.0F
                    );
                }
                return;
            }

            AggregateBinding source = bindingsByEndpoint.get(from.id());
            if (source == null) {
                throw new IllegalArgumentException("endpoint does not belong to this super graph");
            }
            CapturedSuper sourceTopology = requireTopology(source.cluster());
            SuperClusterTopology topology = sourceTopology.topology;
            int aggregateId = source.aggregateId();
            if (output.needsPart(0)) {
                if (goalBindings.stream().anyMatch(goalBinding -> sameAggregate(source, goalBinding))) {
                    output.addMembership(edgeId(source.endpoint(), goal), goal, 0.0F);
                }
                for (int edge = topology.outgoingStart(aggregateId);
                     edge < topology.outgoingEnd(aggregateId); edge++) {
                    AggregateBinding target = binding(sourceTopology, topology.outgoingTarget(edge));
                    long id = edgeId(source.endpoint(), target.endpoint());
                    rememberWitness(id, sourceTopology, topology.outgoingWitness(edge),
                            sourceTopology, null);
                    output.addAggregate(id, target.endpoint(), topology.outgoingCost(edge));
                }
                output.completePart(0);
            }

            for (Direction face : DIRECTIONS) {
                if (face.getAxis().isVertical()) {
                    expandParentBoundary(source, sourceTopology, aggregateId, face, 0, output);
                } else {
                    for (int yShift = -1; yShift <= 1; yShift++) {
                        expandParentBoundary(source, sourceTopology, aggregateId, face, yShift,
                                output);
                    }
                }
            }
        }

        private void expandParentBoundary(AggregateBinding source,
                                          CapturedSuper sourceTopology,
                                          int aggregateId,
                                          Direction face,
                                          int yShift,
                                          MacroSearch.ExpansionBuffer output) {
            SectionPos neighborOrigin = sourceTopology.neighbor(face, yShift);
            int part = superLinkSlot(source.cluster().origin(), neighborOrigin, face) + 1;
            if (!output.needsPart(part)) return;
            if (!sourceTopology.topology.hasPotentialExit(aggregateId, neighborOrigin)) {
                output.completePart(part);
                return;
            }
            CapturedSuper neighbor = captureTopology(neighborOrigin);
            if (neighbor == null) {
                output.addDependency(part,
                        MacroSearch.DependencyKey.superCluster(neighborOrigin));
                return;
            }
            SuperClusterTopology.CrossingIndex links =
                    sourceTopology.boundaryLinks(face, neighbor);
            if (links == null) {
                output.addDependency(part, MacroSearch.DependencyKey.superBoundary(
                        source.cluster().origin(), neighbor.key.origin(), face));
                return;
            }
            for (int edge = links.edgeStart(aggregateId);
                 edge < links.edgeEnd(aggregateId); edge++) {
                AggregateBinding target = binding(neighbor, links.targetAggregate(edge));
                long id = edgeId(source.endpoint(), target.endpoint());
                rememberWitness(id, sourceTopology, links.witness(edge), neighbor, links.face(edge));
                output.addAggregate(id, target.endpoint(),
                        links.lowerBound(edge));
            }
            output.completePart(part);
        }

        private void rememberWitness(long id,
                                     CapturedSuper source,
                                     long packedNodes,
                                     CapturedSuper target,
                                     @Nullable Direction face) {
            int sourceNode = (int) (packedNodes >>> 32);
            int targetNode = (int) packedNodes;
            MacroComponentKey sourceComponent = new MacroComponentKey(
                    source.topology.nodeSection(sourceNode), source.topology.nodeComponent(sourceNode),
                    source.topology.nodeSignature(sourceNode));
            MacroComponentKey targetComponent = new MacroComponentKey(
                    target.topology.nodeSection(targetNode), target.topology.nodeComponent(targetNode),
                    target.topology.nodeSignature(targetNode));
            AggregateWitness witness = new AggregateWitness(sourceComponent, targetComponent, face);
            witnesses.putIfAbsent(id, witness);
        }

        @Nullable
        private AggregateWitness witness(long id) {
            return witnesses.get(id);
        }

        @Override
        public boolean revisionsValid() {
            if (closed) return false;
            for (CapturedSuper captured : topologySnapshot.values()) {
                if (!captured.current()) return false;
            }
            return true;
        }

        @Override
        public float heuristic(MacroSearch.Endpoint endpoint) {
            if (endpoint.id() == goal.id()) {
                return 0.0F;
            }
            SectionPos sourceOrigin = endpoint instanceof MacroSearch.AggregateEndpoint aggregate
                    ? aggregate.origin()
                    : SuperClusterTopology.originOf(SectionPos.of(endpoint.anchor()));
            int best = Integer.MAX_VALUE;
            for (AggregateBinding binding : goalBindings) {
                SectionPos target = binding.cluster().origin();
                int dx = Math.abs(target.x() - sourceOrigin.x())
                        / SuperClusterTopology.CHILDREN_PER_AXIS;
                int dy = Math.abs(target.y() - sourceOrigin.y())
                        / SuperClusterTopology.CHILDREN_PER_AXIS;
                int dz = Math.abs(target.z() - sourceOrigin.z())
                        / SuperClusterTopology.CHILDREN_PER_AXIS;
                best = Math.min(best, Math.max(dx, Math.max(dy, dz)));
            }
            return best;
        }

        @Override
        public float prefetchSlack() {
            return 1.0F;
        }

        private SuperCacheKey key(SectionPos origin) {
            return new SuperCacheKey(dimension, origin, channel, profile);
        }

        private CapturedSuper requireTopology(SuperCacheKey key) {
            CapturedSuper topology = captureTopology(key);
            if (topology == null) {
                throw new IllegalStateException("super topology is not ready for " + key);
            }
            return topology;
        }

        @Nullable
        private CapturedSuper captureTopology(SuperCacheKey key) {
            return topologySnapshot.get(key.origin().asLong());
        }

        @Nullable
        private CapturedSuper captureTopology(SectionPos origin) {
            return topologySnapshot.get(origin.asLong());
        }

        @Nullable
        private CapturedSuper captureInitialTopology(SuperCacheKey key) {
            long packed = key.origin().asLong();
            CapturedSuper existing = topologySnapshot.get(packed);
            if (existing != null) {
                return existing;
            }
            SuperEntry entry = superClusters.get(key);
            SuperClusterTopology topology = superTopology(key);
            List<SectionStamp> stamps = topology == null ? null : currentSuperStamps(key, topology);
            if (stamps == null) {
                return null;
            }
            pinSuper(entry, topology);
            CapturedSuper captured = new CapturedSuper(
                    key, topology, entry, stamps, entry.validity);
            topologySnapshot.put(packed, captured);
            return captured;
        }

        private boolean attachTopology(SuperCacheKey key,
                                       SuperClusterTopology topology,
                                       SuperEntry owner,
                                       List<SectionStamp> stamps,
                                       long validity) {
            if (closed || topologySnapshot.containsKey(key.origin().asLong())) {
                return false;
            }
            topologySnapshot.put(key.origin().asLong(),
                    new CapturedSuper(key, topology, owner, stamps, validity));
            return true;
        }

        private boolean attachBoundary(SuperBoundaryCacheKey key,
                                       SuperClusterTopology.CrossingIndex links,
                                       LinkEntry<SuperClusterTopology.CrossingIndex> owner) {
            if (closed) {
                return false;
            }
            CapturedSuper source = topologySnapshot.get(key.source().origin().asLong());
            CapturedSuper target = topologySnapshot.get(key.target().origin().asLong());
            if (source == null || target == null
                    || source.topology != key.source()
                    || target.topology != key.target()) {
                throw new StaleTopologyException("parent boundary handoff is no longer current");
            }
            return boundarySnapshot.putIfAbsent(
                    key, new CapturedBoundary<>(links, owner)) == null;
        }

        private void close() {
            if (closed) return;
            closed = true;
            for (CapturedSuper captured : topologySnapshot.values()) {
                releaseSuperPin(captured.owner, captured.topology);
            }
            boundarySnapshot.values().forEach(boundary -> releaseLinkPin(boundary.owner));
            boundarySnapshot.clear();
            evictSuperCache();
        }

        private void collectStamps(Map<ClusterKey, SectionStamp> target) {
            for (CapturedSuper captured : topologySnapshot.values()) {
                for (SectionStamp stamp : captured.stamps) target.put(stamp.key(), stamp);
            }
        }

        private List<AggregateBinding> bindCandidates(List<MacroComponentKey> candidates) {
            List<AggregateBinding> bindings = new ArrayList<>(candidates.size());
            for (MacroComponentKey candidate : candidates) {
                CapturedSuper parent = captureInitialTopology(key(
                        SuperClusterTopology.originOf(candidate.section)));
                if (parent == null) {
                    throw new StaleTopologyException("endpoint parent topology is unavailable");
                }
                int aggregateId = parent.topology.aggregateId(
                        candidate.section, candidate.componentId);
                if (aggregateId < 0) {
                    throw new StaleTopologyException("candidate parent mapping changed");
                }
                AggregateBinding binding = binding(parent, aggregateId);
                if (bindings.stream().noneMatch(existing -> sameAggregate(existing, binding))) {
                    bindings.add(binding);
                }
            }
            return List.copyOf(bindings);
        }

        private AggregateBinding binding(CapturedSuper captured, int aggregateId) {
            SuperClusterTopology topology = captured.topology;
            topology.outgoingStart(aggregateId);
            long packed = captured.key.origin().asLong();
            AggregateBinding[] bindings = bindingsByCluster.get(packed);
            if (bindings == null) {
                bindings = new AggregateBinding[topology.aggregateCount()];
                bindingsByCluster.put(packed, bindings);
            }
            AggregateBinding binding = bindings[aggregateId];
            if (binding != null) {
                return binding;
            }
            MacroSearch.AggregateEndpoint endpoint = new MacroSearch.AggregateEndpoint(
                    nextEndpointId++,
                    topology.aggregateAnchor(aggregateId),
                    topology.signature(),
                    captured.key.origin(),
                    channel,
                    aggregateId
            );
            binding = new AggregateBinding(captured.key, aggregateId, endpoint);
            bindings[aggregateId] = binding;
            bindingsByEndpoint.put(endpoint.id(), binding);
            return binding;
        }

        private SuperClusterTopology capturedTopology(SectionPos origin) {
            CapturedSuper captured = topologySnapshot.get(origin.asLong());
            return captured == null ? null : captured.topology;
        }

        private final class CapturedSuper {
            private final SuperCacheKey key;
            private final SuperClusterTopology topology;
            private final SuperEntry owner;
            private final List<SectionStamp> stamps;
            private final long validity;
            private final SuperBoundaryCacheKey[] boundaries = new SuperBoundaryCacheKey[14];
            private final SectionPos[] neighbors = new SectionPos[14];
            private final SuperClusterTopology[] boundaryTargets = new SuperClusterTopology[14];
            private final SuperClusterTopology.CrossingIndex[] readyBoundaries =
                    new SuperClusterTopology.CrossingIndex[14];

            private CapturedSuper(SuperCacheKey key,
                                  SuperClusterTopology topology,
                                  SuperEntry owner,
                                  List<SectionStamp> stamps,
                                  long validity) {
                this.key = key;
                this.topology = topology;
                this.owner = owner;
                this.stamps = stamps;
                this.validity = validity;
            }

            private boolean current() { return owner.validity == validity; }

            private SectionPos neighbor(Direction face, int yShift) {
                SectionPos direct = SuperClusterTopology.offset(
                        key.origin(), face, SuperClusterTopology.CHILDREN_PER_AXIS
                );
                SectionPos target = face.getAxis().isVertical() ? direct : SectionPos.of(
                        direct.x(), direct.y() + yShift * SuperClusterTopology.CHILDREN_PER_AXIS,
                        direct.z()
                );
                int index = superLinkSlot(key.origin(), target, face);
                SectionPos origin = neighbors[index];
                if (origin == null) {
                    origin = target;
                    neighbors[index] = origin;
                }
                return origin;
            }

            @Nullable
            private SuperClusterTopology.CrossingIndex boundaryLinks(
                    Direction face,
                    CapturedSuper target) {
                int index = superLinkSlot(key.origin(), target.key.origin(), face);
                if (boundaryTargets[index] != target.topology) {
                    boundaryTargets[index] = target.topology;
                    boundaries[index] = new SuperBoundaryCacheKey(
                            dimension,
                            topology,
                            target.topology,
                            face
                    );
                    readyBoundaries[index] = null;
                }
                SuperClusterTopology.CrossingIndex ready = readyBoundaries[index];
                if (ready == null) {
                    CapturedBoundary<SuperClusterTopology.CrossingIndex> captured =
                            boundarySnapshot.get(boundaries[index]);
                    ready = captured == null ? null : captured.value;
                    readyBoundaries[index] = ready;
                }
                return ready;
            }
        }
    }

    /** Restricts one local recovery search to one immutable parent aggregate. */
    private final class AggregateTopologyGraph implements MacroSearch.Graph, ComponentAdmission {
        private final TopologyGraph delegate;
        private final SuperTopologyGraph parent;
        private final SectionPos aggregateOrigin;
        private final int aggregateId;

        private AggregateTopologyGraph(TopologyGraph delegate,
                                       SuperTopologyGraph parent,
                                       SectionPos aggregateOrigin,
                                       int aggregateId) {
            this.delegate = delegate;
            this.parent = parent;
            this.aggregateOrigin = Objects.requireNonNull(aggregateOrigin, "aggregateOrigin");
            this.aggregateId = aggregateId;
            if (parent.capturedTopology(aggregateOrigin) == null) {
                throw new StaleTopologyException("local recovery parent is unavailable");
            }
        }

        @Override
        public MacroSearch.Endpoint start() {
            return delegate.start();
        }

        @Override
        public MacroSearch.Endpoint goal() {
            return delegate.goal();
        }

        @Override
        public void expandInto(MacroSearch.Endpoint from, MacroSearch.ExpansionBuffer output) {
            delegate.expandInto(from, output, this);
        }

        @Override
        public boolean revisionsValid() {
            return parent.revisionsValid() && delegate.revisionsValid();
        }

        @Override
        public float heuristic(MacroSearch.Endpoint endpoint) {
            return delegate.heuristic(endpoint);
        }

        @Override
        public float prefetchSlack() {
            return delegate.prefetchSlack();
        }

        @Override
        public boolean allowsSection(SectionPos section) {
            return SuperClusterTopology.originOf(section).equals(aggregateOrigin);
        }

        @Override
        public boolean allowsComponent(SectionPos section, int componentId) {
            if (!allowsSection(section)) return false;
            SuperClusterTopology topology = parent.capturedTopology(aggregateOrigin);
            return topology != null && topology.aggregateId(section, componentId) == aggregateId;
        }
    }

    private final class TopologyGraph implements MacroSearch.Graph {
        private final ResourceKey<Level> dimension;
        private final MacroSearch.Endpoint start;
        private final MacroSearch.Endpoint goal;
        private final BaseClusterTopology.Channel channel;
        private final BaseClusterTopology.TraversalProfile profile;
        private final Long2ObjectOpenHashMap<CapturedBase> topologySnapshot =
                new Long2ObjectOpenHashMap<>();
        private final Map<BaseBoundaryCacheKey, CapturedBoundary<SuperClusterTopology.BoundaryLinks>>
                boundarySnapshot = new HashMap<>();
        private final Long2ObjectOpenHashMap<ComponentBinding[]> bindingsByCluster =
                new Long2ObjectOpenHashMap<>();
        private final Long2ObjectOpenHashMap<ComponentBinding> bindingsByEndpoint =
                new Long2ObjectOpenHashMap<>();
        private List<MacroComponentKey> startCandidates;
        private List<MacroComponentKey> goalCandidates;
        private List<ComponentBinding> startBindings;
        private List<ComponentBinding> goalBindings;
        private long nextEndpointId = 2L;
        private volatile boolean closed;

        private TopologyGraph(ResourceKey<Level> dimension,
                              BlockPos startPosition,
                              BlockPos goalPosition,
                              BaseClusterTopology.Channel channel,
                              BaseClusterTopology.TraversalProfile profile,
                              List<MacroComponentKey> startCandidates,
                              List<MacroComponentKey> goalCandidates) {
            this(
                    dimension,
                    new MacroSearch.ExactEndpoint(
                            0L,
                            startPosition,
                            candidateSignature(startCandidates)
                    ),
                    new MacroSearch.ExactEndpoint(
                            1L,
                            goalPosition,
                            candidateSignature(goalCandidates)
                    ),
                    channel,
                    profile,
                    startCandidates,
                    goalCandidates
            );
        }

        private TopologyGraph(ResourceKey<Level> dimension,
                              MacroSearch.Endpoint start,
                              MacroSearch.Endpoint goal,
                              BaseClusterTopology.Channel channel,
                              BaseClusterTopology.TraversalProfile profile,
                              List<MacroComponentKey> startCandidates,
                              List<MacroComponentKey> goalCandidates) {
            this.dimension = dimension;
            this.channel = channel;
            this.profile = profile;
            this.start = Objects.requireNonNull(start, "start");
            this.goal = Objects.requireNonNull(goal, "goal");
            this.startCandidates = startCandidates;
            this.goalCandidates = goalCandidates;
            try {
                for (MacroComponentKey candidate : startCandidates) captureCandidate(candidate);
                for (MacroComponentKey candidate : goalCandidates) captureCandidate(candidate);
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        private void captureCandidate(MacroComponentKey candidate) {
            if (captureInitialTopology(new ClusterKey(dimension, candidate.section)) == null) {
                throw new StaleTopologyException("endpoint topology is unavailable");
            }
        }

        private void initializeBindings() {
            if (startBindings != null) return;
            startBindings = bindCandidates(startCandidates);
            goalBindings = bindCandidates(goalCandidates);
            startCandidates = List.of();
            goalCandidates = List.of();
        }

        @Override
        public MacroSearch.Endpoint start() {
            return start;
        }

        @Override
        public MacroSearch.Endpoint goal() {
            return goal;
        }

        @Override
        public void expandInto(MacroSearch.Endpoint from, MacroSearch.ExpansionBuffer output) {
            expandInto(from, output, null);
        }

        private void expandInto(MacroSearch.Endpoint from,
                                MacroSearch.ExpansionBuffer output,
                                @Nullable ComponentAdmission admission) {
            if (from.id() == goal.id()) {
                return;
            }
            if (from.id() == start.id()) {
                for (ComponentBinding startBinding : startBindings) {
                    if (admission == null || admission.allowsComponent(
                            startBinding.cluster().section(), startBinding.componentId())) {
                        output.addMembership(edgeId(start, startBinding.endpoint()),
                                startBinding.endpoint(), 0.0F);
                    }
                }
                return;
            }

            ComponentBinding source = bindingsByEndpoint.get(from.id());
            if (source == null) {
                throw new IllegalArgumentException("endpoint does not belong to this graph");
            }
            CapturedBase sourceTopology = requireTopology(source.cluster());
            BaseClusterTopology topology = sourceTopology.topology;
            int sourceComponentId = source.componentId();
            BaseClusterTopology.MovementKey movement = profile.movement(channel);
            if (output.needsPart(0)) {
                if (goalBindings.stream().anyMatch(goalBinding -> sameComponent(source, goalBinding))) {
                    output.addMembership(edgeId(source.endpoint(), goal), goal, 0.0F);
                }
                for (int edge = topology.localEdgeStart(sourceComponentId);
                     edge < topology.localEdgeEnd(sourceComponentId); edge++) {
                    if (!topology.localEdgeSupports(edge, movement)) continue;
                    int targetComponentId = topology.localEdgeTarget(edge);
                    if (admission != null && (!admission.allowsComponent(
                            source.cluster().section(), targetComponentId)
                            || !admission.allowsTransition(source.cluster().section(),
                            sourceComponentId, source.cluster().section(), targetComponentId))) continue;
                    ComponentBinding target = binding(sourceTopology, targetComponentId);
                    output.addLocal(edgeId(source.endpoint(), target.endpoint()), target.endpoint(),
                            topology.localEdgeLowerBound(edge));
                }
                output.completePart(0);
            }

            for (Direction face : DIRECTIONS) {
                if (face.getAxis().isVertical()) {
                    expandBoundary(source, sourceTopology, sourceComponentId, face, 0,
                            admission, movement, output);
                } else {
                    for (int yShift = -1; yShift <= 1; yShift++) {
                        expandBoundary(source, sourceTopology, sourceComponentId, face, yShift,
                                admission, movement, output);
                    }
                }
            }
        }

        private void expandBoundary(ComponentBinding source,
                                    CapturedBase sourceTopology,
                                    int sourceComponentId,
                                    Direction face,
                                    int yShift,
                                    @Nullable ComponentAdmission admission,
                                    BaseClusterTopology.MovementKey movement,
                                    MacroSearch.ExpansionBuffer output) {
            SectionPos neighborSection = sourceTopology.neighbor(face, yShift);
            int part = baseLinkSlot(source.cluster().section(), neighborSection, face) + 1;
            if (!output.needsPart(part)) return;
            if (admission != null && !admission.allowsSection(neighborSection)) {
                output.completePart(part);
                return;
            }
            CapturedBase neighbor = captureTopology(neighborSection);
            if (neighbor == null) {
                output.addDependency(part, new MacroSearch.DependencyKey(
                        MacroSearch.DependencyKind.BASE_CLUSTER, neighborSection));
                return;
            }
            SuperClusterTopology.BoundaryLinks links =
                    sourceTopology.boundaryLinks(face, yShift, neighbor);
            if (links == null) {
                output.addDependency(part, MacroSearch.DependencyKey.baseBoundary(
                        source.cluster().section(), neighborSection, face));
                return;
            }
            for (int edge = links.edgeStart(sourceComponentId);
                 edge < links.edgeEnd(sourceComponentId); edge++) {
                if (!links.supports(edge, movement)) continue;
                int targetComponentId = links.targetComponent(edge);
                if (admission != null && !admission.allowsComponent(
                        neighborSection, targetComponentId)) continue;
                if (admission != null && !admission.allowsTransition(
                        source.cluster().section(),
                        sourceComponentId,
                        neighborSection,
                        targetComponentId
                )) continue;
                ComponentBinding target = binding(neighbor, targetComponentId);
                output.addBoundary(edgeId(source.endpoint(), target.endpoint()), target.endpoint(),
                        links.lowerBound(edge), links, edge, movement.capabilityMask());
            }
            output.completePart(part);
        }

        @Override
        public boolean revisionsValid() {
            if (closed) return false;
            for (CapturedBase captured : topologySnapshot.values()) {
                if (!captured.current()) return false;
            }
            return true;
        }

        @Override
        public float heuristic(MacroSearch.Endpoint endpoint) {
            if (endpoint.id() == goal.id()) {
                return 0.0F;
            }
            SectionPos sourceSection = endpoint instanceof MacroSearch.ComponentEndpoint component
                    ? component.section()
                    : SectionPos.of(endpoint.anchor());
            int best = Integer.MAX_VALUE;
            for (ComponentBinding binding : goalBindings) {
                SectionPos target = binding.cluster().section();
                int dx = Math.abs(target.x() - sourceSection.x());
                int dy = Math.abs(target.y() - sourceSection.y());
                int dz = Math.abs(target.z() - sourceSection.z());
                best = Math.min(best, Math.max(dx, Math.max(dy, dz)));
            }
            return best;
        }

        @Override
        public float prefetchSlack() {
            // One boundary transition is the graph's minimum cross-section cost.
            return 1.0F;
        }

        private CapturedBase requireTopology(ClusterKey key) {
            CapturedBase topology = captureTopology(key);
            if (topology == null) {
                throw new IllegalStateException("topology is not ready for " + key);
            }
            return topology;
        }

        @Nullable
        private CapturedBase captureTopology(ClusterKey key) {
            return topologySnapshot.get(key.section().asLong());
        }

        @Nullable
        private CapturedBase captureTopology(SectionPos section) {
            return topologySnapshot.get(section.asLong());
        }

        @Nullable
        private CapturedBase captureInitialTopology(ClusterKey key) {
            long packed = key.section().asLong();
            CapturedBase existing = topologySnapshot.get(packed);
            if (existing != null) {
                return existing;
            }
            ClusterEntry entry = clusters.get(key);
            ViewEntry view = entry == null ? null : entry.views.get(profile.geometry(channel));
            BaseClusterTopology topology = entry == null
                    ? null : entry.topology(profile.geometry(channel));
            List<SectionStamp> stamps = view == null ? List.of() : view.topologyStamps;
            if (topology == null || stamps.isEmpty() || !isCurrent(stamps)) {
                return null;
            }
            pinBase(view, topology);
            CapturedBase captured = new CapturedBase(key, topology, view, stamps, view.validity);
            topologySnapshot.put(packed, captured);
            return captured;
        }

        private boolean attachTopology(ClusterKey key,
                                       BaseClusterTopology topology,
                                       ViewEntry owner,
                                       List<SectionStamp> stamps,
                                       long validity) {
            if (closed || topologySnapshot.containsKey(key.section().asLong())) {
                return false;
            }
            topologySnapshot.put(key.section().asLong(),
                    new CapturedBase(key, topology, owner, stamps, validity));
            return true;
        }

        private boolean attachBoundary(BaseBoundaryCacheKey key,
                                       SuperClusterTopology.BoundaryLinks links,
                                       LinkEntry<SuperClusterTopology.BoundaryLinks> owner) {
            if (closed) {
                return false;
            }
            CapturedBase source = topologySnapshot.get(key.source().section().asLong());
            CapturedBase target = topologySnapshot.get(key.target().section().asLong());
            if (source == null || target == null
                    || source.topology != key.source()
                    || target.topology != key.target()) {
                throw new StaleTopologyException("base boundary handoff is no longer current");
            }
            return boundarySnapshot.putIfAbsent(
                    key, new CapturedBoundary<>(links, owner)) == null;
        }

        private void close() {
            if (closed) return;
            closed = true;
            for (CapturedBase captured : topologySnapshot.values()) {
                releaseBasePin(captured.owner, captured.topology);
            }
            boundarySnapshot.values().forEach(boundary -> releaseLinkPin(boundary.owner));
            boundarySnapshot.clear();
            evictBaseCache();
        }

        private void collectStamps(Map<ClusterKey, SectionStamp> target) {
            for (CapturedBase captured : topologySnapshot.values()) {
                for (SectionStamp stamp : captured.stamps) target.put(stamp.key(), stamp);
            }
        }

        private List<ComponentBinding> bindCandidates(List<MacroComponentKey> candidates) {
            List<ComponentBinding> bindings = new ArrayList<>(candidates.size());
            for (MacroComponentKey candidate : candidates) {
                CapturedBase captured = captureInitialTopology(
                        new ClusterKey(dimension, candidate.section));
                if (captured == null) {
                    throw new StaleTopologyException("endpoint topology is unavailable");
                }
                if (captured.topology.signature() != candidate.signature
                        || candidate.componentId < 0
                        || candidate.componentId >= captured.topology.componentCount()) {
                    throw new StaleTopologyException("candidate base topology changed");
                }
                bindings.add(binding(captured, candidate.componentId));
            }
            return List.copyOf(bindings);
        }

        private ComponentBinding binding(CapturedBase captured, int componentId) {
            BaseClusterTopology topology = captured.topology;
            if (topology.geometry().channel() != channel) {
                throw new IllegalArgumentException(
                        "component " + componentId + " does not use channel " + channel
                );
            }
            long packed = captured.key.section().asLong();
            ComponentBinding[] bindings = bindingsByCluster.get(packed);
            if (bindings == null) {
                bindings = new ComponentBinding[topology.componentCount()];
                bindingsByCluster.put(packed, bindings);
            }
            ComponentBinding binding = bindings[componentId];
            if (binding != null) {
                return binding;
            }
            MacroSearch.ComponentEndpoint endpoint = new MacroSearch.ComponentEndpoint(
                    nextEndpointId++,
                    componentAnchor(captured.key.section(), topology, componentId),
                    topology.signature(),
                    captured.key.section(),
                    channel,
                    componentId
            );
            binding = new ComponentBinding(captured.key, componentId, endpoint);
            bindings[componentId] = binding;
            bindingsByEndpoint.put(endpoint.id(), binding);
            return binding;
        }

        private final class CapturedBase {
            private final ClusterKey key;
            private final BaseClusterTopology topology;
            private final ViewEntry owner;
            private final List<SectionStamp> stamps;
            private final long validity;
            private final SectionPos[] neighbors = new SectionPos[14];
            private final BaseBoundaryCacheKey[] boundaries = new BaseBoundaryCacheKey[14];
            private final BaseClusterTopology[] boundaryTargets = new BaseClusterTopology[14];
            private final SuperClusterTopology.BoundaryLinks[] readyBoundaries =
                    new SuperClusterTopology.BoundaryLinks[14];

            private CapturedBase(ClusterKey key,
                                 BaseClusterTopology topology,
                                 ViewEntry owner,
                                 List<SectionStamp> stamps,
                                 long validity) {
                this.key = key;
                this.topology = topology;
                this.owner = owner;
                this.stamps = stamps;
                this.validity = validity;
            }

            private boolean current() { return owner.validity == validity; }

            private SectionPos neighbor(Direction face, int yShift) {
                int index = face.getAxis().isVertical()
                        ? baseLinkSlot(key.section(), SuperClusterTopology.offset(
                        key.section(), face, 1), face)
                        : baseLinkSlot(key.section(), SectionPos.of(
                        key.section().x() + face.getStepX(), key.section().y() + yShift,
                        key.section().z() + face.getStepZ()), face);
                SectionPos section = neighbors[index];
                if (section == null) {
                    SectionPos horizontal = SuperClusterTopology.offset(key.section(), face, 1);
                    section = face.getAxis().isVertical() ? horizontal : SectionPos.of(
                            horizontal.x(), horizontal.y() + yShift, horizontal.z()
                    );
                    neighbors[index] = section;
                }
                return section;
            }

            @Nullable
            private SuperClusterTopology.BoundaryLinks boundaryLinks(
                    Direction face,
                    int yShift,
                    CapturedBase target) {
                int index = baseLinkSlot(key.section(), target.key.section(), face);
                if (boundaryTargets[index] != target.topology) {
                    boundaryTargets[index] = target.topology;
                    boundaries[index] = new BaseBoundaryCacheKey(
                            dimension,
                            topology,
                            target.topology,
                            face
                    );
                    readyBoundaries[index] = null;
                }
                SuperClusterTopology.BoundaryLinks ready = readyBoundaries[index];
                if (ready == null) {
                    CapturedBoundary<SuperClusterTopology.BoundaryLinks> captured =
                            boundarySnapshot.get(boundaries[index]);
                    ready = captured == null ? null : captured.value;
                    readyBoundaries[index] = ready;
                }
                return ready;
            }
        }
    }

    private static BlockPos componentAnchor(SectionPos section,
                                            BaseClusterTopology topology,
                                            int componentId) {
        int anchor = topology.componentAnchorCell(componentId);
        return new BlockPos(
                section.minBlockX() + BaseClusterTopology.x(anchor),
                section.minBlockY() + BaseClusterTopology.y(anchor),
                section.minBlockZ() + BaseClusterTopology.z(anchor)
        );
    }

    private static long edgeId(MacroSearch.Endpoint from, MacroSearch.Endpoint to) {
        long fromId = from.id();
        long toId = to.id();
        if ((fromId & ~0xffff_ffffL) != 0L || (toId & ~0xffff_ffffL) != 0L) {
            throw new IllegalStateException("macro endpoint ID exceeds the collision-free edge range");
        }
        return fromId << 32 | toId;
    }

    private static boolean sameComponent(@Nullable ComponentBinding first,
                                         @Nullable ComponentBinding second) {
        return first != null && second != null
                && first.cluster().equals(second.cluster())
                && first.componentId() == second.componentId();
    }

    private static boolean sameAggregate(@Nullable AggregateBinding first,
                                         @Nullable AggregateBinding second) {
        return first != null && second != null
                && first.cluster().equals(second.cluster())
                && first.aggregateId() == second.aggregateId();
    }

    private record AggregateBinding(SuperCacheKey cluster,
                                    int aggregateId,
                                    MacroSearch.AggregateEndpoint endpoint) {
    }

    private record AggregateWitness(MacroComponentKey source, MacroComponentKey target,
                                    @Nullable Direction face) {}

    /** Runtime-lock materialized witness data handed to the worker with no service access. */
    private record PreparedWitness(BlockPos sourceAnchor,
                                   MacroSearch.ComponentEndpoint target,
                                   MacroSearch.Connection aggregateConnection) {
    }

    private record ComponentBinding(ClusterKey cluster,
                                    int componentId,
                                    MacroSearch.ComponentEndpoint endpoint) {
    }

    private interface ComponentAdmission {
        boolean allowsSection(SectionPos section);

        boolean allowsComponent(SectionPos section, int componentId);

        default boolean allowsTransition(SectionPos sourceSection,
                                         int sourceComponentId,
                                         SectionPos targetSection,
                                         int targetComponentId) {
            return true;
        }
    }

}
