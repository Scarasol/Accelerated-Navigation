package com.scarasol.acceleratednavigation.topology;

import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = TopologyWorkerRuntime.class, remap = false)
abstract class TopologyBuildCausalityMixin {
    @Redirect(method = "build", at = @At(value = "INVOKE", target = "Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology;build(Lnet/minecraft/core/SectionPos;JLcom/scarasol/acceleratednavigation/topology/BaseClusterTopology$BuildInput;Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology$GeometryKey;Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology$BuildScratch;)Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology;"))
    private BaseClusterTopology base(SectionPos section, long revision, BaseClusterTopology.BuildInput input,
                                     BaseClusterTopology.GeometryKey geometry, BaseClusterTopology.BuildScratch scratch) {
        var span = TopologyCausalityObservation.begin("build", "BaseClusterTopology.build", this);
        BaseClusterTopology result = null;
        try { return result = BaseClusterTopology.build(section, revision, input, geometry, scratch); }
        finally { if (span != null) TopologyCausalityObservation.end(span, Map.of("section", section, "version", revision,
                "fingerprint", input.center().fingerprint(), "geometry", geometry, "resultToken", TopologyValidationAccess.token(result), "completed", result != null)); }
    }
    @Redirect(method = "buildSuperCluster", at = @At(value = "INVOKE", target = "Lcom/scarasol/acceleratednavigation/topology/SuperClusterTopology;build(Lnet/minecraft/core/SectionPos;[Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology;Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology$GeometryKey;Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology$MovementKey;Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology$BuildScratch;)Lcom/scarasol/acceleratednavigation/topology/SuperClusterTopology;"))
    private SuperClusterTopology parent(SectionPos origin, BaseClusterTopology[] children, BaseClusterTopology.GeometryKey geometry,
                                        BaseClusterTopology.MovementKey movement, BaseClusterTopology.BuildScratch scratch) {
        var span = TopologyCausalityObservation.begin("build", "SuperClusterTopology.build", this);
        SuperClusterTopology result = null;
        try { return result = SuperClusterTopology.build(origin, children, geometry, movement, scratch); }
        finally { if (span != null) TopologyCausalityObservation.end(span, Map.of("origin", origin, "children", children.length,
                "geometry", geometry, "movement", movement, "resultToken", TopologyValidationAccess.token(result), "completed", result != null)); }
    }
    @Redirect(method = "buildBaseBoundaryLinks", at = @At(value = "INVOKE", target = "Lcom/scarasol/acceleratednavigation/topology/SuperClusterTopology;boundaryLinks(Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology;Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology;Lnet/minecraft/core/Direction;)Lcom/scarasol/acceleratednavigation/topology/SuperClusterTopology$BoundaryLinks;"))
    private SuperClusterTopology.BoundaryLinks baseBoundary(BaseClusterTopology source, BaseClusterTopology target, Direction face) {
        var span = TopologyCausalityObservation.begin("build", "SuperClusterTopology.boundaryLinks", this);
        SuperClusterTopology.BoundaryLinks result = null;
        try { return result = SuperClusterTopology.boundaryLinks(source, target, face); }
        finally { if (span != null) TopologyCausalityObservation.end(span, Map.of("source", source.section(), "target", target.section(),
                "sourceToken", TopologyValidationAccess.token(source), "targetToken", TopologyValidationAccess.token(target),
                "face", face, "resultToken", TopologyValidationAccess.token(result), "completed", result != null)); }
    }
    @Redirect(method = "buildSuperBoundaryLinks", at = @At(value = "INVOKE", target = "Lcom/scarasol/acceleratednavigation/topology/SuperClusterTopology;crossingIndex(Lnet/minecraft/core/Direction;Lcom/scarasol/acceleratednavigation/topology/SuperClusterTopology;[Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology;[Lcom/scarasol/acceleratednavigation/topology/BaseClusterTopology;)Lcom/scarasol/acceleratednavigation/topology/SuperClusterTopology$CrossingIndex;"))
    private SuperClusterTopology.CrossingIndex parentBoundary(SuperClusterTopology source, Direction face, SuperClusterTopology target,
                                                              BaseClusterTopology[] sourceChildren, BaseClusterTopology[] targetChildren) {
        var span = TopologyCausalityObservation.begin("build", "SuperClusterTopology.crossingIndex", this);
        SuperClusterTopology.CrossingIndex result = null;
        try { return result = source.crossingIndex(face, target, sourceChildren, targetChildren); }
        finally { if (span != null) TopologyCausalityObservation.end(span, Map.of("source", source.origin(), "target", target.origin(),
                "sourceToken", TopologyValidationAccess.token(source), "targetToken", TopologyValidationAccess.token(target),
                "face", face, "resultToken", TopologyValidationAccess.token(result), "completed", result != null)); }
    }
}
