package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.AcceleratedNavigation;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Two workers, one serial build permit, and one global foreground order. */
final class TopologyTaskExecutor {

    private static final int WORKER_COUNT = 2;
    private static final long AGING_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(250L);

    private final Object monitor = new Object();
    private final BooleanSupplier prewarmAllowed;
    private final NavigableMap<TaskKey, Task> foreground = new TreeMap<>();
    private final NavigableMap<Long, Task> prewarm = new TreeMap<>();
    private final ThreadLocal<Task> currentTask = new ThreadLocal<>();
    private final Thread[] workers = new Thread[WORKER_COUNT];

    private boolean accepting = true;
    private boolean buildRunning;
    private long sequence;
    private Task controlTask;
    private final long[] queuedByKind = new long[WorkKind.values().length];
    private final long[] runningByKind = new long[WorkKind.values().length];
    private final long[] highestQueuedByKind = new long[WorkKind.values().length];
    private final long[] highestRunningByKind = new long[WorkKind.values().length];
    private final long[] completedByKind = new long[WorkKind.values().length];
    private final long[] failedByKind = new long[WorkKind.values().length];

    TopologyTaskExecutor(BooleanSupplier prewarmAllowed) {
        this.prewarmAllowed = Objects.requireNonNull(prewarmAllowed, "prewarmAllowed");
        for (int index = 0; index < WORKER_COUNT; index++) {
            Thread worker = new Thread(this::run,
                    "accelerated-navigation-topology-" + (index + 1));
            worker.setDaemon(true);
            worker.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            workers[index] = worker;
            worker.start();
        }
    }

    TaskHandle submit(NavigationScheduler.Priority priority,
                      Runnable command) {
        return submit(priority, command, WorkKind.BUILD);
    }

    TaskHandle submitSearch(NavigationScheduler.Priority priority,
                            WorkKind kind,
                            Runnable command) {
        if (kind != WorkKind.QUICK_SEARCH && kind != WorkKind.LONG_SEARCH) {
            throw new IllegalArgumentException("search task requires a search work kind");
        }
        return submit(priority, command, kind);
    }

    TaskHandle submitPrewarm(Runnable command) {
        return submit(NavigationScheduler.Priority.BACKGROUND, command, WorkKind.PREWARM);
    }

    /**
     * Ensures that at most one cross-thread state-consumption task is queued or running.
     * The runtime owns the coalesced state; this handle is only its execution notice.
     */
    TaskHandle submitControl(Runnable command) {
        Objects.requireNonNull(command, "command");
        synchronized (monitor) {
            ensureAccepting();
            return queueControlLocked(command);
        }
    }

    private TaskHandle queueControlLocked(Runnable command) {
        if (controlTask != null && controlTask.state != TaskState.DONE) {
            if (controlTask.state == TaskState.RUNNING && controlTask.nextCommand == null) {
                controlTask.nextPriority = NavigationScheduler.Priority.PLAYER_PURSUIT;
                controlTask.nextKind = WorkKind.CONTROL;
                controlTask.nextCommand = command;
            }
            return controlTask;
        }
        Task task = new Task(++sequence, NavigationScheduler.Priority.PLAYER_PURSUIT,
                WorkKind.CONTROL, command, System.nanoTime());
        controlTask = task;
        queue(task);
        monitor.notifyAll();
        return task;
    }

    private TaskHandle submit(NavigationScheduler.Priority priority,
                              Runnable command,
                              WorkKind kind) {
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(command, "command");
        synchronized (monitor) {
            ensureAccepting();
            Task task = new Task(++sequence, priority, kind, command,
                    System.nanoTime());
            queue(task);
            monitor.notifyAll();
            return task;
        }
    }

    private void ensureAccepting() {
        if (!accepting) {
            throw new RejectedExecutionException("topology workers are stopped");
        }
    }

    void shutdown() {
        synchronized (monitor) {
            if (!accepting) {
                return;
            }
            accepting = false;
            cancelQueuedTasks();
            monitor.notifyAll();
        }
    }

    /** Preserve the final state-consumption notice when ordinary submission was rejected. */
    void shutdown(Runnable finalControl) {
        Objects.requireNonNull(finalControl, "finalControl");
        synchronized (monitor) {
            queueControlLocked(finalControl);
            shutdown();
        }
    }

