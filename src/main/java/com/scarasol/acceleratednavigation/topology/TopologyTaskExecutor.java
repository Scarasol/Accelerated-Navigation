package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.AcceleratedNavigation;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** Single-worker priority queue with bounded starvation through task aging. */
final class TopologyTaskExecutor {

    private static final long AGING_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(250L);

    private final Object monitor = new Object();
    private final EnumMap<NavigationScheduler.Priority, ArrayDeque<Task>> queues =
            new EnumMap<>(NavigationScheduler.Priority.class);
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
            queues.put(priority, new ArrayDeque<>());
        }
        thread = new Thread(this::run, Objects.requireNonNull(threadName, "threadName"));
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Math.min(Thread.MAX_PRIORITY, threadPriority)));
        thread.start();
    }

    TaskHandle submit(NavigationScheduler.Priority priority, Runnable command) {
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(command, "command");
        synchronized (monitor) {
            if (!accepting) {
                throw new RejectedExecutionException(thread.getName() + " is stopped");
            }
            Task task = new Task(++sequence, priority, command, System.nanoTime());
            queues.get(priority).addLast(task);
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
        Task selected = null;
        NavigationScheduler.Priority selectedQueue = null;
        int selectedRank = Integer.MAX_VALUE;
        for (NavigationScheduler.Priority priority : NavigationScheduler.Priority.values()) {
            Task candidate = queues.get(priority).peekFirst();
            if (candidate == null) {
                continue;
            }
            int rank = effectiveRank(candidate, now);
            if (selected == null || rank < selectedRank
                    || (rank == selectedRank && candidate.sequence < selected.sequence)) {
                selected = candidate;
                selectedQueue = priority;
                selectedRank = rank;
            }
        }
        if (selected == null || selectedQueue == null) {
            throw new IllegalStateException("topology worker queue size is inconsistent");
        }
        queues.get(selectedQueue).removeFirst();
        queuedTasks--;
        selected.state = TaskState.RUNNING;
        long waited = Math.max(0L, now - selected.enqueuedNanos);
        totalQueueWaitNanos += waited;
        maximumQueueWaitNanos = Math.max(maximumQueueWaitNanos, waited);
        return selected;
    }

    private static int effectiveRank(Task task, long now) {
        int baseRank = task.priority.ordinal();
        long waited = Math.max(0L, now - task.enqueuedNanos);
        int promotions = (int) Math.min(baseRank, waited / AGING_INTERVAL_NANOS);
        return baseRank - promotions;
    }

    interface TaskHandle {
        void promote(NavigationScheduler.Priority priority);

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
        private final Runnable command;
        private final long enqueuedNanos;
        private NavigationScheduler.Priority priority;
        private TaskState state = TaskState.QUEUED;

        private Task(long sequence,
                     NavigationScheduler.Priority priority,
                     Runnable command,
                     long enqueuedNanos) {
            this.sequence = sequence;
            this.priority = priority;
            this.command = command;
            this.enqueuedNanos = enqueuedNanos;
        }

        @Override
        public void promote(NavigationScheduler.Priority requested) {
            Objects.requireNonNull(requested, "requested");
            synchronized (monitor) {
                if (state != TaskState.QUEUED || !requested.higherThan(priority)) {
                    return;
                }
                if (!queues.get(priority).remove(this)) {
                    throw new IllegalStateException("queued topology task is missing from its band");
                }
                priority = requested;
                queues.get(priority).addLast(this);
                promotedTasks++;
                monitor.notifyAll();
            }
        }

        @Override
        public boolean cancel() {
            synchronized (monitor) {
                if (state != TaskState.QUEUED || !queues.get(priority).remove(this)) {
                    return false;
                }
                state = TaskState.CANCELLED;
                queuedTasks--;
                cancelledTasks++;
                return true;
            }
        }
    }
}
