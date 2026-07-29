package com.scarasol.acceleratednavigation.scheduler;

import com.scarasol.acceleratednavigation.AcceleratedNavigation;
import com.scarasol.acceleratednavigation.api.ResumableSearch;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;

/**
 * Main-thread cooperative scheduler shared by all navigation backends on one
 * server. It owns time, fairness and request lifecycle, never path semantics.
 */
public final class NavigationScheduler {

    private static final long TICK_REFILL_NANOS = 4_000_000L;
    private static final long TOKEN_CAP_NANOS = 6_000_000L;
    private static final long TICK_HEADROOM_DEADLINE_NANOS = 45_000_000L;
    private static final long MIN_SLICE_NANOS = 500_000L;
    private static final long MAX_SLICE_NANOS = 1_000_000L;
    private static final long MAX_DEFICIT_NANOS = TOKEN_CAP_NANOS * 2L;
    private static final int MAX_EXPANSIONS_PER_SLICE = 64;
    private static final int MAX_PENDING_REQUESTS = 1_024;

    private static final Map<MinecraftServer, NavigationScheduler> SERVICES = new IdentityHashMap<>();

    private final MinecraftServer server;
    private final EnumMap<Priority, BandQueue> queues = new EnumMap<>(Priority.class);
    private final EnumMap<Priority, Long> deficits = new EnumMap<>(Priority.class);
    private final Map<RequestKey, QueuedSearch<?>> pending = new LinkedHashMap<>();

    private long tickStartedNanos;
    private long requestSequence;
    private long tokensNanos;
    private int bandCursor;

    private NavigationScheduler(MinecraftServer server) {
        this.server = server;
        for (Priority priority : Priority.values()) {
            queues.put(priority, new BandQueue());
            deficits.put(priority, 0L);
        }
    }

    public static NavigationScheduler forServer(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        synchronized (SERVICES) {
            return SERVICES.computeIfAbsent(server, NavigationScheduler::new);
        }
    }

    public static void beginServerTick(MinecraftServer server) {
        forServer(server).beginTick();
    }

    public static void endServerTick(MinecraftServer server, boolean haveTime) {
        forServer(server).endTick(haveTime);
    }

    public static void shutdown(MinecraftServer server) {
        NavigationScheduler scheduler;
        synchronized (SERVICES) {
            scheduler = SERVICES.remove(server);
        }
        if (scheduler != null) {
            scheduler.cancelAll();
        }
    }

    public <R> CompletableFuture<R> submitStrict(ResourceKey<Level> dimension,
                                                  UUID owner,
                                                  Priority priority,
                                                  ResumableSearch<R> search) {
        return submit(dimension, owner, priority, search, MIN_SLICE_NANOS, false);
    }

    public <R> CompletableFuture<R> submitDependency(ResourceKey<Level> dimension,
                                                      UUID owner,
                                                      Priority priority,
                                                      ResumableSearch<R> search) {
        return submit(dimension, owner, priority, search, MIN_SLICE_NANOS, true);
    }

    /**
     * Schedules an unmodified synchronous backend as one atomic call. The
     * scheduler controls when it starts and charges its actual runtime as
     * budget debt, but cannot preempt it after invocation.
     */
    public <R> CompletableFuture<R> submitSoft(ResourceKey<Level> dimension,
                                                UUID owner,
                                                Priority priority,
                                                long estimatedCostNanos,
                                                Callable<R> task) {
        if (estimatedCostNanos <= 0L) {
            throw new IllegalArgumentException("estimatedCostNanos must be positive");
        }
        Objects.requireNonNull(task, "task");
        long startThreshold = Math.max(
                MIN_SLICE_NANOS,
                Math.min(MAX_SLICE_NANOS, estimatedCostNanos)
        );
        return submit(dimension, owner, priority, new AtomicSearch<>(task), startThreshold, false);
    }

