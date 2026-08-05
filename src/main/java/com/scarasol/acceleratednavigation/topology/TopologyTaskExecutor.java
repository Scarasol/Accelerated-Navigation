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

/** Single-worker priority queue with bounded starvation through task aging. */
final class TopologyTaskExecutor {

    private static final long AGING_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(250L);

    private final Object monitor = new Object();
    private final EnumMap<NavigationScheduler.Priority,
            LinkedHashMap<ResourceKey<Level>, ArrayDeque<Task>>> queues =
            new EnumMap<>(NavigationScheduler.Priority.class);
    private final List<ResourceKey<Level>> dimensionOrder = new ArrayList<>();
    private final int[] dimensionCursors = new int[NavigationScheduler.Priority.values().length];
    private final Thread thread;

    private boolean accepting = true;
    private int queuedTasks;
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
        for (NavigationScheduler.Priority priority : NavigationScheduler.Priority.values()) {
            queues.put(priority, new LinkedHashMap<>());
        }
        thread = new Thread(this::run, Objects.requireNonNull(threadName, "threadName"));
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Math.min(Thread.MAX_PRIORITY, threadPriority)));
        thread.start();
    }

    TaskHandle submit(ResourceKey<Level> dimension,
                      NavigationScheduler.Priority priority,
                      Runnable command) {
        return submit(dimension, priority, command, true);
    }

    TaskHandle submit(ResourceKey<Level> dimension,
                      NavigationScheduler.Priority priority,
                      Runnable command,
                      boolean allowAging) {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(command, "command");
        synchronized (monitor) {
            if (!accepting) {
                throw new RejectedExecutionException(thread.getName() + " is stopped");
            }
            Task task = new Task(++sequence, dimension, priority, command,
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
                    while (queuedTasks == 0 && accepting) {
                        try {
                            monitor.wait();
                        } catch (InterruptedException ignored) {
                            if (!accepting) {
                                return;
                            }
                        }
                    }
                    if (queuedTasks == 0) {
                        break;
                    }
                    task = takeNext(System.nanoTime());
                }
                try {
                    task.command.run();
                } catch (VirtualMachineError | ThreadDeath fatal) {
                    throw fatal;
                } catch (Throwable failure) {
                    AcceleratedNavigation.LOGGER.error(
                            "Unhandled task failure on {}",
                            thread.getName(),
                            failure
                    );
                } finally {
                    synchronized (monitor) {
                        task.state = TaskState.COMPLETED;
                        completedTasks++;
                    }
                }
            }
        } finally {
            try {
                afterDrain.run();
            } catch (Throwable failure) {
                AcceleratedNavigation.LOGGER.error(
                        "Could not finish shutdown for {}",
                        thread.getName(),
                        failure
                );
            }
        }
    }

    private Task takeNext(long now) {
        int selectedRank = Integer.MAX_VALUE;
        for (NavigationScheduler.Priority priority : NavigationScheduler.Priority.values()) {
            for (ArrayDeque<Task> dimension : queues.get(priority).values()) {
                Task candidate = dimension.peekFirst();
                if (candidate != null) {
                    selectedRank = Math.min(selectedRank, effectiveRank(candidate, now));
                }
            }
        }
        if (selectedRank == Integer.MAX_VALUE) {
            throw new IllegalStateException("topology worker queue size is inconsistent");
        }
        Task selected = null;
        int cursor = dimensionCursors[selectedRank];
        for (int offset = 0; offset < dimensionOrder.size(); offset++) {
            int index = (cursor + offset) % dimensionOrder.size();
            ResourceKey<Level> dimension = dimensionOrder.get(index);
            for (NavigationScheduler.Priority priority : NavigationScheduler.Priority.values()) {
                ArrayDeque<Task> queue = queues.get(priority).get(dimension);
                Task candidate = queue == null ? null : queue.peekFirst();
                if (candidate != null && effectiveRank(candidate, now) == selectedRank
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
            throw new IllegalStateException("topology worker queue size is inconsistent");
        }
        LinkedHashMap<ResourceKey<Level>, ArrayDeque<Task>> band = queues.get(selected.priority);
        ArrayDeque<Task> dimensionQueue = band.get(selected.dimension);
        if (dimensionQueue == null || dimensionQueue.removeFirst() != selected) {
            throw new IllegalStateException("topology worker dimension queue is inconsistent");
        }
        if (dimensionQueue.isEmpty()) {
            band.remove(selected.dimension);
        }
        queuedTasks--;
        selected.state = TaskState.RUNNING;
        long waited = Math.max(0L, now - selected.enqueuedNanos);
        totalQueueWaitNanos += waited;
        maximumQueueWaitNanos = Math.max(maximumQueueWaitNanos, waited);
        return selected;
    }

    private static int effectiveRank(Task task, long now) {
        int baseRank = task.priority.ordinal();
        if (!task.allowAging) {
            return baseRank;
        }
        long waited = Math.max(0L, now - task.enqueuedNanos);
        int promotions = (int) Math.min(baseRank, waited / AGING_INTERVAL_NANOS);
        return baseRank - promotions;
    }

    interface TaskHandle {
        void promote(NavigationScheduler.Priority priority);

        void reprioritize(NavigationScheduler.Priority priority);

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

    private enum TaskState {
        QUEUED,
        RUNNING,
        COMPLETED,
        CANCELLED
    }

    private final class Task implements TaskHandle {
        private final long sequence;
        private final ResourceKey<Level> dimension;
        private final Runnable command;
        private final long enqueuedNanos;
        private boolean allowAging;
        private NavigationScheduler.Priority priority;
        private TaskState state = TaskState.QUEUED;

        private Task(long sequence,
                     ResourceKey<Level> dimension,
                     NavigationScheduler.Priority priority,
                     Runnable command,
                     long enqueuedNanos,
                     boolean allowAging) {
            this.sequence = sequence;
            this.dimension = dimension;
            this.priority = priority;
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
        public void enableAging() {
            synchronized (monitor) {
                allowAging = true;
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
                insertStable(
                        queues.get(priority).computeIfAbsent(dimension, ignored -> new ArrayDeque<>()),
                        this
                );
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
                return true;
            }
        }
    }

    private static void insertStable(ArrayDeque<Task> queue, Task inserted) {
        if (queue.isEmpty() || queue.peekLast().sequence < inserted.sequence) {
            queue.addLast(inserted);
            return;
        }
        ArrayDeque<Task> reordered = new ArrayDeque<>(queue.size() + 1);
        boolean added = false;
        for (Task task : queue) {
            if (!added && inserted.sequence < task.sequence) {
                reordered.addLast(inserted);
                added = true;
            }
            reordered.addLast(task);
        }
        if (!added) {
            reordered.addLast(inserted);
        }
        queue.clear();
        queue.addAll(reordered);
    }
}


