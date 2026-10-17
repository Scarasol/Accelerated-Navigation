package an.production.mixin;

import an.production.FailureSearchTelemetry;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.acceleratednavigation.navigation.NativeNavigationSearch;
import com.scarasol.acceleratednavigation.navigation.NavigationRequestPath;
import com.scarasol.acceleratednavigation.navigation.SegmentedNavigationService;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.entity.ai.navigation.PathNavigation;

@Mixin(SegmentedNavigationService.class)
public abstract class FailureSearchMixin {
    @Inject(method="nativeStopped",at=@At("TAIL"),remap=false,require=1)
    private void stopped(PathNavigation navigation,CallbackInfo callback) {
        an.production.ShortNativeScenarioProbe.nativeStopped(navigation);
    }

    @WrapOperation(method={"findSegment","searchNative"}, at=@At(value="INVOKE",
            target="Lcom/scarasol/acceleratednavigation/navigation/NativeNavigationSearch;find(Lcom/scarasol/acceleratednavigation/navigation/NavigationRequestPath;Ljava/util/Set;IFLjava/util/Set;)Lcom/scarasol/acceleratednavigation/navigation/NativeNavigationSearch$Result;"),
            remap=false,require=2,expect=2,allow=2)
    private NativeNavigationSearch.Result search(NavigationRequestPath request,Set<BlockPos> targets,
            int accuracy,float range,Set<BlockPos> avoided,Operation<NativeNavigationSearch.Result> original) {
        NativeNavigationSearch.Result result=original.call(request,targets,accuracy,range,avoided);
        result=an.production.ShortNativeScenarioProbe.searched(request,targets,accuracy,range,avoided,result);
        FailureSearchTelemetry.search(request,targets,accuracy,range,avoided,result);
        an.production.FinalWindowScenarioProbe.searched(request,targets,accuracy,range,result);
        return result;
    }
}
