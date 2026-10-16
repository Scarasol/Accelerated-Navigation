package an.production.mixin;

import an.production.ColdTopologyTelemetry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets="com.scarasol.acceleratednavigation.topology.SuperClusterTopology",remap=false)
public abstract class ColdSuperStagesMixin {
    @Inject(method="build",at=@At("HEAD"))
    private static void begin(CallbackInfoReturnable<?> info){ColdTopologyTelemetry.begin("super");}
    @Inject(method="build",at=@At("RETURN"))
    private static void end(CallbackInfoReturnable<?> info){ColdTopologyTelemetry.end("super",null);}
    @Inject(method="boundaryLinks",at=@At("HEAD"))
    private static void boundaryBegin(CallbackInfoReturnable<?> info){ColdTopologyTelemetry.begin("boundary");}
    @Inject(method="boundaryLinks",at=@At("RETURN"))
    private static void boundaryEnd(CallbackInfoReturnable<?> info){ColdTopologyTelemetry.end("boundary",null);}
}
