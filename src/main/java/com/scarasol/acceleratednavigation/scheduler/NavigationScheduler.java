package com.scarasol.acceleratednavigation.scheduler;

import com.scarasol.acceleratednavigation.AcceleratedNavigation;
import com.scarasol.acceleratednavigation.api.ResumableSearch;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
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
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.LongSupplier;
import java.util.function.BooleanSupplier;

/**
 * Main-thread request queue with the original END/ready dispatch points.
 * This isolated candidate has no navigation execution budget.
 */
public final class NavigationScheduler {

    // Compatibility fields for existing telemetry; zero means no budget is granted.
    public static final long TICK_BUDGET_NANOS = 0L;
    public static final long TARGET_NANOS = 0L;
    public static final long TAIL_RESERVE_NANOS = 0L;
    private static final long AGING_INTERVAL_NANOS = 250_000_000L;
    private static final int MAX_PENDING_REQUESTS = 1_024;

    private static final Map<MinecraftServer, NavigationScheduler> SERVICES = new IdentityHashMap<>();
    private static final Set<MinecraftServer> CLOSED = Collections.newSetFromMap(new WeakHashMap<>());

    private final MinecraftServer server;
    private final EnumMap<Priority, BandQueue> queues = new EnumMap<>(Priority.class);
    private final Map<RequestKey, QueuedSearch<?>> pending = new LinkedHashMap<>();
    private final ArrayList<ResourceKey<Level>> dimensionOrder = new ArrayList<>();
    private final int[] dimensionCursors = new int[Priority.values().length];

    private long tickStartedNanos;
    private long requestSequence;
    private boolean draining;
    private int accountingDepth;
    private long accountingStarted;
    private LongSupplier clock = System::nanoTime;
    private long chargedNanos;
    private boolean inEnvelope;
    private boolean startSeen;
    private boolean endSeen;
    private boolean closed;
    private boolean readyDispatchQueued;
    private boolean readyWindowValid;
    private long chargeAtStart;
    private long chargeAtDrainEnd;
    private long previousChargeAtEnd;
    private long drainEndedNanos;
    private long outsideNavigationNanos;
    private long validTicks;
    private long skippedTicks;
    private int pendingBeforeDrain;
    private StopReason stopReason = StopReason.NO_END;
    private final long[] stopCounts = new long[StopReason.values().length];
    private TickSample lastTick;
    private long experimentOriginNanos;
    private long experimentDrains;
    private long experimentSteps;
    private final long[] experimentSpans = new long[3 * 65_536];
    private int experimentSpanCount;
    private boolean experimentSpanOverflow;

    private NavigationScheduler(MinecraftServer server) {
        this.server = server;
        for (Priority priority : Priority.values()) {
            queues.put(priority, new BandQueue());
        }
    }

    public static NavigationScheduler forServer(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        synchronized (SERVICES) {
            if (CLOSED.contains(server)) throw new RejectedExecutionException("scheduler is stopped");
            return SERVICES.computeIfAbsent(server, NavigationScheduler::new);
        }
    }

    public static void beginServerTick(MinecraftServer server) {
        forServer(server).beginTick();
    }

    public static NavigationScheduler beginTickEnvelope(MinecraftServer server, BooleanSupplier haveTime) {
        NavigationScheduler scheduler = forServer(server);
        scheduler.beginEnvelope(haveTime);
        return scheduler;
    }

    public static void endServerTick(MinecraftServer server, boolean haveTime) {
        forServer(server).endTick(haveTime);
    }

    public static void shutdown(MinecraftServer server) {
        NavigationScheduler scheduler;
        synchronized (SERVICES) {
            CLOSED.add(server);
            scheduler = SERVICES.remove(server);
        }
        if (scheduler != null) {
            scheduler.closed = true;
            scheduler.cancelAll();
            AcceleratedNavigation.LOGGER.debug(
                    "AN_SCHEDULER_EXPERIMENT variant=U drains={} steps={}",
                    scheduler.experimentDrains, scheduler.experimentSteps);
            scheduler.writeExperimentSpans();
        }
    }

    public <R> CompletableFuture<R> submitStrict(ResourceKey<Level> dimension,
                                                  UUID owner,
                                                  Priority priority,
                                                  ResumableSearch<R> search) {
        return submit(dimension, owner, priority, search);
    }

