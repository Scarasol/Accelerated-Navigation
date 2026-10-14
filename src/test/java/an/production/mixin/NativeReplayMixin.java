package an.production.mixin;

import an.production.NativeReplayTelemetry;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.pathfinder.BinaryHeap;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Target;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = PathFinder.class, remap = false)
public abstract class NativeReplayMixin {
    @Shadow @Final private int f_77421_;
    @Shadow @Final private BinaryHeap f_77423_;
    @Inject(method = "m_164716_", at = @At("HEAD"))
    private void begin(ProfilerFiller profiler, Node start, Map<Target, BlockPos> targets,
            float range, int accuracy, float multiplier, CallbackInfoReturnable<Path> callback) {
        NativeReplayTelemetry.started(start, range, multiplier, f_77421_);
    }
    @WrapOperation(method = "m_164716_", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/pathfinder/BinaryHeap;m_77091_()Lnet/minecraft/world/level/pathfinder/Node;"))
    private Node pop(BinaryHeap heap, Operation<Node> original) {
        Node node = original.call(heap); NativeReplayTelemetry.popped(node); return node;
    }
    @WrapOperation(method = "m_164716_", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/pathfinder/NodeEvaluator;m_6065_([Lnet/minecraft/world/level/pathfinder/Node;Lnet/minecraft/world/level/pathfinder/Node;)I"))
    private int neighbors(NodeEvaluator evaluator, Node[] nodes, Node node, Operation<Integer> original) {
        int count = original.call(evaluator, nodes, node);
        NativeReplayTelemetry.neighbors(node, nodes, count); return count;
    }
    @Inject(method = "m_164716_", at = @At("RETURN"))
    private void finish(ProfilerFiller profiler, Node start, Map<Target, BlockPos> targets,
            float range, int accuracy, float multiplier, CallbackInfoReturnable<Path> callback) {
        NativeReplayTelemetry.finished(f_77423_, callback.getReturnValue());
    }
}
