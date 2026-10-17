package an.production.mixin;

import an.production.FailureSearchTelemetry;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.pathfinder.*;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PathFinder.class)
public abstract class PathFinderFailureMixin {
    @Inject(method="m_164716_",at=@At("HEAD"),remap=false,require=1)
    private void begin(ProfilerFiller profiler,Node start,Map<Target,BlockPos> targets,
            float range,int accuracy,float multiplier,CallbackInfoReturnable<Path> callback) {
        FailureSearchTelemetry.beginSearch(start,range);
    }
    @WrapOperation(method="m_164716_",at=@At(value="INVOKE",
            target="Lnet/minecraft/world/level/pathfinder/BinaryHeap;m_77091_()Lnet/minecraft/world/level/pathfinder/Node;"),
            remap=false,require=1,expect=1,allow=1)
    private Node pop(BinaryHeap heap,Operation<Node> original) {
        Node node=original.call(heap);FailureSearchTelemetry.popped();return node;
    }
    @WrapOperation(method="m_164716_",at=@At(value="FIELD",
            target="Lnet/minecraft/world/level/pathfinder/Node;f_77280_:F",opcode=Opcodes.GETFIELD,ordinal=1),
            remap=false,require=1,expect=1,allow=1)
    private float walked(Node node,Operation<Float> original) {
        float value=original.call(node);FailureSearchTelemetry.walked(value);return value;
    }
}