    /**
     * Schedules an unmodified synchronous backend as one atomic call. The
     * queue controls when it starts and observes its runtime without imposing a
     * navigation allowance or deadline.
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
        return submit(dimension, owner, priority, new AtomicSearch<>(task));
    }

    public boolean cancel(ResourceKey<Level> dimension, UUID owner) {
        requireServerThread();
        try (TimeAccount ignored = account()) {
        QueuedSearch<?> request = pending.remove(new RequestKey(dimension, owner));
        if (request == null) {
            return false;
        }
        cancelQueued(request);
        return true;
        }
    }

    public boolean promote(ResourceKey<Level> dimension, UUID owner, Priority priority) {
        requireServerThread();
        requireOpen();
        try (TimeAccount ignored = account()) {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(priority, "priority");
        QueuedSearch<?> request = pending.get(new RequestKey(dimension, owner));
        if (request == null || !priority.higherThan(request.priority)) {
            return false;
        }
        return reprioritize(request, priority);
        }
    }

    public boolean reprioritize(ResourceKey<Level> dimension, UUID owner, Priority priority) {
        requireServerThread();
        try (TimeAccount ignored = account()) {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(priority, "priority");
        QueuedSearch<?> request = pending.get(new RequestKey(dimension, owner));
        return request != null && request.priority != priority && reprioritize(request, priority);
        }
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

    private <R> CompletableFuture<R> submit(ResourceKey<Level> dimension,
                                             UUID owner,
                                             Priority priority,
                                              ResumableSearch<R> search) {
        requireServerThread();
        requireOpen();
        try (TimeAccount ignored = account()) {
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
        if (pending.size() >= MAX_PENDING_REQUESTS) {
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
                new CompletableFuture<>(),
                clock.getAsLong()
        );
        pending.put(key, request);
        enqueue(request);
        return request.future;
        }
    }

    private void beginEnvelope(BooleanSupplier haveTime) {
        requireServerThread();
        requireOpen();
        if (inEnvelope) throw new IllegalStateException("nested navigation tick envelope");
        readyWindowValid = false;
        Objects.requireNonNull(haveTime, "haveTime");
        tickStartedNanos = clock.getAsLong();
        experimentOriginNanos = tickStartedNanos;
        chargeAtStart = totalAccounted(tickStartedNanos);
        outsideNavigationNanos = Math.max(0L, chargeAtStart - previousChargeAtEnd);
        inEnvelope = true;
        startSeen = endSeen = false;
        drainEndedNanos = 0L;
        stopReason = StopReason.NO_END;
    }

    private void beginTick() {
        requireServerThread();
        requireOpen();
        if (!inEnvelope || startSeen) throw new IllegalStateException("missing envelope or duplicate navigation START");
        startSeen = true;
    }

    public void finishTickEnvelope(boolean completedNormally) {
        requireServerThread();
        if (!inEnvelope) throw new IllegalStateException("missing navigation tick envelope");
        long now = clock.getAsLong();
        long chargeAtEnd = totalAccounted(now);
        try {
            if (completedNormally && startSeen && endSeen && !closed) {
                long full = Math.max(0L, now - tickStartedNanos);
                long navigation = Math.max(0L, chargeAtEnd - chargeAtStart);
                long tail = Math.max(0L, now - drainEndedNanos - (chargeAtEnd - chargeAtDrainEnd));
                validTicks = increment(validTicks);
                stopCounts[stopReason.ordinal()] = increment(stopCounts[stopReason.ordinal()]);
                lastTick = new TickSample(validTicks, full, navigation, outsideNavigationNanos,
                        0L, 0L, tail, 0L, 0L, 0L,
                        pendingBeforeDrain, pending.size(), stopReason);
                readyWindowValid = true;
            } else skippedTicks = increment(skippedTicks);
        } finally {
            previousChargeAtEnd = chargeAtEnd;
            inEnvelope = startSeen = endSeen = false;
            tickStartedNanos = 0L;
        }
        if (readyWindowValid && !pending.isEmpty()) requestReadyDispatch();
    }

    private void endTick(boolean haveTime) {
        requireServerThread();
        requireOpen();
        if (!inEnvelope || !startSeen || endSeen)
            throw new IllegalStateException("missing START or duplicate navigation END");
        endSeen = true;
        pendingBeforeDrain = pending.size();
        try (TimeAccount ignored = account()) {
            if (pending.isEmpty()) stopReason = StopReason.EMPTY;
            else drain();
        } finally {
            drainEndedNanos = clock.getAsLong();
            chargeAtDrainEnd = totalAccounted(drainEndedNanos);
        }
    }

    /**
     * Queue a main-thread ready callback at the original dispatch point.
     */
    public void requestReadyDispatch() {
        requireServerThread();
        try (TimeAccount ignored = account()) {
        if (readyDispatchQueued || draining || !readyDispatchAllowed()) return;
        readyDispatchQueued = true;
        try {
            server.tell(new TickTask(server.getTickCount(), this::drainReady));
        } catch (RejectedExecutionException rejected) {
            readyDispatchQueued = false;
        }
        }
    }

