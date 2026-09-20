package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.topology.TopologySegmentTrace;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import com.scarasol.acceleratednavigation.topology.TopologyValidationAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only owner anchors. Completion is observed exclusively by CompletionMixin. */
public final class TopologyRequestSegmentMixin {
    private TopologyRequestSegmentMixin() { }

    @Mixin(value = TopologyService.MacroRequest.class, remap = false)
    public abstract static class ServiceRequest implements TopologySegmentTrace.RequestAccess {
        @Unique private final TopologySegmentTrace.Request acceleratedNavigation$segments = TopologySegmentTrace.newRequest();
        @Override public TopologySegmentTrace.Request segmentRequest() { return acceleratedNavigation$segments; }
        @Inject(method = "submit", at = @At("HEAD"))
        private void submitEntry(CallbackInfo ci) { if (TopologySegmentTrace.ENABLED) acceleratedNavigation$segments.submit(); }
        // javap confirms $2/$4 enqueue; $1/$3 execute completeWorker.
        @Inject(method = {"lambda$submit$2", "lambda$completeWorker$4"},
                at = @At(value = "INVOKE", target = "Lcom/scarasol/acceleratednavigation/topology/TopologyService;queueServer(Ljava/lang/Runnable;)V"))
        private void queued(CallbackInfo ci) { if (TopologySegmentTrace.ENABLED) acceleratedNavigation$segments.queued(); }
        @Inject(method = "completeWorker", at = @At("HEAD"))
        private void serverStart(CallbackInfo ci) { if (TopologySegmentTrace.ENABLED) acceleratedNavigation$segments.serverStart(); }
    }

    @Mixin(targets = "com.scarasol.acceleratednavigation.topology.TopologyWorkerRuntime$MacroRequest", remap = false)
    public abstract static class WorkerRequest implements TopologySegmentTrace.RequestAccess {
        @Unique private final TopologySegmentTrace.Request acceleratedNavigation$segments = TopologySegmentTrace.newRequest();
        @Override public TopologySegmentTrace.Request segmentRequest() { return acceleratedNavigation$segments; }
        @Inject(method = {"finish", "finishExceptionally"}, at = @At(value = "INVOKE",
                target = "Lcom/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime;finishFuture(Ljava/util/concurrent/CompletableFuture;Ljava/lang/Object;Ljava/lang/Throwable;)V"))
        private void resultReady(CallbackInfo ci) { if (TopologySegmentTrace.ENABLED) acceleratedNavigation$segments.workerReady(); }
    }

    @Mixin(targets = "com.scarasol.acceleratednavigation.topology.TopologyWorkerRuntime$MacroFlight", remap = false)
    public abstract static class WorkerFlight implements TopologySegmentTrace.FlightAccess {
        // add(first) runs before injected field initialization, so allocate lazily.
        @Unique private TopologySegmentTrace.Flight acceleratedNavigation$segments;
        @Override public TopologySegmentTrace.Flight segmentFlight() {
            if (acceleratedNavigation$segments == null) acceleratedNavigation$segments = new TopologySegmentTrace.Flight();
            return acceleratedNavigation$segments;
        }
        @Inject(method = "add", at = @At("RETURN"))
        private void bind(@Coerce Object waiter, CallbackInfo ci) {
            if (!TopologySegmentTrace.ENABLED) return;
            ((TopologySegmentTrace.RequestAccess) waiter).segmentRequest().bind(TopologyValidationAccess.token(this), segmentFlight());
        }
        @Inject(method = "complete", at = @At("HEAD"))
        private void completeEntry(CallbackInfo ci) { if (TopologySegmentTrace.ENABLED) segmentFlight().complete = System.nanoTime(); }
        @Inject(method = "runWorkerTask", at = @At("HEAD"))
        private void taskStart(CallbackInfo ci) { if (TopologySegmentTrace.ENABLED) segmentFlight().taskStart(); }
        @Inject(method = "runWorkerTask", at = @At("RETURN"))
        private void taskEnd(CallbackInfo ci) { if (TopologySegmentTrace.ENABLED) segmentFlight().taskEnd(); }
    }
}
