package an.production.mixin;

import an.production.ColdTopologyTelemetry;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets="com.scarasol.acceleratednavigation.topology.TopologyWorkerRuntime",remap=false)
public abstract class ColdRuntimeStagesMixin {
    @Inject(method="joinCanonical(Lcom/scarasol/acceleratednavigation/topology/TopologyWorkerRuntime$MacroRequest;Ljava/util/List;Ljava/util/List;Z)V",at=@At("HEAD"))
    private void canonical(@Coerce Object request,List<?> starts,List<?> goals,boolean hierarchical,CallbackInfo info){
        ColdTopologyTelemetry.mark("canonical",hierarchical);
    }
    @Inject(method="joinResolve",at=@At("HEAD"))
    private void resolve(CallbackInfo info){ColdTopologyTelemetry.mark("endpoint-resolve",true);}
    @Inject(method="beginBuild",at=@At("HEAD"))
    private void captureBegin(CallbackInfoReturnable<?> info){ColdTopologyTelemetry.begin("input-capture");}
    @Inject(method="beginBuild",at=@At("RETURN"))
    private void captureEnd(CallbackInfoReturnable<?> info){ColdTopologyTelemetry.end("input-capture",null);}
    @Inject(method="publish",at=@At("HEAD"))
    private void publish(CallbackInfo info){ColdTopologyTelemetry.mark("base-publication",true);}
    @Inject(method="requestSuperCluster",at=@At("HEAD"))
    private void superDemand(CallbackInfo info){ColdTopologyTelemetry.mark("super-demand",true);}
    @Inject(method="publishSuperCluster",at=@At("HEAD"))
    private void superPublish(CallbackInfo info){ColdTopologyTelemetry.mark("super-publication",true);}
}
