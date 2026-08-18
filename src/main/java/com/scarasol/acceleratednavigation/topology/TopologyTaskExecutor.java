package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.AcceleratedNavigation;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** Shared two-worker executor for searches and serial topology construction. */
final class TopologyTaskExecutor {

    private static final int WORKER_COUNT = 2;
    private static final long AGING_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(250L);

    private final Object monitor = new Object();
    private final EnumMap<NavigationScheduler.Priority,
            LinkedHashMap<ResourceKey<Level>, ArrayDeque<Task>>> queues =
            new EnumMap<>(NavigationScheduler.Priority.class);
    private final List<ResourceKey<Level>> dimensionOrder = new ArrayList<>();
    private final int[] dimensionCursors = new int[NavigationScheduler.Priority.values().length];
    private final List<Thread> workers = new ArrayList<>(WORKER_COUNT);
    private final ThreadLocal<Task> currentTask = new ThreadLocal<>();

    private boolean accepting = true;
    private boolean buildRunning;
    private int runningForegroundTasks;
    private int queuedTasks;
    private int liveWorkers = WORKER_COUNT;
    private long sequence;
    private long submittedTasks;
    private long completedTasks;
    private long promotedTasks;
    private long cancelledTasks;
    private long totalQueueWaitNanos;
    private long maximumQueueWaitNanos;
    private Runnable afterDrain = () -> {
    };

    TopologyTaskExecutor(String threadName, int threadPriority) {
        Objects.requireNonNull(threadName, "threadName");
        for (NavigationScheduler.Priority priority : NavigationScheduler.Priority.values()) {
            queues.put(priority, new LinkedHashMap<>());
        }
        for (int index = 0; index < WORKER_COUNT; index++) {
            Thread worker = new Thread(this::run,
                    threadName + "-" + (index + 1));
            worker.setDaemon(true);
            worker.setPriority(Math.max(Thread.MIN_PRIORITY,
                    Math.min(Thread.MAX_PRIORITY, threadPriority)));
            workers.add(worker);
            worker.start();
        }
    }

    TaskHandle submit(ResourceKey<Level> dimension,
                      NavigationScheduler.Priority priority,
                      Runnable command) {
        return submit(dimension, priority, command, WorkKind.BUILD, true);
    }

    TaskHandle submit(ResourceKey<Level> dimension,
                      NavigationScheduler.Priority priority,
                      Runnable command,
                      boolean allowAging) {
        return submit(dimension, priority, command, WorkKind.BUILD, allowAging);
    }

    TaskHandle submitSearch(ResourceKey<Level> dimension,
                            NavigationScheduler.Priority priority,
                            WorkKind kind,
                            boolean allowAging,
                            Runnable command) {
        if (kind == WorkKind.BUILD) {
            throw new IllegalArgumentException("search task cannot use BUILD kind");
        }
        return submit(dimension, priority, command, kind, allowAging);
    }

    TaskHandle submitPrewarm(ResourceKey<Level> dimension,
                              NavigationScheduler.Priority priority,
                              Runnable command) {
        return submit(dimension, priority, command, WorkKind.PREWARM, false);
    }

    private TaskHandle submit(ResourceKey<Level> dimension,
                              NavigationScheduler.Priority priority,
                              Runnable command,
                              WorkKind kind,
                              boolean allowAging) {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(kind, "kind");
        synchronized (monitor) {
            if (!accepting) {
                throw new RejectedExecutionException("topology workers are stopped");
            }
            Task task = new Task(++sequence, dimension, priority, kind, command,
                    System.nanoTime(), allowAging);
            if (!dimensionOrder.contains(dimension)) {
                dimensionOrder.add(dimension);
            }
            queues.get(priority).computeIfAbsent(dimension, ignored -> new ArrayDeque<>())
                    .addLast(task);
            queuedTasks++;
            submittedTasks++;
            monitor.notifyAll();
            return task;
        }
    }

    void shutdown() {
        shutdown(() -> {
        });
    }

    void shutdown(Runnable afterQueuedTasks) {
        Objects.requireNonNull(afterQueuedTasks, "afterQueuedTasks");
        synchronized (monitor) {
            if (!accepting) {
                return;
            }
            accepting = false;
            afterDrain = afterQueuedTasks;
            cancelQueuedSearches();
            monitor.notifyAll();
        }
    }

