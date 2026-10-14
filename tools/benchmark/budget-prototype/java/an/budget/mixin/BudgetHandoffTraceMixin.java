package an.budget.mixin;

import an.budget.HandoffTrace;
import com.scarasol.acceleratednavigation.navigation.SegmentedNavigationService;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SegmentedNavigationService.class, remap = false)
public abstract class BudgetHandoffTraceMixin {
    @Inject(method = "initialize", at = @At("HEAD"))
    private void watch(@Coerce Object work, CallbackInfo callback) {
        HandoffTrace.watch(work);
        HandoffTrace.event(work, "initialize-head", null);
    }

    @Inject(method = "observe", at = @At("HEAD"))
    private void beforeObserve(CallbackInfo callback) { HandoffTrace.observe("observe-head"); }
    @Inject(method = "observe", at = @At("RETURN"))
    private void afterObserve(CallbackInfo callback) { HandoffTrace.observe("observe-return"); }

    @Inject(method = {"settle", "nextSegment", "recover", "refresh"}, at = @At("HEAD"))
    private void beforeStage(@Coerce Object work, CallbackInfo callback) {
        HandoffTrace.event(work, callback.getId() + "-head", null);
    }
    @Inject(method = {"settle", "nextSegment", "recover", "refresh"}, at = @At("RETURN"))
    private void afterStage(@Coerce Object work, CallbackInfo callback) {
        HandoffTrace.event(work, callback.getId() + "-return", null);
    }
    @Inject(method = {"physical", "intermediateArrival", "finalArrival"}, at = @At("RETURN"))
    private void decision(@Coerce Object work, CallbackInfoReturnable<Boolean> callback) {
        HandoffTrace.event(work, callback.getId() + "-decision", callback.getReturnValue());
    }

    @Inject(method = "publish", at = @At("RETURN"))
    private void published(@Coerce Object work, net.minecraft.world.level.pathfinder.Path path,
            CallbackInfo callback) { HandoffTrace.event(work, "publish-return", null); }
    @Inject(method = "findSegment", at = @At("HEAD"))
    private void find(@Coerce Object work, java.util.Set<?> targets, int accuracy,
            CallbackInfo callback) { HandoffTrace.event(work, "findSegment-head", null); }
    @Inject(method = "findSegment", at = @At("RETURN"))
    private void found(@Coerce Object work, java.util.Set<?> targets, int accuracy,
            CallbackInfo callback) { HandoffTrace.event(work, "findSegment-return", null); }

    @Inject(method = "nativeStopped", at = @At("HEAD"))
    private void stopHead(PathNavigation navigation, CallbackInfo callback) {
        HandoffTrace.nativeStop(this, navigation, "native-stop-head");
    }
    @Inject(method = "nativeStopped", at = @At("RETURN"))
    private void stopReturn(PathNavigation navigation, CallbackInfo callback) {
        HandoffTrace.nativeStop(this, navigation, "native-stop-return");
    }
    @Inject(method = "finish", at = @At("HEAD"))
    private void finishHead(@Coerce Object work, @Coerce Object state, CallbackInfo callback) {
        HandoffTrace.event(work, "finish-head", state.toString());
    }
    @Inject(method = "finish", at = @At("RETURN"))
    private void finishReturn(@Coerce Object work, @Coerce Object state, CallbackInfo callback) {
        HandoffTrace.event(work, "finish-return", state.toString());
    }
}