    public boolean cancel(ResourceKey<Level> dimension, UUID owner) {
        requireServerThread();
        QueuedSearch<?> request = pending.remove(new RequestKey(dimension, owner));
        if (request == null) {
            return false;
        }
        cancelQueued(request);
        return true;
    }

    public boolean promote(ResourceKey<Level> dimension, UUID owner, Priority priority) {
        requireServerThread();
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(priority, "priority");
        QueuedSearch<?> request = pending.get(new RequestKey(dimension, owner));
        if (request == null || !priority.higherThan(request.priority)) {
            return false;
        }
        return reprioritize(request, priority);
    }

    public boolean reprioritize(ResourceKey<Level> dimension, UUID owner, Priority priority) {
        requireServerThread();
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(priority, "priority");
        QueuedSearch<?> request = pending.get(new RequestKey(dimension, owner));
        return request != null && request.priority != priority && reprioritize(request, priority);
    }

    private boolean reprioritize(QueuedSearch<?> request, Priority priority) {
        if (request.queued) {
            queues.get(request.priority).remove(request);
            request.queued = false;
        }
        request.priority = priority;
        if (!request.parked) {
            enqueue(request);
        }
        return true;
    }

    public boolean qualifyDependency(ResourceKey<Level> dimension,
                                     UUID owner,
                                     Priority priority) {
        requireServerThread();
        Objects.requireNonNull(priority, "priority");
        QueuedSearch<?> request = pending.get(new RequestKey(
                Objects.requireNonNull(dimension, "dimension"),
                Objects.requireNonNull(owner, "owner")
        ));
        if (request == null) {
            return false;
        }
        request.dependency = true;
        promote(dimension, owner, priority);
        return true;
    }

    public boolean releaseDependency(ResourceKey<Level> dimension, UUID owner) {
        requireServerThread();
        QueuedSearch<?> request = pending.get(new RequestKey(
                Objects.requireNonNull(dimension, "dimension"),
                Objects.requireNonNull(owner, "owner")
        ));
        if (request == null) {
            return false;
        }
        request.dependency = false;
        if (!request.started && ordinaryRequestCount() > MAX_PENDING_REQUESTS) {
            pending.remove(request.key, request);
            cancelQueued(request);
            return false;
        }
        return true;
    }

    public AdmissionCapacity admissionCapacity() {
        requireServerThread();
        int background = 0;
        int dependency = 0;
        int parked = 0;
        for (QueuedSearch<?> request : pending.values()) {
            if (request.dependency) {
                dependency++;
            } else if (request.priority == Priority.BACKGROUND) {
                background++;
            }
            if (request.parked) {
                parked++;
            }
        }
        return new AdmissionCapacity(
                Math.max(0, MAX_PENDING_REQUESTS - ordinaryRequestCount()),
                background,
                dependency,
                pending.size(),
                parked
        );
    }

    private <R> CompletableFuture<R> submit(ResourceKey<Level> dimension,
                                             UUID owner,
                                              Priority priority,
                                              ResumableSearch<R> search,
                                              long startThresholdNanos,
                                              boolean dependency) {
        requireServerThread();
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(search, "search");

        if (search.status() != ResumableSearch.Status.RUNNING) {
            return CompletableFuture.completedFuture(
                    search.status() == ResumableSearch.Status.SUCCEEDED ? search.result() : null
            );
        }

        RequestKey key = new RequestKey(dimension, owner);
        QueuedSearch<?> replaced = pending.remove(key);
        if (replaced != null) {
            cancelQueued(replaced);
        }
        if (!dependency && ordinaryRequestCount() >= MAX_PENDING_REQUESTS
                && (priority == Priority.BACKGROUND || !evictNewestBackgroundRequest())) {
            cancelSafely(search);
            return CompletableFuture.failedFuture(
                    new RejectedExecutionException("accelerated navigation queue is full")
            );
        }

        QueuedSearch<R> request = new QueuedSearch<>(
                ++requestSequence,
                key,
                priority,
                search,
                startThresholdNanos,
                new CompletableFuture<>(),
                dependency
        );
        pending.put(key, request);
        enqueue(request);
        return request.future;
    }