    Metrics metrics() {
        synchronized (monitor) {
            return new Metrics(
                    queuedTasks,
                    submittedTasks,
                    completedTasks,
                    promotedTasks,
                    cancelledTasks,
                    totalQueueWaitNanos,
                    maximumQueueWaitNanos
            );
        }
    }

    private void run() {
        try {
            while (true) {
                Task task;
                synchronized (monitor) {
                    while (true) {
                        if (queuedTasks == 0 && !accepting) {
                            return;
                        }
                        task = takeNext(System.nanoTime());
                        if (task != null) {
                            break;
                        }
                        try {
                            monitor.wait();
                        } catch (InterruptedException ignored) {
                            if (!accepting) {
                                cancelQueuedSearches();
                            }
                        }
                    }
                }
                try {
                    currentTask.set(task);
                    task.command.run();
                } catch (VirtualMachineError | ThreadDeath fatal) {
                    throw fatal;
                } catch (Throwable failure) {
                    AcceleratedNavigation.LOGGER.error(
                            "Unhandled topology task failure on {}",
                            Thread.currentThread().getName(),
                        failure
                    );
                } finally {
                    currentTask.remove();
                    synchronized (monitor) {
                        completedTasks++;
                        if (task.nextCommand != null && accepting) {
                            // A search continuation keeps the same queue handle.  The
                            // previous command has returned before the handle becomes
                            // runnable again, so one MacroFlight never owns two tasks.
                            if (task.foregroundRunning) {
                                runningForegroundTasks--;
                                task.foregroundRunning = false;
                            }
                            task.command = task.nextCommand;
                            task.nextCommand = null;
                            task.sequence = ++sequence;
                            task.enqueuedNanos = System.nanoTime();
                            task.state = TaskState.QUEUED;
                            queues.get(task.priority)
                                    .computeIfAbsent(task.dimension, ignored -> new ArrayDeque<>())
                                    .addLast(task);
                            queuedTasks++;
                            submittedTasks++;
                        } else {
                            task.nextCommand = null;
                            task.state = TaskState.COMPLETED;
                            if (isBuildTask(task.kind)) {
                                buildRunning = false;
                            }
                            if (task.foregroundRunning) {
                                runningForegroundTasks--;
                                task.foregroundRunning = false;
                            }
                        }
                        monitor.notifyAll();
                    }
                }
            }
        } finally {
            Runnable completion = null;
            synchronized (monitor) {
                liveWorkers--;
                if (liveWorkers == 0) {
                    completion = afterDrain;
                }
                monitor.notifyAll();
            }
            if (completion != null) {
                try {
                    completion.run();
                } catch (Throwable failure) {
                    AcceleratedNavigation.LOGGER.error(
                            "Could not finish topology worker shutdown",
                            failure
                    );
                }
            }
        }
    }

    /** Requeues the currently running command without creating a second task handle. */
    boolean requeueCurrent(NavigationScheduler.Priority continuationPriority,
                           WorkKind continuationKind,
                           Runnable continuation) {
        Objects.requireNonNull(continuationPriority, "continuationPriority");
        Objects.requireNonNull(continuationKind, "continuationKind");
        Objects.requireNonNull(continuation, "continuation");
        synchronized (monitor) {
            Task task = currentTask.get();
            if (task == null || task.state != TaskState.RUNNING || !accepting
                    || task.nextCommand != null) {
                return false;
            }
            task.priority = continuationPriority;
            task.kind = continuationKind;
            task.nextCommand = continuation;
            return true;
        }
    }

