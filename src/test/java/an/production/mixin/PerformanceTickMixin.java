package an.production.mixin;

import an.production.PerformanceTickTelemetry;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = MinecraftServer.class, priority = 1100)
public abstract class PerformanceTickMixin {
    @WrapOperation(method = "m_130011_", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;m_5705_(Ljava/util/function/BooleanSupplier;)V"),
            remap = false, require = 1, expect = 1, allow = 1)
    private void measure(MinecraftServer server, BooleanSupplier haveTime, Operation<Void> original) {
        var span = PerformanceTickTelemetry.begin(server);
        boolean completed = false;
        try { original.call(server, haveTime); completed = true; }
        finally { PerformanceTickTelemetry.finish(server, span, completed); }
    }
}
