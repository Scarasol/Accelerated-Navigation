package an.production.mixin;

import an.production.TailHandoffChecks;
import com.scarasol.acceleratednavigation.navigation.SegmentedNavigationService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=SegmentedNavigationService.class,remap=false)
public abstract class TailHandoffMixin {
    @Inject(method="settleTail",at=@At("HEAD"),remap=false,require=0)
    private void before(@Coerce Object work,CallbackInfo callback) {TailHandoffChecks.before(this,work);}
    @Inject(method="settleTail",at=@At("RETURN"),remap=false,require=0)
    private void after(@Coerce Object work,CallbackInfo callback) {TailHandoffChecks.after(this,work);}
}