    private void beginTick() {
        requireServerThread();
        tickStartedNanos = System.nanoTime();
        tokensNanos = Math.min(TOKEN_CAP_NANOS, tokensNanos + TICK_REFILL_NANOS);
    }

    private void endTick(boolean haveTime) {
        requireServerThread();
        if (!haveTime || pending.isEmpty() || tickStartedNanos == 0L) {
            tickStartedNanos = 0L;
            return;
        }

        long elapsedTickNanos = System.nanoTime() - tickStartedNanos;
        tickStartedNanos = 0L;
        long headroomNanos = TICK_HEADROOM_DEADLINE_NANOS - elapsedTickNanos;
        long budgetNanos = Math.min(Math.max(0L, tokensNanos), headroomNanos);
        if (budgetNanos < MIN_SLICE_NANOS) {
            return;
        }

        addDeficits(budgetNanos);
        long schedulerDeadline = System.nanoTime() + budgetNanos;
        int requestsWithoutProgress = 0;
        while (!pending.isEmpty() && requestsWithoutProgress < pending.size()) {
            long remaining = schedulerDeadline - System.nanoTime();
            if (remaining < MIN_SLICE_NANOS) {
                break;
            }

            Priority priority = nextEligibleBand();
            if (priority == null) {
                break;
            }
            QueuedSearch<?> request = queues.get(priority).poll();
            if (request == null) {
                deficits.put(priority, 0L);
                requestsWithoutProgress++;
                continue;
            }
            request.queued = false;
            if (request.future.isDone() || pending.get(request.key) != request) {
                if (pending.remove(request.key, request)) {
                    cancelSafely(request.search);
                }
                continue;
            }

            long deficit = deficits.get(priority);
            if (deficit < request.startThresholdNanos || remaining < request.startThresholdNanos) {
                enqueue(request);
                requestsWithoutProgress++;
                continue;
            }

            long allowance = Math.min(MAX_SLICE_NANOS, Math.min(deficit, remaining));
            long sliceStarted = System.nanoTime();
            request.started = true;
            ResumableSearch.Status status;
            try {
                status = Objects.requireNonNull(
                        request.search.step(
                                MAX_EXPANSIONS_PER_SLICE,
                                Math.min(sliceStarted + allowance, schedulerDeadline)
                        ),
                        "search step returned null status"
                );
            } catch (Throwable failure) {
                rethrowFatal(failure);
                long used = chargeSlice(priority, sliceStarted);
                pending.remove(request.key, request);
                cancelSafely(request.search);
                request.future.completeExceptionally(failure);
                AcceleratedNavigation.LOGGER.error(
                        "Navigation search for {} failed after {} ns",
                        request.key.owner,
                        used,
                        failure
                );
                requestsWithoutProgress = 0;
                continue;
            }

            chargeSlice(priority, sliceStarted);
            requestsWithoutProgress = 0;
            if (status == ResumableSearch.Status.RUNNING) {
                boolean parked;
                try {
                    parked = request.search.park(() -> wake(request));
                } catch (Throwable failure) {
                    rethrowFatal(failure);
                    pending.remove(request.key, request);
                    cancelSafely(request.search);
                    request.future.completeExceptionally(failure);
                    AcceleratedNavigation.LOGGER.error(
                            "Navigation search parking for {} failed",
                            request.key.owner,
                            failure
                    );
                    continue;
                }
                if (parked) {
                    request.parked = true;
                } else {
                    enqueue(request);
                }
                continue;
            }

            pending.remove(request.key, request);
            completeRequest(request, status);
        }
    }

    private void enqueue(QueuedSearch<?> request) {
        if (request.queued || request.parked || request.future.isDone()
                || pending.get(request.key) != request) {
            return;
        }
        request.queued = true;
        queues.get(request.priority).add(request);
    }

