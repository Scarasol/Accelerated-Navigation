package an.production.mixin;

import an.production.FinalWindowScenarioProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets="com.scarasol.acceleratednavigation.topology.TopologyService$MacroRequest",remap=false)
public abstract class FinalWindowLifecycleMixin {
    @Inject(method="completeWorker",at=@At("TAIL"),require=1,expect=1,allow=1,remap=false)
    private void delivered(CallbackInfo callback) {
        FinalWindowScenarioProbe.macroDelivered();
    }
}
