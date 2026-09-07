package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopologyTaskExecutorTest {

    @Test
    void finalControlRemainsOnOriginalWorkerAfterShutdownDuringRunningControl() throws Exception {
        TopologyTaskExecutor executor = new TopologyTaskExecutor(() -> false);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), finalControl = new CountDownLatch(1);
        Thread caller = Thread.currentThread();
        var completedOn = new java.util.concurrent.atomic.AtomicReference<Thread>();
        try {
            executor.submitControl(() -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            executor.shutdown(() -> { completedOn.set(Thread.currentThread()); finalControl.countDown(); });
            assertFalse(executor.awaitTermination(20, TimeUnit.MILLISECONDS));
            release.countDown();
            assertTrue(finalControl.await(5, TimeUnit.SECONDS));
            assertTrue(completedOn.get() != caller && completedOn.get().getName().startsWith("accelerated-navigation-topology-"));
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        } finally { release.countDown(); executor.shutdown(); executor.awaitTermination(5, TimeUnit.SECONDS); }
    }

    @Test
    void shutdownWaitsForRunningAtomicTask() throws Exception {
        TopologyTaskExecutor executor = new TopologyTaskExecutor(() -> false);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.submit(NavigationScheduler.Priority.PLAYER_PURSUIT, () -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(5L, TimeUnit.SECONDS));

            executor.shutdown();
            assertFalse(executor.awaitTermination(20L, TimeUnit.MILLISECONDS));

            release.countDown();
            assertTrue(executor.awaitTermination(5L, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdown();
            executor.awaitTermination(5L, TimeUnit.SECONDS);
        }
    }
}
