package an.production.mixin;

import an.production.SharedRejectionControls;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets="com.scarasol.acceleratednavigation.topology.TopologyWorkerRuntime", remap=false)
public abstract class SharedSubmissionRejectionMixin {
    @Inject(method="submitSharedWork",at=@At("HEAD"),cancellable=true,require=0)
    private void reject(Runnable work,Consumer<?> accepted,Consumer<Throwable> rejected,CallbackInfo info) {
        if(!SharedRejectionControls.rejectSubmission)return;
        SharedRejectionControls.rejectSubmission=false;
        SharedRejectionControls.submissionHits++;
        info.cancel();
        try {
            var after=getClass().getDeclaredMethod("sharedAfter",Runnable.class);after.setAccessible(true);
            var transition=getClass().getDeclaredMethod("sharedTransition",Supplier.class);transition.setAccessible(true);
            after.invoke(this,(Runnable)()-> {
                try {
                    transition.invoke(this,(Supplier<Object>)()-> {
                        rejected.accept(new RejectedExecutionException("controlled generic worker rejection"));
                        return null;
                    });
                } catch(ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            });
        } catch(ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
}