    private Task takeNext(long now) {
        int selectedRank = Integer.MAX_VALUE;
        int selectedKind = Integer.MAX_VALUE;
        for (NavigationScheduler.Priority priority : NavigationScheduler.Priority.values()) {
            for (ArrayDeque<Task> dimension : queues.get(priority).values()) {
                Task candidate = bestRunnable(dimension, now, Integer.MAX_VALUE,
                        Integer.MAX_VALUE);
                if (candidate == null) {
                    continue;
                }
                int rank = effectiveRank(candidate, now);
                int kind = candidate.kind.order;
                if (rank < selectedRank || rank == selectedRank && kind < selectedKind) {
                    selectedRank = rank;
                    selectedKind = kind;
                }
            }
        }
        if (selectedRank == Integer.MAX_VALUE) {
            return null;
        }

        Task selected = null;
        int cursor = dimensionCursors[selectedRank];
        for (int offset = 0; offset < dimensionOrder.size(); offset++) {
            int index = (cursor + offset) % dimensionOrder.size();
            ResourceKey<Level> dimension = dimensionOrder.get(index);
            for (NavigationScheduler.Priority priority : NavigationScheduler.Priority.values()) {
                ArrayDeque<Task> queue = queues.get(priority).get(dimension);
                Task candidate = queue == null
                        ? null
                        : bestRunnable(queue, now, selectedRank, selectedKind);
                if (candidate != null
                        && (selected == null || candidate.sequence < selected.sequence)) {
                    selected = candidate;
                }
            }
            if (selected != null) {
                dimensionCursors[selectedRank] = (index + 1) % dimensionOrder.size();
                break;
            }
        }
        if (selected == null) {
            throw new IllegalStateException("topology worker queue is inconsistent");
        }

        LinkedHashMap<ResourceKey<Level>, ArrayDeque<Task>> band =
                queues.get(selected.priority);
        ArrayDeque<Task> dimensionQueue = band.get(selected.dimension);
        if (dimensionQueue == null || !dimensionQueue.remove(selected)) {
            throw new IllegalStateException("topology worker dimension queue is inconsistent");
        }
        if (dimensionQueue.isEmpty()) {
            band.remove(selected.dimension);
        }
        queuedTasks--;
        selected.state = TaskState.RUNNING;
        if (isBuildTask(selected.kind)) {
            buildRunning = true;
        }
        if (selected.kind != WorkKind.PREWARM) {
            runningForegroundTasks++;
            selected.foregroundRunning = true;
        }
        long waited = Math.max(0L, now - selected.enqueuedNanos);
        totalQueueWaitNanos += waited;
        maximumQueueWaitNanos = Math.max(maximumQueueWaitNanos, waited);
        return selected;
    }

    private Task bestRunnable(ArrayDeque<Task> queue,
                              long now,
                              int requiredRank,
                              int requiredKind) {
        Task selected = null;
        for (Task task : queue) {
            if (isBuildTask(task.kind) && buildRunning) {
                continue;
            }
            if (task.kind == WorkKind.PREWARM && hasForegroundWork()) {
                continue;
            }
            int rank = effectiveRank(task, now);
            int kind = task.kind.order;
            if (requiredRank != Integer.MAX_VALUE
                    && (rank != requiredRank || kind != requiredKind)) {
                continue;
            }
            if (selected == null || rank < effectiveRank(selected, now)
                    || rank == effectiveRank(selected, now) && (kind < selected.kind.order
                    || kind == selected.kind.order && task.sequence < selected.sequence)) {
                selected = task;
            }
        }
        return selected;
    }

