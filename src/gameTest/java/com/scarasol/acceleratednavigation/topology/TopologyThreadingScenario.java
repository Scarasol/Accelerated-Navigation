package com.scarasol.acceleratednavigation.topology;

import com.scarasol.acceleratednavigation.gametest.ProductionRemediationPlan;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;

/** D06 exercises real foreign-thread entry points once per disposable server. */
public final class TopologyThreadingScenario implements AutoCloseable {
    private static final BlockPos START = new BlockPos(1540, 289, 1540);
    private final TopologyService service;
    private final ServerLevel level;
    private final String entry;
    private final boolean mutated;
    private final CompletableFuture<Throwable> invocation = new CompletableFuture<>();
    private LevelChunk chunk;
    private TopologyService.MacroRequest pending;
    private Thread caller;

    public TopologyThreadingScenario(TopologyService service, ServerLevel level, ProductionRemediationPlan.Target target, String control) {
        this.service = service; this.level = level; entry = target.parameters().get("entry"); mutated = "mutated".equals(control);
    }

    public Set<Long> chunkPositions() { return Set.of(new ChunkPos(START).toLong()); }

    public Map<String, Object> tick() {
        if (caller == null) {
            chunk = level.getChunk(START.getX() >> 4, START.getZ() >> 4);
            TopologyService.onChunkLoaded(level, chunk);
            for (int x = 0; x < 5; x++) {
                chunk.setBlockState(START.offset(x, -1, 0), Blocks.STONE.defaultBlockState(), false);
                chunk.setBlockState(START.offset(x, 0, 0), Blocks.AIR.defaultBlockState(), false);
                chunk.setBlockState(START.offset(x, 1, 0), Blocks.AIR.defaultBlockState(), false);
            }
            require(TopologyService.tracksFacts(chunk), "the real loaded chunk has its production consumer");
            if (!entry.equals("chunk-write-idle")) {
                pending = request();
                require(!pending.future().isDone(), "the main-thread request is pending before the foreign call");
            }
            caller = new Thread(() -> {
                try {
                    require(!level.getServer().isSameThread(), "the test caller is genuinely off the server thread");
                    if (entry.equals("api-pending")) request();
                    else chunk.setBlockState(START.above(), Blocks.STONE.defaultBlockState(), false);
                    invocation.complete(null);
                } catch (Throwable failure) { invocation.complete(failure); }
            }, "prmq-d06-caller");
            caller.start();
            return null;
        }
        if (!invocation.isDone() || caller.isAlive()) return null;
        Throwable callFailure = invocation.join();
        require(entry.equals("api-pending") ? callFailure instanceof UnsupportedOperationException : callFailure == null,
                "the real entry uses the approved degradation boundary");
        if (!entry.equals("api-pending")) require(chunk.getBlockState(START.above()).is(Blocks.STONE), "the foreign chunk write actually occurred");
        if (!Boolean.TRUE.equals(TopologyTestBridge.readField(service, "stopping"))) return null;
        if (pending != null && !pending.future().isDone()) return null;
        String pendingFailure = "NOT_APPLICABLE";
        if (pending != null) {
            try { pending.future().join(); throw new AssertionError("the pending request did not fail"); }
            catch (CompletionException failure) {
                require(failure.getCause() instanceof UnsupportedOperationException, "service unavailability is not an R/P/U route failure");
                pendingFailure = failure.getCause().getClass().getName();
            }
        }
        boolean metadata = ChunkSerializer.write(level, chunk).contains("accelerated_navigation_facts_versions");
        verifyDisabled(service.isAvailable(), TopologyService.canReuseFactVersions(level.getServer()), metadata);
        var carrier = (TopologyService.ChunkFactsCarrier) chunk;
        require(carrier.acceleratedNavigation$factsState().versions().isEmpty(), "disabled load versions are cleared");
        require(!TopologyService.tracksFacts(chunk), "the chunk no longer records facts");
        for (int i = 0; i < 2; i++) {
            try { request(); throw new AssertionError("a later request reactivated the service"); }
            catch (UnsupportedOperationException expected) { }
        }
        var cleanup = TopologyValidationAccess.chunkSettlement(service, level.dimension(), chunkPositions());
        if (!Boolean.TRUE.equals(cleanup.get("settled"))) return null;
        boolean detected = false;
        if (mutated) {
            try { verifyDisabled(true, false, false); } catch (AssertionError expected) { detected = true; }
            require(detected, "the validator rejects a falsely available disabled service");
        }
        return Map.of("outcome", "PASS", "entry", entry, "callerThread", caller.getName(),
                "pendingFailure", pendingFailure, "chunkWriteApplied", !entry.equals("api-pending") && chunk.getBlockState(START.above()).is(Blocks.STONE),
                "metadataPresent", metadata, "cleanup", cleanup, "mutationDetected", detected,
                "coverage", "real production entry with a foreign Java caller; no third-party mod compatibility claim");
    }

    private TopologyService.MacroRequest request() {
        return service.requestMacroQuery(level, UUID.randomUUID(), START, START.offset(4, 0, 0), BaseClusterTopology.Channel.GROUND,
                BaseClusterTopology.TraversalProfile.DEFAULT_GROUND, NavigationScheduler.Priority.ACTIVE);
    }
    private static void verifyDisabled(boolean available, boolean reusableVersions, boolean metadata) {
        require(!available && !reusableVersions && !metadata, "disablement persists through request and NBT consumers");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    @Override public void close() {
        if (caller != null && caller.isAlive()) {
            caller.interrupt();
            try { caller.join(1000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            if (caller.isAlive()) throw new IllegalStateException("D06 foreign caller did not stop");
        }
        if (pending != null) { pending.cancel(); TopologyValidationAccess.forgetRequest(pending); pending = null; }
        if (chunk != null) { TopologyService.onChunkUnloaded(level, chunk); chunk = null; }
    }
}