    boolean awaitTermination(long timeout, TimeUnit unit) {
        Objects.requireNonNull(unit, "unit");
        if (timeout < 0L) {
            throw new IllegalArgumentException("termination timeout cannot be negative");
        }
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        for (Thread worker : workers) {
            while (worker.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return false;
                }
                long millis = TimeUnit.NANOSECONDS.toMillis(remaining);
                int nanos = (int) (remaining - TimeUnit.MILLISECONDS.toNanos(millis));
                try {
                    worker.join(millis, nanos);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    void signalWork() {
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    TopologyService.TaskMetrics metrics() {
        synchronized (monitor) {
            return new TopologyService.TaskMetrics(
                    counts(queuedByKind),
                    counts(runningByKind),
                    counts(highestQueuedByKind),
                    counts(highestRunningByKind),
                    counts(completedByKind),
                    counts(failedByKind)
            );
        }
    }

    private static TopologyService.TaskCounts counts(long[] source) {
        return new TopologyService.TaskCounts(
                source[WorkKind.CONTROL.ordinal()],
                source[WorkKind.BUILD.ordinal()],
                source[WorkKind.QUICK_SEARCH.ordinal()],
                source[WorkKind.LONG_SEARCH.ordinal()],
                source[WorkKind.PREWARM.ordinal()]
        );
    }

    private void run() {
        while (true) {
            Task task;
            synchronized (monitor) {
                while (true) {
                    if (!accepting && foreground.isEmpty() && prewarm.isEmpty()
                            && queuedControl() == null) {
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
                            cancelQueuedTasks();
                        }
                    }
                }
            }
            boolean failed = false;
            try {
                currentTask.set(task);
                task.command.run();
            } catch (VirtualMachineError | ThreadDeath fatal) {
                failed = true;
                throw fatal;
            } catch (Throwable failure) {
                failed = true;
                AcceleratedNavigation.LOGGER.error(
                        "Unhandled topology task failure on {}",
                        Thread.currentThread().getName(), failure);
            } finally {
                currentTask.remove();
                synchronized (monitor) {
                    finishTask(task, failed);
                    monitor.notifyAll();
                }
            }
        }
    }

    private void finishTask(Task task, boolean failed) {
        leaveRunning(task);
        completedByKind[task.kind.ordinal()]++;
        if (failed) failedByKind[task.kind.ordinal()]++;
        if (task.nextCommand != null && (accepting || task.nextKind == WorkKind.CONTROL)) {
            task.command = task.nextCommand;
            task.priority = task.nextPriority;
            task.kind = task.nextKind;
            task.nextCommand = null;
            task.nextPriority = null;
            task.nextKind = null;
            task.sequence = ++sequence;
            task.enqueuedNanos = System.nanoTime();
            task.state = TaskState.QUEUED;
            queue(task);
            return;
        }
        task.nextCommand = null;
        task.nextPriority = null;
        task.nextKind = null;
        task.state = TaskState.DONE;
        if (controlTask == task) {
            controlTask = null;
        }
    }

    /** Requeues the current search state without creating a second task handle. */
    boolean requeueCurrent(NavigationScheduler.Priority continuationPriority,
                           WorkKind continuationKind,
                           Runnable continuation) {
        Objects.requireNonNull(continuationPriority, "continuationPriority");
        Objects.requireNonNull(continuationKind, "continuationKind");
        Objects.requireNonNull(continuation, "continuation");
        if (continuationKind != WorkKind.QUICK_SEARCH
                && continuationKind != WorkKind.LONG_SEARCH) {
            throw new IllegalArgumentException("continuation requires a search work kind");
        }
        synchronized (monitor) {
            Task task = currentTask.get();
            if (task == null || !task.kind.isSearch() || !canContinue(task)) {
                return false;
            }
            task.nextPriority = continuationPriority;
            task.nextKind = continuationKind;
            task.nextCommand = continuation;
            return true;
        }
    }

    /** Requeues the one coalesced control notice when state arrived during its current batch. */
    boolean requeueControlCurrent(Runnable continuation) {
        Objects.requireNonNull(continuation, "continuation");
        synchronized (monitor) {
            Task task = currentTask.get();
            if (task == null || task.kind != WorkKind.CONTROL || !canContinue(task)) {
                return false;
            }
            task.nextPriority = NavigationScheduler.Priority.PLAYER_PURSUIT;
            task.nextKind = WorkKind.CONTROL;
            task.nextCommand = continuation;
            return true;
        }
    }

    private boolean canContinue(Task task) {
        return task.state == TaskState.RUNNING && accepting && task.nextCommand == null;
    }

    private Task takeNext(long now) {
        Task selected = queuedControl();
        if (selected == null) {
            selected = bestForeground(now);
        }
        if (selected == null && foregroundIdle()) {
            Map.Entry<Long, Task> first = prewarm.firstEntry();
            selected = first == null ? null : first.getValue();
        }
        if (selected == null) {
            return null;
        }
        if (!removeQueued(selected)) {
            throw new IllegalStateException("selected topology task is not queued");
        }
        int kind = selected.kind.ordinal();
        runningByKind[kind]++;
        highestRunningByKind[kind] = Math.max(
                highestRunningByKind[kind], runningByKind[kind]);
        selected.state = TaskState.RUNNING;
        if (selected.kind.requiresBuildPermit()) {
            if (buildRunning) {
                throw new IllegalStateException("two topology builds selected concurrently");
            }
            buildRunning = true;
        }
        return selected;
    }

    private Task queuedControl() {
        return controlTask != null && controlTask.state == TaskState.QUEUED
                ? controlTask : null;
    }

    /** Fixed four priorities by three foreground kinds: no task-table scan. */
    private Task bestForeground(long now) {
        Task selected = null;
        int selectedRank = Integer.MAX_VALUE;
        for (NavigationScheduler.Priority priority : NavigationScheduler.Priority.values()) {
            for (WorkKind kind : WorkKind.FOREGROUND_ORDER) {
                if (kind.isForegroundBuild() && buildRunning) {
                    continue;
                }
                Task candidate = firstInGroup(priority, kind);
                if (candidate == null) {
                    continue;
                }
                int rank = effectiveRank(candidate, now);
                if (selected == null || rank < selectedRank
                        || rank == selectedRank && (kind.order < selected.kind.order
                        || kind.order == selected.kind.order
                        && candidate.sequence < selected.sequence)) {
                    selected = candidate;
                    selectedRank = rank;
                }
            }
        }
        return selected;
    }

    private Task firstInGroup(NavigationScheduler.Priority priority, WorkKind kind) {
        Map.Entry<TaskKey, Task> entry = foreground.ceilingEntry(
                new TaskKey(priority.ordinal(), kind.order, Long.MIN_VALUE));
        if (entry == null || entry.getKey().priorityRank != priority.ordinal()
                || entry.getKey().kindOrder != kind.order) {
            return null;
        }
        return entry.getValue();
    }

    private boolean foregroundIdle() {
        return foreground.isEmpty() && !buildRunning
                && runningByKind[WorkKind.CONTROL.ordinal()] == 0L
                && runningByKind[WorkKind.QUICK_SEARCH.ordinal()] == 0L
                && runningByKind[WorkKind.LONG_SEARCH.ordinal()] == 0L
                && prewarmAllowed.getAsBoolean();
    }

    private static int effectiveRank(Task task, long now) {
        int baseRank = task.priority.ordinal();
        long waited = Math.max(0L, now - task.enqueuedNanos);
        int promotions = (int) Math.min(baseRank, waited / AGING_INTERVAL_NANOS);
        return baseRank - promotions;
    }

    private void insertQueued(Task task) {
        if (task.kind == WorkKind.PREWARM) prewarm.put(task.sequence, task);
        else if (task.kind != WorkKind.CONTROL) foreground.put(task.key(), task);
        int kind = task.kind.ordinal();
        queuedByKind[kind]++;
        highestQueuedByKind[kind] = Math.max(highestQueuedByKind[kind], queuedByKind[kind]);
    }

    private void queue(Task task) {
        insertQueued(task);
    }

    private boolean removeQueued(Task task) {
        boolean removed = switch (task.kind) {
            case BUILD, QUICK_SEARCH, LONG_SEARCH -> foreground.remove(task.key(), task);
            case PREWARM -> prewarm.remove(task.sequence, task);
            case CONTROL -> controlTask == task && task.state == TaskState.QUEUED;
        };
        if (removed && --queuedByKind[task.kind.ordinal()] < 0) {
            throw new IllegalStateException("negative queued topology task count");
        }
        return removed;
    }

    private void leaveRunning(Task task) {
        if (--runningByKind[task.kind.ordinal()] < 0) {
            throw new IllegalStateException("negative running topology task count");
        }
        if (task.kind.requiresBuildPermit()) {
            buildRunning = false;
        }
    }

    private void cancelQueuedTasks() {
        foreground.values().forEach(this::cancelQueued);
        foreground.clear();
        for (Task task : prewarm.values()) {
            cancelQueued(task);
        }
        prewarm.clear();
        queuedByKind[WorkKind.BUILD.ordinal()] = 0;
        queuedByKind[WorkKind.QUICK_SEARCH.ordinal()] = 0;
        queuedByKind[WorkKind.LONG_SEARCH.ordinal()] = 0;
        queuedByKind[WorkKind.PREWARM.ordinal()] = 0;
    }

    private void cancelQueued(Task task) {
        task.state = TaskState.DONE;
    }

    interface TaskHandle {
        TaskHandle NONE = completion -> completion.accept(false);

        default void reprioritize(NavigationScheduler.Priority priority) {}

        default void promoteBuild() {}

        void cancelWhenQueued(Consumer<Boolean> completion);
    }

    enum WorkKind {
        CONTROL(-1),
        BUILD(0),
        QUICK_SEARCH(1),
        LONG_SEARCH(2),
        PREWARM(3);

        private static final WorkKind[] FOREGROUND_ORDER = {
                BUILD, QUICK_SEARCH, LONG_SEARCH
        };
        private final int order;

        WorkKind(int order) {
            this.order = order;
        }

        private boolean requiresBuildPermit() {
            return this == BUILD || this == PREWARM;
        }

        private boolean isForegroundBuild() {
            return this == BUILD;
        }

        private boolean isSearch() {
            return this == QUICK_SEARCH || this == LONG_SEARCH;
        }

    }

    private enum TaskState {
        QUEUED,
        RUNNING,
        DONE
    }

    private record TaskKey(int priorityRank, int kindOrder, long sequence)
            implements Comparable<TaskKey> {
        @Override
        public int compareTo(TaskKey other) {
            int comparison = Integer.compare(priorityRank, other.priorityRank);
            if (comparison == 0) {
                comparison = Integer.compare(kindOrder, other.kindOrder);
            }
            return comparison != 0 ? comparison : Long.compare(sequence, other.sequence);
        }
    }

    private final class Task implements TaskHandle {
        private long sequence;
        private Runnable command;
        private Runnable nextCommand;
        private NavigationScheduler.Priority nextPriority;
        private WorkKind nextKind;
        private long enqueuedNanos;
        private NavigationScheduler.Priority priority;
        private WorkKind kind;
        private TaskState state = TaskState.QUEUED;

        private Task(long sequence,
                     NavigationScheduler.Priority priority,
                     WorkKind kind,
                     Runnable command,
                     long enqueuedNanos) {
            this.sequence = sequence;
            this.priority = priority;
            this.kind = kind;
            this.command = command;
            this.enqueuedNanos = enqueuedNanos;
        }

        private TaskKey key() {
            return new TaskKey(priority.ordinal(), kind.order, sequence);
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

        private void moveTo(NavigationScheduler.Priority requested) {
            if (kind == WorkKind.CONTROL) {
                return;
            }
            if (kind == WorkKind.PREWARM) {
                priority = requested;
            } else {
                if (!foreground.remove(key(), this)) {
                    throw new IllegalStateException("queued topology task is missing");
                }
                priority = requested;
                foreground.put(key(), this);
            }
            monitor.notifyAll();
        }

        @Override
        public void promoteBuild() {
            synchronized (monitor) {
                if (state != TaskState.QUEUED || kind != WorkKind.PREWARM) {
                    return;
                }
                if (!prewarm.remove(sequence, this)) {
                    throw new IllegalStateException("queued prewarm task is missing");
                }
                if (--queuedByKind[WorkKind.PREWARM.ordinal()] < 0) {
                    throw new IllegalStateException("negative queued prewarm count");
                }
                kind = WorkKind.BUILD;
                insertQueued(this);
                monitor.notifyAll();
            }
        }

        @Override
        public void cancelWhenQueued(Consumer<Boolean> completion) {
            Objects.requireNonNull(completion, "completion");
            boolean removed;
            synchronized (monitor) {
                if (state != TaskState.QUEUED || !removeQueued(this)) {
                    removed = false;
                } else {
                    if (controlTask == this) controlTask = null;
                    cancelQueued(this);
                    monitor.notifyAll();
                    removed = true;
                }
            }
            completion.accept(removed);
        }
    }

}