    private boolean readyDispatchAllowed() {
        return !closed && !inEnvelope && readyWindowValid && !pending.isEmpty();
    }

    private void drainReady() {
        readyDispatchQueued = false;
        // Existing accounting records this work in chargedNanos and the next
        // envelope's outsideNavigationNanos; the completed tick sample stays intact.
        try (TimeAccount ignored = account()) {
            if (draining || !readyDispatchAllowed()) return;
            drain();
        }
    }

    private void drain() {
        if (draining) {
            return;
        }
        draining = true;
        long experimentStarted = clock.getAsLong();
        experimentDrains++;
        try {
            while (!closed && !pending.isEmpty()) {
                QueuedSearch<?> request = takeNext(clock.getAsLong());
                if (request == null) {
                    stopReason = StopReason.DEPENDENCY;
                    return;
                }
                request.queued = false;
                if (request.future.isDone() || pending.get(request.key) != request) {
                    if (pending.remove(request.key, request)) {
                        cancelSafely(request.search);
                    }
                    continue;
                }

                long sliceStarted = clock.getAsLong();
                experimentSteps++;
                ResumableSearch.Status status;
                try {
                    status = Objects.requireNonNull(
                            request.search.step(Integer.MAX_VALUE, Long.MAX_VALUE),
                            "search step returned null status"
                    );
                } catch (Throwable failure) {
                    rethrowFatal(failure);
                    long used = Math.max(0L, clock.getAsLong() - sliceStarted);
                    pending.remove(request.key, request);
                    cancelSafely(request.search);
                    request.future.completeExceptionally(failure);
                    AcceleratedNavigation.LOGGER.error(
                            "Navigation search for {} failed after {} ns",
                            request.key.owner,
                            used,
                            failure
                    );
                    continue;
                }

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
                try {
                    completeRequest(request, status);
                } catch (Throwable failure) {
                    rethrowFatal(failure);
                    cancelSafely(request.search);
                    request.future.completeExceptionally(failure);
                    AcceleratedNavigation.LOGGER.error("Navigation result publication for {} failed", request.key.owner, failure);
                }
            }
            stopReason = pending.isEmpty() ? StopReason.EMPTY : StopReason.DEPENDENCY;
        } finally {
            recordExperimentSpan(experimentOriginNanos, experimentStarted, clock.getAsLong());
            draining = false;
        }
    }

    private void recordExperimentSpan(long origin, long start, long end) {
        int offset = experimentSpanCount * 3;
        if (offset == experimentSpans.length) { experimentSpanOverflow = true; return; }
        experimentSpans[offset] = origin;
        experimentSpans[offset + 1] = start;
        experimentSpans[offset + 2] = end;
        experimentSpanCount++;
    }

    private void writeExperimentSpans() {
        String output = System.getProperty("an.production.output");
        if (output == null) return;
        StringBuilder json = new StringBuilder("{\"variant\":\"U\",\"overflow\":")
                .append(experimentSpanOverflow).append(",\"spans\":[");
        for (int i = 0; i < experimentSpanCount; i++) {
            if (i > 0) json.append(',');
            int offset = i * 3;
            json.append('[').append(experimentSpans[offset]).append(',')
                    .append(experimentSpans[offset + 1]).append(',')
                    .append(experimentSpans[offset + 2]).append(']');
        }
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of(output, "scheduler-spans.json"),
                    json.append("]}").toString());
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private void wake(QueuedSearch<?> request) {
        if (!server.isSameThread()) {
            server.execute(() -> wake(request));
            return;
        }
        if (closed || !request.parked || request.future.isDone()
                || pending.get(request.key) != request) {
            return;
        }
        request.parked = false;
        try (TimeAccount ignored = account()) { enqueue(request); }
    }

