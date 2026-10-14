package an.production.mixin;

import an.production.PerformanceScheduleTelemetry;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.acceleratednavigation.api.ResumableSearch;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = NavigationScheduler.class, remap = false)
public abstract class PerformanceScheduleMixin {
    @Shadow @Final private MinecraftServer server;
    @Unique private Object anSelected;
    @Unique private boolean anWakeParked;

    @Inject(method = "takeNext", at = @At("RETURN"))
    private void selected(long now, CallbackInfoReturnable<Object> callback) {
        anSelected = callback.getReturnValue();
    }
    @WrapOperation(method = "drain", at = @At(value = "INVOKE",
            target = "Lcom/scarasol/acceleratednavigation/api/ResumableSearch;step(IJ)Lcom/scarasol/acceleratednavigation/api/ResumableSearch$Status;"))
    private ResumableSearch.Status measure(ResumableSearch<?> search, int limit, long deadline,
            Operation<ResumableSearch.Status> original) {
        PerformanceScheduleTelemetry.dispatch(server, anSelected);
        return original.call(search, limit, deadline);
    }
    @Inject(method = "wake", at = @At("HEAD"))
    private void waking(@Coerce Object request, CallbackInfo callback) {
        if (server.isSameThread()) anWakeParked = PerformanceScheduleTelemetry.parked(request);
    }
    @Inject(method = "wake", at = @At("RETURN"))
    private void woke(@Coerce Object request, CallbackInfo callback) {
        if (server.isSameThread() && anWakeParked) PerformanceScheduleTelemetry.wake(request);
    }
    @Inject(method = "cancelQueued", at = @At("RETURN"))
    private void cancelled(@Coerce Object request, CallbackInfo callback) {
        if (server.isSameThread()) PerformanceScheduleTelemetry.cancel(request);
    }
}
