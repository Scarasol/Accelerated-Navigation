package an.production.mixin;

import an.production.ColdTopologyTelemetry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets="com.scarasol.acceleratednavigation.topology.MacroSearch",remap=false)
public abstract class ColdMacroStagesMixin {
    @Inject(method="step",at=@At("HEAD"))
    private void begin(CallbackInfoReturnable<?> info){ColdTopologyTelemetry.begin("macro-step");}
    @Inject(method="step",at=@At("RETURN"))
    private void end(CallbackInfoReturnable<?> info){ColdTopologyTelemetry.end("macro-step",null);}
}