    private void enqueue(QueuedSearch<?> request) {
        if (request.queued || request.parked || request.future.isDone()
                || pending.get(request.key) != request) {
            return;
        }
        request.queued = true;
        if (!dimensionOrder.contains(request.key.dimension)) {
            dimensionOrder.add(request.key.dimension);
        }
        queues.get(request.priority).add(request);
    }

    public TimeAccount account() {
        requireServerThread();
        if (accountingDepth++ == 0) accountingStarted = clock.getAsLong();
        return new TimeAccount();
    }

    public final class TimeAccount implements AutoCloseable {
        private boolean closed;
        @Override public void close() {
            requireServerThread();
            if (closed) throw new IllegalStateException("duplicate accounting close");
            closed = true;
            if (--accountingDepth == 0) {
                long used = Math.max(0L, clock.getAsLong() - accountingStarted);
                chargedNanos += used;
            }
        }
    }

    private long activeElapsed(long now) {
        return accountingDepth == 0 ? 0L : Math.max(0L, now - accountingStarted);
    }

    private long totalAccounted(long now) { return chargedNanos + activeElapsed(now); }
    private static long increment(long value) { return value == Long.MAX_VALUE ? value : value + 1; }

    public record Metrics(long chargedNanos, long availableNanos, int pendingRequests) {}

    public Metrics metrics() {
        requireServerThread();
        long now = clock.getAsLong();
        return new Metrics(totalAccounted(now), 0L, pending.size());
    }

    public enum StopReason { NO_END, EMPTY, BALANCE, HEADROOM, SERVER_NO_TIME, DEPENDENCY, START_THRESHOLD }

    public record TickSample(long sequence, long fullNanos, long navigationNanos, long outsideNavigationNanos,
            long grantNanos, long tailReserveNanos, long measuredTailNanos,
            long balanceAfterRefillNanos, long balanceBeforeDrainNanos, long finalBalanceNanos,
            int pendingBeforeDrain, int pendingAfterDrain, StopReason stopReason) {}

    public record BudgetMetrics(long validTicks, long skippedTicks, int runnableRequests,
            int parkedRequests, TickSample lastTick, long emptyStops, long balanceStops, long headroomStops,
            long serverNoTimeStops, long dependencyStops, long thresholdStops) {}

    public BudgetMetrics budgetMetrics() {
        requireServerThread();
        int runnable = 0;
        for (BandQueue queue : queues.values()) runnable += queue.size;
        return new BudgetMetrics(validTicks, skippedTicks, runnable,
                Math.max(0, pending.size() - runnable), lastTick, stopCounts[StopReason.EMPTY.ordinal()],
                stopCounts[StopReason.BALANCE.ordinal()], stopCounts[StopReason.HEADROOM.ordinal()],
                stopCounts[StopReason.SERVER_NO_TIME.ordinal()], stopCounts[StopReason.DEPENDENCY.ordinal()],
                stopCounts[StopReason.START_THRESHOLD.ordinal()]);
    }

    private void requireOpen() {
        if (closed) throw new RejectedExecutionException("scheduler is stopped");
    }

    private static <R> void completeRequest(QueuedSearch<R> request,
                                            ResumableSearch.Status status) {
        request.future.complete(status == ResumableSearch.Status.SUCCEEDED
                ? request.search.result()
                : null);
    }

    private QueuedSearch<?> takeNext(long now) {
        int selectedRank = Integer.MAX_VALUE;
        for (Priority priority : Priority.values()) {
            for (ArrayDeque<QueuedSearch<?>> dimension
                    : queues.get(priority).byDimension.values()) {
                QueuedSearch<?> candidate = dimension.peekFirst();
                if (candidate != null) {
                    selectedRank = Math.min(selectedRank, effectiveRank(candidate, now));
                }
            }
        }
        if (selectedRank == Integer.MAX_VALUE || dimensionOrder.isEmpty()) {
            return null;
        }
        int cursor = dimensionCursors[selectedRank];
        for (int offset = 0; offset < dimensionOrder.size(); offset++) {
            int index = (cursor + offset) % dimensionOrder.size();
            ResourceKey<Level> dimension = dimensionOrder.get(index);
            QueuedSearch<?> selected = null;
            for (Priority priority : Priority.values()) {
                QueuedSearch<?> candidate = queues.get(priority).peek(dimension);
                if (candidate != null && effectiveRank(candidate, now) == selectedRank
                        && (selected == null || candidate.sequence < selected.sequence)) {
                    selected = candidate;
                }
            }
            if (selected != null) {
                dimensionCursors[selectedRank] = (index + 1) % dimensionOrder.size();
                queues.get(selected.priority).removeFirst(selected);
                return selected;
            }
        }
        throw new IllegalStateException("navigation queue is inconsistent");
    }

