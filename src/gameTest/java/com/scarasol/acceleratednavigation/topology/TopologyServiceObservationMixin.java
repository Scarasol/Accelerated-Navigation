package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.gametest.TopologyObservationAccess;
import com.scarasol.acceleratednavigation.gametest.TopologyStoreObservationAccess;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import com.scarasol.acceleratednavigation.topology.TopologyTestBridge;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import net.minecraft.server.TickTask;
import net.minecraft.server.MinecraftServer;
import java.util.concurrent.RejectedExecutionException;

/** Common read-only observation injected for the formal and refresh runs. */
@Mixin(value = TopologyService.class, remap = false)
abstract class TopologyServiceObservationMixin implements TopologyObservationAccess {

    @Shadow @Final private Map<?, ?> loadedChunks;
    @Shadow @Final private Map<?, ?> loadedSections;
    @Shadow @Final private Map<?, ?> macroRequests;
    @Shadow @Final private LinkedHashSet<?> foregroundRecovery;
    @Shadow @Final private LinkedHashSet<?> ordinaryRecovery;
    @Shadow private int highestMacroRequests;
    @Shadow private int highestQueuedRecoveries;
    @Shadow private long recoveredSections;
    @Shadow private long failedRecoveries;
    @Shadow private long degradedRecoveryCells;
    @Shadow private long successfulMacroQueries;
    @Shadow private long failedMacroQueries;
    @Shadow private long cancelledMacroQueries;
    @Shadow private long exceptionalMacroQueries;
    @Shadow private long finalValidationRejections;
    @Shadow private long successfulStaleRetries;

    @Unique
    private final AtomicInteger acceleratedNavigation$pendingPersistenceCompletions =
            new AtomicInteger();
    @Unique
    private final AtomicInteger acceleratedNavigation$pendingServerCallbacks =
            new AtomicInteger();

    @ModifyArg(
            method = {"readPersisted", "persistDecision"},
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/concurrent/CompletableFuture;"
                            + "whenComplete(Ljava/util/function/BiConsumer;)"
                            + "Ljava/util/concurrent/CompletableFuture;"),
            index = 0)
    private BiConsumer<Object, Object> acceleratedNavigation$trackPersistenceCompletion(
            BiConsumer<Object, Object> completion) {
        acceleratedNavigation$pendingPersistenceCompletions.incrementAndGet();
        return (result, failure) -> {
            try {
                completion.accept(result, failure);
            } finally {
                acceleratedNavigation$pendingPersistenceCompletions.decrementAndGet();
            }
        };
    }

    @ModifyArg(
            method = "queueServer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/TickTask;"
                            + "<init>(ILjava/lang/Runnable;)V",
                    remap = true),
            index = 1)
    private Runnable acceleratedNavigation$trackServerCallback(Runnable action) {
        acceleratedNavigation$pendingServerCallbacks.incrementAndGet();
        return () -> {
            try {
                action.run();
            } finally {
                acceleratedNavigation$pendingServerCallbacks.decrementAndGet();
            }
        };
    }

    @Redirect(
            method = "queueServer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;"
                            + "tell(Ljava/lang/Runnable;)V",
                    remap = true))
    private void acceleratedNavigation$submitTrackedServerCallback(
            MinecraftServer server,
            Runnable action) {
        try {
            server.tell((TickTask) action);
        } catch (RejectedExecutionException rejected) {
            acceleratedNavigation$pendingServerCallbacks.decrementAndGet();
            throw rejected;
        }
    }

    @Override
    public Map<String, Long> acceleratedNavigation$snapshotMetrics() {
        Map<String, Long> result = new LinkedHashMap<>();
        put(result, "service.loadedChunks", loadedChunks.size());
        put(result, "service.loadedSections", loadedSections.size());
        put(result, "service.activeMacroRequests", macroRequests.size());
        put(result, "service.highestMacroRequests", highestMacroRequests);
        put(result, "service.foregroundRecoveries", foregroundRecovery.size());
        put(result, "service.ordinaryRecoveries", ordinaryRecovery.size());
        put(result, "service.highestQueuedRecoveries", highestQueuedRecoveries);
        put(result, "service.recoveredSections", recoveredSections);
        put(result, "service.failedRecoveries", failedRecoveries);
        put(result, "service.degradedRecoveryCells", degradedRecoveryCells);
        put(result, "service.successfulMacroQueries", successfulMacroQueries);
        put(result, "service.failedMacroQueries", failedMacroQueries);
        put(result, "service.cancelledMacroQueries", cancelledMacroQueries);
        put(result, "service.exceptionalMacroQueries", exceptionalMacroQueries);
        put(result, "service.finalValidationRejections", finalValidationRejections);
        put(result, "service.successfulStaleRetries", successfulStaleRetries);
        put(result, "service.pendingPersistenceCompletions",
                acceleratedNavigation$pendingPersistenceCompletions.get());
        put(result, "service.pendingServerCallbacks",
                acceleratedNavigation$pendingServerCallbacks.get());
        result.putAll(TopologyTestBridge.workerMetrics(this));
        if (TopologyTestBridge.hasStore(this)) {
            result.putAll(TopologyTestBridge.persistenceMetrics(this));
            TopologyStoreObservationAccess.StoreObservation observation =
                    TopologyTestBridge.storeObservation(this);
            put(result, "persistence.requestedFlushes", observation.requestedFlushes());
            put(result, "persistence.queuedFlushes", observation.queuedFlushes());
            put(result, "persistence.activeFlushes", observation.activeFlushes());
        }
        return Map.copyOf(result);
    }

    private static void put(Map<String, Long> target, String key, long value) {
        target.put(key, value);
    }
}
