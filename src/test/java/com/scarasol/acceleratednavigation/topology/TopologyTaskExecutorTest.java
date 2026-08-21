package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopologyTaskExecutorTest {

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