    private static int effectiveRank(QueuedSearch<?> request, long now) {
        int rank = request.priority.ordinal();
        long waited = Math.max(0L, now - request.enqueuedNanos);
        return rank - (int) Math.min(rank, waited / AGING_INTERVAL_NANOS);
    }

    private void cancelQueued(QueuedSearch<?> request) {
        queues.get(request.priority).remove(request);
        request.queued = false;
        request.parked = false;
        cancelSafely(request.search);
        request.future.cancel(false);
    }

    private void cancelAll() {
        try (TimeAccount ignored = account()) {
        for (QueuedSearch<?> request : new ArrayList<>(pending.values())) {
            cancelSafely(request.search);
            request.future.cancel(false);
        }
        pending.clear();
        queues.values().forEach(BandQueue::clear);
        }
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
        PLAYER_PURSUIT,
        PLAYER_NEARBY,
        ACTIVE,
        BACKGROUND;

        public boolean higherThan(Priority other) {
            return ordinal() < Objects.requireNonNull(other, "other").ordinal();
        }
    }

    private record RequestKey(ResourceKey<Level> dimension, UUID owner) {
    }

    private static final class QueuedSearch<R> {
        private final long sequence;
        private final RequestKey key;
        private Priority priority;
        private final ResumableSearch<R> search;
        private final CompletableFuture<R> future;
        private final long enqueuedNanos;
        private boolean queued;
        private boolean parked;

        private QueuedSearch(long sequence,
                             RequestKey key,
                             Priority priority,
                             ResumableSearch<R> search,
                             CompletableFuture<R> future,
                             long enqueuedNanos) {
            this.sequence = sequence;
            this.key = key;
            this.priority = priority;
            this.search = search;
            this.future = future;
            this.enqueuedNanos = enqueuedNanos;
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
        private int size;

        private void add(QueuedSearch<?> request) {
            ArrayDeque<QueuedSearch<?>> dimensionQueue = byDimension.get(request.key.dimension);
            if (dimensionQueue == null) {
                dimensionQueue = new ArrayDeque<>();
                byDimension.put(request.key.dimension, dimensionQueue);
            }
            if (dimensionQueue.isEmpty()
                    || dimensionQueue.peekLast().sequence < request.sequence) {
                dimensionQueue.addLast(request);
            } else {
                ArrayDeque<QueuedSearch<?>> reordered = new ArrayDeque<>(dimensionQueue.size() + 1);
                boolean inserted = false;
                for (QueuedSearch<?> queued : dimensionQueue) {
                    if (!inserted && request.sequence < queued.sequence) {
                        reordered.addLast(request);
                        inserted = true;
                    }
                    reordered.addLast(queued);
                }
                if (!inserted) {
                    reordered.addLast(request);
                }
                dimensionQueue.clear();
                dimensionQueue.addAll(reordered);
            }
            size++;
        }

        private QueuedSearch<?> peek(ResourceKey<Level> dimension) {
            ArrayDeque<QueuedSearch<?>> queue = byDimension.get(dimension);
            return queue == null ? null : queue.peekFirst();
        }

        private void removeFirst(QueuedSearch<?> request) {
            ArrayDeque<QueuedSearch<?>> dimensionQueue = byDimension.get(request.key.dimension);
            if (dimensionQueue == null || dimensionQueue.removeFirst() != request) {
                throw new IllegalStateException("navigation dimension queue is inconsistent");
            }
            size--;
            if (dimensionQueue.isEmpty()) {
                byDimension.remove(request.key.dimension);
            }
        }

        private void remove(QueuedSearch<?> request) {
            ArrayDeque<QueuedSearch<?>> dimensionQueue = byDimension.get(request.key.dimension);
            if (dimensionQueue == null || !dimensionQueue.remove(request)) {
                return;
            }
            size--;
            if (dimensionQueue.isEmpty()) {
                byDimension.remove(request.key.dimension);
            }
        }

        private void clear() {
            byDimension.clear();
            size = 0;
        }
    }
}
