package an.production.mixin;

import an.production.SharedRejectionControls;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets="com.scarasol.acceleratednavigation.topology.TopologyService", remap=false)
public abstract class SharedUnavailableRejectionMixin {
    @Inject(method="requestSharedSection",at=@At("HEAD"),cancellable=true,require=0)
    private void reject(CallbackInfoReturnable<CompletableFuture<?>> info) {
        if(!SharedRejectionControls.rejectUnavailable)return;
        SharedRejectionControls.rejectUnavailable=false;
        SharedRejectionControls.unavailableHits++;
        info.setReturnValue(CompletableFuture.failedFuture(
                new RejectedExecutionException("controlled service unavailable rejection")));
    }
}
