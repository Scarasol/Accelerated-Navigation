package an.production.mixin;

import an.production.ShortNativeScenarioProbe;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=TopologyService.class,remap=false)
public abstract class ShortNativeLifecycleMixin {
    @Inject(method="requestMacroQuery",at=@At("HEAD"),remap=false,require=1)
    private void macro(CallbackInfoReturnable<?> callback) { ShortNativeScenarioProbe.macro(); }

    @Inject(method="requestSharedSection",at=@At("HEAD"),remap=false,require=1)
    private void shared(CallbackInfoReturnable<?> callback) { ShortNativeScenarioProbe.shared(); }

    @Inject(method="prepareGroundWindow",at=@At("HEAD"),remap=false,require=1)
    private void window(CallbackInfoReturnable<?> callback) { ShortNativeScenarioProbe.window(); }
}
