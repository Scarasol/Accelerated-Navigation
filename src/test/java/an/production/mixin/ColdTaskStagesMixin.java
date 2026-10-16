package an.production.mixin;

import an.production.ColdTopologyTelemetry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets="com.scarasol.acceleratednavigation.topology.TopologyTaskExecutor",remap=false)
public abstract class ColdTaskStagesMixin {
    @Inject(method="takeNext",at=@At("RETURN"))
    private void selected(CallbackInfoReturnable<?> info){ColdTopologyTelemetry.queue(info.getReturnValue());}
}
