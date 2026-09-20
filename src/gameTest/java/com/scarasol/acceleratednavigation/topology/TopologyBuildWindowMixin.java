package com.scarasol.acceleratednavigation.gametest.mixin;

import com.scarasol.acceleratednavigation.topology.*;

import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.injection.Coerce;

@Mixin(targets = "com.scarasol.acceleratednavigation.topology.TopologyWorkerRuntime", remap = false)
abstract class TopologyBuildWindowMixin {
    @WrapOperation(method = "build", at = @At(value = "INVOKE", target = "Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology;build(Lnet/minecraft/core/SectionPos;JLcom/scarasol/acceleratednavigation/topology/BaseClusterTopology$BuildInput;Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology$GeometryKey;Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology$BuildScratch;)Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology;"))
    private BaseClusterTopology base(SectionPos section, long revision, @Coerce Object input,
                                     BaseClusterTopology.GeometryKey geometry, @Coerce Object scratch, Operation<BaseClusterTopology> original) {
        TopologyBuildWindowProbe.point(this, "base", "captured", section, input, null);
        BaseClusterTopology result = original.call(section, revision, input, geometry, scratch);
        TopologyBuildWindowProbe.point(this, "base", "publishing", section, input, result);
        return result;
    }
@WrapOperation(method = {"buildSuperCluster", "buildBaseBoundaryLinks", "buildSuperBoundaryLinks"},
            at = @At(value = "INVOKE", target = "Lcom/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime;runtimeTransition(Ljava/util/function/Supplier;)Ljava/lang/Object;"))
    private Object captured(@Coerce Object runtime, java.util.function.Supplier<?> action, Operation<Object> original,
                            @com.llamalad7.mixinextras.sugar.Share("buildWindowInput") com.llamalad7.mixinextras.sugar.ref.LocalRef<TopologyBuildTransitionProbe.Input> shared) {
        Object value = original.call(runtime, action);
        var input = TopologyBuildTransitionProbe.captured(action, value);
        shared.set(input);
        if (input != null) TopologyBuildWindowProbe.point(this, input.kind(), "captured", input.section(), input.input(), null);
        return value;
    }

    @WrapOperation(method = {"buildSuperCluster", "buildBaseBoundaryLinks", "buildSuperBoundaryLinks"},
            at = @At(value = "INVOKE", target = "Lcom/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime;runRuntimeTransition(Ljava/lang/Runnable;)V"))
    private void publishing(@Coerce Object runtime, Runnable action, Operation<Void> original,
                            @com.llamalad7.mixinextras.sugar.Share("buildWindowInput") com.llamalad7.mixinextras.sugar.ref.LocalRef<TopologyBuildTransitionProbe.Input> shared) {
        var input = shared.get();
        if (input != null) {
            Object result = TopologyBuildTransitionProbe.result(action, input);
            if (result != null) TopologyBuildWindowProbe.point(this, input.kind(), "publishing", input.section(), input.input(), result);
        }
        original.call(runtime, action);
    }
}
