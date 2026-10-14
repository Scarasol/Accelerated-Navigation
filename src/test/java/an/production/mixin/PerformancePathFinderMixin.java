package an.production.mixin;

import an.production.PerformanceSearchTelemetry;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PathFinder.class)
public abstract class PerformancePathFinderMixin {
    @Unique private PerformanceSearchTelemetry.Span anPerformanceSpan;
    @Inject(method = "m_77427_(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At("HEAD"), remap = false, require = 1)
    private void begin(PathNavigationRegion region, Mob mob, Set<BlockPos> targets,
            float range, int accuracy, float multiplier, CallbackInfoReturnable<Path> callback) {
        anPerformanceSpan = PerformanceSearchTelemetry.begin(mob);
    }
    @Inject(method = "m_77427_(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At("RETURN"), remap = false, require = 1)
    private void end(PathNavigationRegion region, Mob mob, Set<BlockPos> targets,
            float range, int accuracy, float multiplier, CallbackInfoReturnable<Path> callback) {
        PerformanceSearchTelemetry.end(anPerformanceSpan, callback.getReturnValue());
        anPerformanceSpan = null;
    }
}
