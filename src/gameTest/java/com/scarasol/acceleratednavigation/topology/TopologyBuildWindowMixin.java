package com.scarasol.acceleratednavigation.topology;

import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = TopologyWorkerRuntime.class, remap = false)
abstract class TopologyBuildWindowMixin {
    @Redirect(method = "build", at = @At(value = "INVOKE", target = "Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology;build(Lnet/minecraft/core/SectionPos;JLcom/scarasol/acceleratednavigation/topology/BaseClusterTopology$BuildInput;Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology$GeometryKey;Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology$BuildScratch;)Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology;"))
    private BaseClusterTopology base(SectionPos section, long revision, BaseClusterTopology.BuildInput input,
                                     BaseClusterTopology.GeometryKey geometry, BaseClusterTopology.BuildScratch scratch) {
        TopologyBuildWindowProbe.point(this, "base", "captured", section, input, null);
        BaseClusterTopology result = BaseClusterTopology.build(section, revision, input, geometry, scratch);
        TopologyBuildWindowProbe.point(this, "base", "publishing", section, input, result);
        return result;
    }
    @Redirect(method = "buildSuperCluster", at = @At(value = "INVOKE", target = "Lcom/scarasol/acceleratednavigation/topology/SuperClusterTopology;build(Lnet/minecraft/core/SectionPos;[Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology;Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology$GeometryKey;Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology$MovementKey;Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology$BuildScratch;)Lcom/scarasol/acceleratednavigation/topology/SuperClusterTopology;"))
    private SuperClusterTopology parent(SectionPos origin, BaseClusterTopology[] children, BaseClusterTopology.GeometryKey geometry,
                                        BaseClusterTopology.MovementKey movement, BaseClusterTopology.BuildScratch scratch) {
        TopologyBuildWindowProbe.point(this, "parent", "captured", origin, children, null);
        SuperClusterTopology result = SuperClusterTopology.build(origin, children, geometry, movement, scratch);
        TopologyBuildWindowProbe.point(this, "parent", "publishing", origin, children, result);
        return result;
    }
    @Redirect(method = "buildBaseBoundaryLinks", at = @At(value = "INVOKE", target = "Lcom/scarasol/acceleratednavigation/topology/SuperClusterTopology;boundaryLinks(Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology;Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology;Lnet/minecraft/core/Direction;)Lcom/scarasol/acceleratednavigation/topology/SuperClusterTopology$BoundaryLinks;"))
    private SuperClusterTopology.BoundaryLinks baseBoundary(BaseClusterTopology source, BaseClusterTopology target, Direction face) {
        TopologyBuildWindowProbe.point(this, "base-boundary", "captured", source.section(), source, null);
        var result = SuperClusterTopology.boundaryLinks(source, target, face);
        TopologyBuildWindowProbe.point(this, "base-boundary", "publishing", source.section(), source, result);
        return result;
    }
    @Redirect(method = "buildSuperBoundaryLinks", at = @At(value = "INVOKE", target = "Lcom/scarasol/acceleratednavigation/topology/SuperClusterTopology;crossingIndex(Lnet/minecraft/core/Direction;Lcom/scarasol/acceleratednavigation/topology/SuperClusterTopology;[Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology;[Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology;)Lcom/scarasol/acceleratednavigation/topology/SuperClusterTopology$CrossingIndex;"))
    private SuperClusterTopology.CrossingIndex parentBoundary(SuperClusterTopology source, Direction face, SuperClusterTopology target,
                                                              BaseClusterTopology[] sourceChildren, BaseClusterTopology[] targetChildren) {
        TopologyBuildWindowProbe.point(this, "parent-boundary", "captured", source.origin(), source, null);
        var result = source.crossingIndex(face, target, sourceChildren, targetChildren);
        TopologyBuildWindowProbe.point(this, "parent-boundary", "publishing", source.origin(), source, result);
        return result;
    }
}
