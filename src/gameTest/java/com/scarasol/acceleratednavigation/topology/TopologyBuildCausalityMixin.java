package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.topology.BaseClusterTopology;
import com.scarasol.acceleratednavigation.topology.TopologyCausalityObservation;
import com.scarasol.acceleratednavigation.topology.TopologyTestBridge;
import com.scarasol.acceleratednavigation.topology.TopologyValidationAccess;

import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

@Mixin(targets = "com.scarasol.acceleratednavigation.topology.TopologyWorkerRuntime", remap = false)
abstract class TopologyBuildCausalityMixin {
    @org.spongepowered.asm.mixin.injection.ModifyVariable(method = "submitOutsideRuntimeLock", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private java.util.function.Supplier<?> submission(java.util.function.Supplier<?> original) {
        if (!TopologyCausalityObservation.enabled("build")) return original;
        return () -> { Object task = original.get(); TopologyCausalityObservation.buildSubmission(this, task); return task; };
    }
    @org.spongepowered.asm.mixin.injection.Inject(method = {"build", "buildSuperCluster", "buildBaseBoundaryLinks", "buildSuperBoundaryLinks"}, at = @At("HEAD"))
    private void attempted(org.spongepowered.asm.mixin.injection.callback.CallbackInfo callback) { TopologyCausalityObservation.buildAttempt(this, "start"); }
    @org.spongepowered.asm.mixin.injection.Inject(method = {"build", "buildSuperCluster", "buildBaseBoundaryLinks", "buildSuperBoundaryLinks"}, at = @At("RETURN"))
    private void settled(org.spongepowered.asm.mixin.injection.callback.CallbackInfo callback) { TopologyCausalityObservation.buildAttempt(this, "end"); }
    @org.spongepowered.asm.mixin.injection.Inject(method = "markLatestDerivedStale", at = @At("HEAD"))
    private void invalidated(@Coerce Object source, @Coerce Object state, org.spongepowered.asm.mixin.injection.callback.CallbackInfo callback) {
        TopologyCausalityObservation.invalidated(source, state);
    }
    @WrapOperation(method = "build", at = @At(value = "INVOKE", target = "Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology;build(Lnet/minecraft/core/SectionPos;JLcom/scarasol/acceleratednavigation/topology/BaseClusterTopology$BuildInput;Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology$GeometryKey;Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology$BuildScratch;)Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology;"))
    private BaseClusterTopology base(SectionPos section, long revision, @Coerce Object input,
                                     BaseClusterTopology.GeometryKey geometry, @Coerce Object scratch, Operation<BaseClusterTopology> original) {
        var span = TopologyCausalityObservation.begin("build", "BaseClusterTopology.build", this);
        BaseClusterTopology result = null;
        try { return result = original.call(section, revision, input, geometry, scratch); }
        finally { if (span != null) TopologyCausalityObservation.end(span, Map.of("taskToken", com.scarasol.acceleratednavigation.topology.TopologyBuildTransitionProbe.currentTask(this), "section", section, "version", revision,
                "fingerprint", TopologyTestBridge.invoke(TopologyTestBridge.invoke(input, "center"), "fingerprint"),
                "geometry", geometry, "resultToken", TopologyValidationAccess.token(result), "completed", result != null)); }
    }
@WrapOperation(method = {"buildSuperCluster", "buildBaseBoundaryLinks", "buildSuperBoundaryLinks"},
            at = @At(value = "INVOKE", target = "Lcom/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime;runtimeTransition(Ljava/util/function/Supplier;)Ljava/lang/Object;"))
    private Object captured(@Coerce Object runtime, java.util.function.Supplier<?> action, Operation<Object> original,
                            @com.llamalad7.mixinextras.sugar.Share("causalInput") com.llamalad7.mixinextras.sugar.ref.LocalRef<com.scarasol.acceleratednavigation.topology.TopologyBuildTransitionProbe.Input> shared,
                            @com.llamalad7.mixinextras.sugar.Share("causalSpan") com.llamalad7.mixinextras.sugar.ref.LocalRef<TopologyCausalityObservation.Span> span) {
        Object value = original.call(runtime, action);
        if (!TopologyCausalityObservation.enabled("build")) return value;
        var input = com.scarasol.acceleratednavigation.topology.TopologyBuildTransitionProbe.captured(action, value);
        shared.set(input);
        if (input != null) span.set(TopologyCausalityObservation.begin("build", input.kind(), this));
        return value;
    }

    @WrapOperation(method = {"buildSuperCluster", "buildBaseBoundaryLinks", "buildSuperBoundaryLinks"},
            at = @At(value = "INVOKE", target = "Lcom/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime;runRuntimeTransition(Ljava/lang/Runnable;)V"))
    private void publishing(@Coerce Object runtime, Runnable action, Operation<Void> original,
                            @com.llamalad7.mixinextras.sugar.Share("causalInput") com.llamalad7.mixinextras.sugar.ref.LocalRef<com.scarasol.acceleratednavigation.topology.TopologyBuildTransitionProbe.Input> shared,
                            @com.llamalad7.mixinextras.sugar.Share("causalSpan") com.llamalad7.mixinextras.sugar.ref.LocalRef<TopologyCausalityObservation.Span> span) {
        if (span.get() != null) {
            var current = span.get(); span.set(null);
            TopologyCausalityObservation.end(current, com.scarasol.acceleratednavigation.topology.TopologyBuildTransitionProbe.details(this, shared.get(), action,
                    com.scarasol.acceleratednavigation.topology.TopologyBuildTransitionProbe.result(action, shared.get())));
        }
        original.call(runtime, action);
    }
}
