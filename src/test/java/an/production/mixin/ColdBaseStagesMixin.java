package an.production.mixin;

import an.production.ColdTopologyTelemetry;
import com.scarasol.acceleratednavigation.topology.BaseClusterTopology;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=BaseClusterTopology.class,remap=false)
public abstract class ColdBaseStagesMixin {
    @Inject(method="build",at=@At("HEAD"))
    private static void buildBegin(CallbackInfoReturnable<?> info){ColdTopologyTelemetry.begin("base");}
    @Inject(method="build",at=@At("RETURN"))
    private static void buildEnd(CallbackInfoReturnable<?> info){ColdTopologyTelemetry.end("base",info.getReturnValue());}
    @Inject(method="deriveLegalAnchors",at=@At("HEAD"))
    private static void legalBegin(CallbackInfo info){ColdTopologyTelemetry.begin("legal");}
    @Inject(method="deriveLegalAnchors",at=@At("RETURN"))
    private static void legalEnd(CallbackInfo info){ColdTopologyTelemetry.end("legal",null);}
    @Inject(method="labelComponents",at=@At("HEAD"))
    private static void labelBegin(CallbackInfoReturnable<?> info){ColdTopologyTelemetry.begin("labels");}
    @Inject(method="labelComponents",at=@At("RETURN"))
    private static void labelEnd(CallbackInfoReturnable<?> info){ColdTopologyTelemetry.end("labels",null);}
    @Inject(method="buildGroundEdges",at=@At("HEAD"))
    private static void edgeBegin(CallbackInfoReturnable<?> info){ColdTopologyTelemetry.begin("edges");}
    @Inject(method="buildGroundEdges",at=@At("RETURN"))
    private static void edgeEnd(CallbackInfoReturnable<?> info){ColdTopologyTelemetry.end("edges",null);}
}