    private void wake(QueuedSearch<?> request) {
        if (!server.isSameThread()) {
            server.execute(() -> wake(request));
            return;
        }
        if (!request.parked || request.future.isDone()
                || pending.get(request.key) != request) {
            return;
        }
        request.parked = false;
        enqueue(request);
    }

    private long chargeSlice(Priority priority, long sliceStarted) {
        long used = Math.max(1L, System.nanoTime() - sliceStarted);
        deficits.put(priority, deficits.get(priority) - used);
        // Negative tokens deliberately carry atomic soft-task overruns forward.
        tokensNanos -= used;
        return used;
    }

    private static <R> void completeRequest(QueuedSearch<R> request,
                                            ResumableSearch.Status status) {
        request.future.complete(status == ResumableSearch.Status.SUCCEEDED
                ? request.search.result()
                : null);
    }

    private void addDeficits(long budgetNanos) {
        int totalWeight = 0;
        for (Priority priority : Priority.values()) {
            if (!queues.get(priority).isEmpty()) {
                totalWeight += priority.weight;
            }
        }
        if (totalWeight == 0) {
            return;
        }
        for (Priority priority : Priority.values()) {
            if (queues.get(priority).isEmpty()) {
                deficits.put(priority, 0L);
                continue;
            }
            long share = budgetNanos * priority.weight / totalWeight;
            deficits.put(
                    priority,
                    Math.min(MAX_DEFICIT_NANOS, deficits.get(priority) + share)
            );
        }
    }

    private Priority nextEligibleBand() {
        Priority[] priorities = Priority.values();
        for (int index = 0; index < priorities.length; index++) {
            int candidateIndex = (bandCursor + index) % priorities.length;
            Priority priority = priorities[candidateIndex];
            if (!queues.get(priority).isEmpty() && deficits.get(priority) >= MIN_SLICE_NANOS) {
                bandCursor = (candidateIndex + 1) % priorities.length;
                return priority;
            }
        }
        return null;
    }

    private boolean evictNewestBackgroundRequest() {
        QueuedSearch<?> newest = null;
        for (QueuedSearch<?> request : pending.values()) {
            if (!request.dependency && request.priority == Priority.BACKGROUND
                    && (newest == null || request.sequence > newest.sequence)) {
                newest = request;
            }
        }
        if (newest == null) {
            return false;
        }
        pending.remove(newest.key, newest);
        cancelQueued(newest);
        return true;
    }

    private int ordinaryRequestCount() {
        int count = 0;
        for (QueuedSearch<?> request : pending.values()) {
            if (!request.dependency) {
                count++;
            }
        }
        return count;
    }

    private void cancelQueued(QueuedSearch<?> request) {
        queues.get(request.priority).remove(request);
        request.queued = false;
        request.parked = false;
        cancelSafely(request.search);
        request.future.cancel(false);
    }

    private void cancelAll() {
        for (QueuedSearch<?> request : new ArrayList<>(pending.values())) {
            cancelSafely(request.search);
            request.future.cancel(false);
        }
        pending.clear();
        queues.values().forEach(BandQueue::clear);
    }

