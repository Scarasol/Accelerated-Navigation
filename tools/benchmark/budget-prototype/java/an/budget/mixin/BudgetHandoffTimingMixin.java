package an.budget.mixin;

import an.budget.HandoffTiming;
import com.scarasol.acceleratednavigation.navigation.SegmentedNavigationService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SegmentedNavigationService.class, remap = false)
public abstract class BudgetHandoffTimingMixin {
    @Inject(method = "settle", at = @At("HEAD"), cancellable = true)
    private void defer(@Coerce Object work, CallbackInfo callback) {
        if (HandoffTiming.defer(work)) callback.cancel();
    }
}
