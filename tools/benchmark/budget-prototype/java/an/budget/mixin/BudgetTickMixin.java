package an.budget.mixin;

import an.budget.BudgetPrototype;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MinecraftServer.class)
public abstract class BudgetTickMixin {
    // These names are pinned to build/createMcpToSrg/output.tsrg for 1.20.1.
    @WrapOperation(method = "m_130011_", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;m_5705_(Ljava/util/function/BooleanSupplier;)V"),
            remap = false, require = 1)
    private void budgetTick(MinecraftServer server, BooleanSupplier haveTime, Operation<Void> original) {
        BudgetPrototype.State state = BudgetPrototype.begin(server, haveTime);
        boolean completed = false;
        try {
            original.call(server, haveTime);
            completed = true;
        } finally {
            BudgetPrototype.finish(state, completed);
        }
    }
}
