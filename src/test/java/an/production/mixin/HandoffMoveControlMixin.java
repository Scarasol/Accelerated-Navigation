package an.production.mixin;

import an.production.HandoffWaitBoundaryProbe;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stops propulsion only in the isolated timeout fixture; navigation still ticks. */
@Mixin(value = MoveControl.class, remap = false)
public abstract class HandoffMoveControlMixin {
    @Shadow @Final protected Mob f_24974_;

    @Inject(method = "m_8126_", at = @At("HEAD"), cancellable = true)
    private void freeze(CallbackInfo callback) {
        if (HandoffWaitBoundaryProbe.freeze(f_24974_)) {
            f_24974_.setZza(0);
            f_24974_.setXxa(0);
            f_24974_.setDeltaMovement(0, f_24974_.getDeltaMovement().y, 0);
            callback.cancel();
        }
    }
}