    private boolean hasForegroundWork() {
        if (runningForegroundTasks > 0) {
            return true;
        }
        for (LinkedHashMap<ResourceKey<Level>, ArrayDeque<Task>> band : queues.values()) {
            for (ArrayDeque<Task> queue : band.values()) {
                for (Task task : queue) {
                    if (task.kind != WorkKind.PREWARM) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isBuildTask(WorkKind kind) {
        return kind == WorkKind.BUILD || kind == WorkKind.PREWARM;
    }

    private static int effectiveRank(Task task, long now) {
        int baseRank = task.priority.ordinal();
        if (!task.allowAging || task.kind == WorkKind.PREWARM) {
            return baseRank;
        }
        long waited = Math.max(0L, now - task.enqueuedNanos);
        int promotions = (int) Math.min(baseRank, waited / AGING_INTERVAL_NANOS);
        return baseRank - promotions;
    }

    private void cancelQueuedSearches() {
        for (NavigationScheduler.Priority priority : NavigationScheduler.Priority.values()) {
            LinkedHashMap<ResourceKey<Level>, ArrayDeque<Task>> band = queues.get(priority);
            for (ArrayDeque<Task> queue : new ArrayList<>(band.values())) {
                for (Task task : new ArrayList<>(queue)) {
                    if (task.kind == WorkKind.BUILD) {
                        continue;
                    }
                    queue.remove(task);
                    task.state = TaskState.CANCELLED;
                    queuedTasks--;
                    cancelledTasks++;
                }
            }
            band.values().removeIf(ArrayDeque::isEmpty);
        }
    }

    interface TaskHandle {
        void promote(NavigationScheduler.Priority priority);

        void reprioritize(NavigationScheduler.Priority priority);

        void promoteBuild();

        void enableAging();

        boolean cancel();
    }

    record Metrics(int queuedTasks,
                   long submittedTasks,
                   long completedTasks,
                   long promotedTasks,
                   long cancelledTasks,
                   long totalQueueWaitNanos,
                   long maximumQueueWaitNanos) {
    }

    enum WorkKind {
        BUILD(0),
        QUICK_SEARCH(1),
        LONG_SEARCH(2),
        PREWARM(3);

        private final int order;

        WorkKind(int order) {
            this.order = order;
        }
    }

    private enum TaskState {
        QUEUED,
        RUNNING,
        COMPLETED,
        CANCELLED
    }

    private final class Task implements TaskHandle {
        private long sequence;
        private final ResourceKey<Level> dimension;
        private Runnable command;
        private Runnable nextCommand;
        private long enqueuedNanos;
        private boolean allowAging;
        private NavigationScheduler.Priority priority;
        private WorkKind kind;
        private TaskState state = TaskState.QUEUED;
        private boolean foregroundRunning;

        private Task(long sequence,
                     ResourceKey<Level> dimension,
                     NavigationScheduler.Priority priority,
                     WorkKind kind,
                     Runnable command,
                     long enqueuedNanos,
                     boolean allowAging) {
            this.sequence = sequence;
            this.dimension = dimension;
            this.priority = priority;
            this.kind = kind;
            this.command = command;
            this.enqueuedNanos = enqueuedNanos;
            this.allowAging = allowAging;
        }

        @Override
        public void promote(NavigationScheduler.Priority requested) {
            Objects.requireNonNull(requested, "requested");
            synchronized (monitor) {
                if (state != TaskState.QUEUED || !requested.higherThan(priority)) {
                    return;
                }
                moveTo(requested);
            }
        }

        @Override
        public void reprioritize(NavigationScheduler.Priority requested) {
            Objects.requireNonNull(requested, "requested");
            synchronized (monitor) {
                if (state != TaskState.QUEUED || requested == priority) {
                    return;
                }
                moveTo(requested);
            }
        }

        @Override
        public void promoteBuild() {
            synchronized (monitor) {
                if (state == TaskState.QUEUED && kind == WorkKind.PREWARM) {
                    kind = WorkKind.BUILD;
                    allowAging = true;
                    monitor.notifyAll();
                }
            }
        }

        @Override
        public void enableAging() {
            synchronized (monitor) {
                if (kind != WorkKind.PREWARM) {
                    allowAging = true;
                }
            }
        }

        private void moveTo(NavigationScheduler.Priority requested) {
            boolean promoted = requested.higherThan(priority);
            ArrayDeque<Task> previous = queues.get(priority).get(dimension);
            if (previous == null || !previous.remove(this)) {
                throw new IllegalStateException("queued topology task is missing from its band");
            }
            if (previous.isEmpty()) {
                queues.get(priority).remove(dimension);
            }
            priority = requested;
            queues.get(priority).computeIfAbsent(dimension, ignored -> new ArrayDeque<>())
                    .addLast(this);
            if (promoted) {
                promotedTasks++;
            }
            monitor.notifyAll();
        }

        @Override
        public boolean cancel() {
            synchronized (monitor) {
                ArrayDeque<Task> dimensionQueue = queues.get(priority).get(dimension);
                if (state != TaskState.QUEUED || dimensionQueue == null
                        || !dimensionQueue.remove(this)) {
                    return false;
                }
                if (dimensionQueue.isEmpty()) {
                    queues.get(priority).remove(dimension);
                }
                state = TaskState.CANCELLED;
                queuedTasks--;
                cancelledTasks++;
                monitor.notifyAll();
                return true;
            }
        }
    }
}
