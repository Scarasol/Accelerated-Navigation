package an.production.mixin;

import an.production.ColdTopologyTelemetry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets="com.scarasol.acceleratednavigation.topology.TopologyService$MacroRequest",remap=false)
public abstract class ColdPublicationMixin {
    @Inject(method="completeWorker",at=@At("HEAD"))
    private void publication(CallbackInfo info){ColdTopologyTelemetry.mark("server-macro-publication",true);}
}