    private static void cancelSafely(ResumableSearch<?> search) {
        try {
            search.cancel();
        } catch (Throwable failure) {
            rethrowFatal(failure);
            AcceleratedNavigation.LOGGER.error("Navigation search cancellation failed", failure);
        }
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError virtualMachineError) {
            throw virtualMachineError;
        }
        if (failure instanceof ThreadDeath threadDeath) {
            throw threadDeath;
        }
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("navigation scheduling must run on the server thread");
        }
    }

    public enum Priority {
        PLAYER_PURSUIT(8),
        PLAYER_NEARBY(4),
        ACTIVE(2),
        BACKGROUND(1);

        private final int weight;

        Priority(int weight) {
            this.weight = weight;
        }

        public boolean higherThan(Priority other) {
            return weight > Objects.requireNonNull(other, "other").weight;
        }
    }

    public record AdmissionCapacity(int freeSlots,
                                    int evictableBackgroundSlots,
                                    int dependencyRequests,
                                    int pendingRequests,
                                    int parkedRequests) {
        public AdmissionCapacity {
            if (freeSlots < 0 || evictableBackgroundSlots < 0 || dependencyRequests < 0
                    || pendingRequests < 0 || parkedRequests < 0) {
                throw new IllegalArgumentException("navigation admission capacity cannot be negative");
            }
        }
    }

    private record RequestKey(ResourceKey<Level> dimension, UUID owner) {
    }

    private static final class QueuedSearch<R> {
        private final long sequence;
        private final RequestKey key;
        private Priority priority;
        private final ResumableSearch<R> search;
        private final long startThresholdNanos;
        private final CompletableFuture<R> future;
        private boolean dependency;
        private boolean queued;
        private boolean parked;
        private boolean started;

        private QueuedSearch(long sequence,
                             RequestKey key,
                             Priority priority,
                             ResumableSearch<R> search,
                             long startThresholdNanos,
                             CompletableFuture<R> future,
                             boolean dependency) {
            this.sequence = sequence;
            this.key = key;
            this.priority = priority;
            this.search = search;
            this.startThresholdNanos = startThresholdNanos;
            this.future = future;
            this.dependency = dependency;
        }
    }

    private static final class AtomicSearch<R> implements ResumableSearch<R> {
        private final Callable<R> task;
        private Status status = Status.RUNNING;
        private R result;

        private AtomicSearch(Callable<R> task) {
            this.task = task;
        }

        @Override
        public Status step(int expansionBudget, long deadlineNanos) {
            if (status != Status.RUNNING) {
                return status;
            }
            try {
                result = task.call();
                status = Status.SUCCEEDED;
                return status;
            } catch (Exception exception) {
                status = Status.FAILED;
                throw new CompletionException(exception);
            }
        }

        @Override
        public Status status() {
            return status;
        }

        @Override
        public R result() {
            return result;
        }

        @Override
        public void cancel() {
            if (status == Status.RUNNING) {
                status = Status.FAILED;
            }
        }
    }

    private static final class BandQueue {
        private final Map<ResourceKey<Level>, ArrayDeque<QueuedSearch<?>>> byDimension =
                new LinkedHashMap<>();
        private final ArrayDeque<ResourceKey<Level>> dimensions = new ArrayDeque<>();
        private int size;

        private void add(QueuedSearch<?> request) {
            ArrayDeque<QueuedSearch<?>> dimensionQueue = byDimension.get(request.key.dimension);
            if (dimensionQueue == null) {
                dimensionQueue = new ArrayDeque<>();
                byDimension.put(request.key.dimension, dimensionQueue);
                dimensions.addLast(request.key.dimension);
            }
            dimensionQueue.addLast(request);
            size++;
        }

        private QueuedSearch<?> poll() {
            while (!dimensions.isEmpty()) {
                ResourceKey<Level> dimension = dimensions.removeFirst();
                ArrayDeque<QueuedSearch<?>> dimensionQueue = byDimension.get(dimension);
                if (dimensionQueue == null || dimensionQueue.isEmpty()) {
                    byDimension.remove(dimension);
                    continue;
                }
                QueuedSearch<?> request = dimensionQueue.removeFirst();
                size--;
                if (dimensionQueue.isEmpty()) {
                    byDimension.remove(dimension);
                } else {
                    dimensions.addLast(dimension);
                }
                return request;
            }
            size = 0;
            return null;
        }

        private void remove(QueuedSearch<?> request) {
            ArrayDeque<QueuedSearch<?>> dimensionQueue = byDimension.get(request.key.dimension);
            if (dimensionQueue == null || !dimensionQueue.remove(request)) {
                return;
            }
            size--;
            if (dimensionQueue.isEmpty()) {
                byDimension.remove(request.key.dimension);
                dimensions.remove(request.key.dimension);
            }
        }

        private boolean isEmpty() {
            return size == 0;
        }

        private void clear() {
            byDimension.clear();
            dimensions.clear();
            size = 0;
        }
    }
}
