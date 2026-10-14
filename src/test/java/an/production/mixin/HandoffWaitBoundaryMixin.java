package an.production.mixin;

import an.production.HandoffWaitBoundaryProbe;
import an.production.HandoffDiagnostics;
import com.scarasol.acceleratednavigation.navigation.SegmentedNavigationService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;

@Mixin(value = SegmentedNavigationService.class, remap = false)
public abstract class HandoffWaitBoundaryMixin {
    @Inject(method = "settle", at = @At("HEAD"))
    private void beginning(@Coerce Object work, CallbackInfo callback) {
        HandoffDiagnostics.event((SegmentedNavigationService) (Object) this, work, "settle-head");
    }
    @Inject(method = "settle", at = @At("RETURN"))
    private void completed(@Coerce Object work, CallbackInfo callback) {
        HandoffDiagnostics.event((SegmentedNavigationService) (Object) this, work, "settle-return");
    }
    @Inject(method = "deferSegmentAdvance", at = @At("RETURN"))
    private void advanced(PathNavigation navigation, Path path, boolean following, CallbackInfoReturnable<Boolean> callback) {
        HandoffDiagnostics.advanced((SegmentedNavigationService) (Object) this,
                navigation, path, callback.getReturnValue(), following);
        HandoffWaitBoundaryProbe.afterAdvance((SegmentedNavigationService) (Object) this,
                navigation, path, callback.getReturnValue(), following);
    }
    @Inject(method = "recover", at = @At("HEAD"))
    private void recovering(@Coerce Object work, CallbackInfo callback) {
        HandoffDiagnostics.event((SegmentedNavigationService) (Object) this, work, "recover-head");
    }
    @Inject(method = "observe", at = @At("RETURN"))
    private void observed(CallbackInfo callback) {
        HandoffDiagnostics.observed((SegmentedNavigationService) (Object) this);
    }
}
