package com.scarasol.acceleratednavigation.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.acceleratednavigation.navigation.AutomaticNavigationBridge;
import com.scarasol.acceleratednavigation.navigation.NativeNavigationSearch;
import com.scarasol.acceleratednavigation.navigation.NavigationRequestPath;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PathNavigation.class)
public abstract class AutomaticNavigationMixin {
    @Shadow protected Path path;
    @Shadow protected long timeoutTimer;

    @Inject(method="createPath(Lnet/minecraft/world/entity/Entity;I)Lnet/minecraft/world/level/pathfinder/Path;",
            at=@At("HEAD"),cancellable=true,require=1,expect=1,allow=1)
    private void prepareEntity(Entity target,int accuracy,CallbackInfoReturnable<Path> callback) {
        PathNavigation navigation=(PathNavigation)(Object)this;
        if(AutomaticNavigationBridge.prepareAllowed(navigation))
            callback.setReturnValue(AutomaticNavigationBridge.prepare(navigation,
                    AutomaticNavigationBridge.movementIntent(navigation,target.blockPosition(),target,accuracy)));
    }
    @Inject(method="createPath(Lnet/minecraft/core/BlockPos;II)Lnet/minecraft/world/level/pathfinder/Path;",
            at=@At("HEAD"),cancellable=true,require=1,expect=1,allow=1)
    private void bounded(BlockPos target,int accuracy,int range,CallbackInfoReturnable<Path> callback) {
        PathNavigation navigation=(PathNavigation)(Object)this;
        if(AutomaticNavigationBridge.enabled(navigation))callback.setReturnValue(NativeNavigationSearch.legacy(navigation,
                AutomaticNavigationBridge.intent(navigation,Set.of(target),null,false,8,false,accuracy,range)));
    }
    @Inject(method="moveTo(DDDD)Z",at=@At("HEAD"),cancellable=true,require=1,expect=1,allow=1)
    private void moveCoordinates(double x,double y,double z,double speed,CallbackInfoReturnable<Boolean> callback) {
        PathNavigation navigation=(PathNavigation)(Object)this;
        if(AutomaticNavigationBridge.enabled(navigation)) {
            if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)) { callback.setReturnValue(false);return; }
            callback.setReturnValue(AutomaticNavigationBridge.move(navigation,AutomaticNavigationBridge.movementIntent(navigation,
                    BlockPos.containing(x,y,z),null,1),speed));
        }
    }
    @Inject(method="moveTo(Lnet/minecraft/world/entity/Entity;D)Z",at=@At("HEAD"),cancellable=true,require=1,expect=1,allow=1)
    private void moveEntity(Entity target,double speed,CallbackInfoReturnable<Boolean> callback) {
        PathNavigation navigation=(PathNavigation)(Object)this;
        if(AutomaticNavigationBridge.enabled(navigation))callback.setReturnValue(AutomaticNavigationBridge.move(navigation,
                AutomaticNavigationBridge.movementIntent(navigation,target.blockPosition(),target,1),speed));
    }
    @Inject(method="moveTo(Lnet/minecraft/world/level/pathfinder/Path;D)Z",at=@At("HEAD"),cancellable=true,require=1,expect=1,allow=1)
    private void movePath(Path path,double speed,CallbackInfoReturnable<Boolean> callback) {
        PathNavigation navigation=(PathNavigation)(Object)this;
        if(!AutomaticNavigationBridge.enabled(navigation))return;
        callback.setReturnValue(AutomaticNavigationBridge.movePath(navigation,path,speed));
    }
    @Inject(method="createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at=@At("HEAD"),cancellable=true,require=1,expect=1,allow=1)
    private void query(Set<BlockPos> targets,int offset,boolean upward,int accuracy,float range,CallbackInfoReturnable<Path> callback) {
        PathNavigation navigation=(PathNavigation)(Object)this;
        if(NativeNavigationSearch.raw()) {
            var scope=NativeNavigationSearch.scope();
            if(scope.entered) { scope.unknown=true;callback.setReturnValue(null); }
            else scope.entered=true;
            return;
        }
        if(!AutomaticNavigationBridge.enabled(navigation)||targets.isEmpty())return;
        var intent=AutomaticNavigationBridge.intent(navigation,targets,null,false,offset,upward,accuracy,range);
        callback.setReturnValue(AutomaticNavigationBridge.prepareAllowed(navigation)
                ?AutomaticNavigationBridge.prepare(navigation,intent):NativeNavigationSearch.legacy(navigation,intent));
    }
    @Inject(method="isDone()Z",at=@At("HEAD"),cancellable=true,require=1,expect=1,allow=1)
    private void wholeDone(CallbackInfoReturnable<Boolean> callback) {
        PathNavigation navigation=(PathNavigation)(Object)this;
        if(AutomaticNavigationBridge.enabled(navigation)&&AutomaticNavigationBridge.service(navigation).active(navigation)!=null)
            callback.setReturnValue(false);
    }
    @WrapOperation(method="tick()V",at=@At(value="INVOKE",
            target="Lnet/minecraft/world/entity/ai/navigation/PathNavigation;isDone()Z"),require=2,expect=2,allow=2)
    private boolean localDone(PathNavigation navigation,Operation<Boolean> original) {
        return AutomaticNavigationBridge.localDone(navigation,()->original.call(navigation));
    }
    @Inject(method="stop()V",at=@At("HEAD"),cancellable=true,require=1,expect=1,allow=1)
    private void stop(CallbackInfo callback) {
        if(AutomaticNavigationBridge.externalStop((PathNavigation)(Object)this))callback.cancel();
    }
    @ModifyArg(method="doStuckDetection(Lnet/minecraft/world/phys/Vec3;)V",
            at=@At(value="INVOKE",
                    target="Lnet/minecraft/world/phys/Vec3;atBottomCenterOf(Lnet/minecraft/core/Vec3i;)Lnet/minecraft/world/phys/Vec3;"),
            index=0,require=1,expect=1,allow=1)
    private Vec3i nextNodeTimeout(Vec3i next) {
        if(path instanceof NavigationRequestPath)timeoutTimer=0L;
        return next;
    }
    @WrapOperation(method={"doStuckDetection(Lnet/minecraft/world/phys/Vec3;)V","timeoutPath()V"},at=@At(value="INVOKE",
            target="Lnet/minecraft/world/entity/ai/navigation/PathNavigation;stop()V"),require=2,expect=2,allow=2)
    private void nativeStop(PathNavigation navigation,Operation<Void> original) {
        AutomaticNavigationBridge.nativeStop(navigation,()->original.call(navigation));
    }
    @Inject(method="recomputePath()V",at=@At("HEAD"),cancellable=true,require=1,expect=1,allow=1)
    private void recompute(CallbackInfo callback) {
        PathNavigation navigation=(PathNavigation)(Object)this;
        if(AutomaticNavigationBridge.enabled(navigation)&&AutomaticNavigationBridge.service(navigation).recompute(navigation))callback.cancel();
    }
    @Inject(method="setSpeedModifier(D)V",at=@At("HEAD"),require=1,expect=1,allow=1)
    private void speed(double value,CallbackInfo callback) {
        PathNavigation navigation=(PathNavigation)(Object)this;
        if(AutomaticNavigationBridge.enabled(navigation))AutomaticNavigationBridge.service(navigation).speed(navigation,value);
    }
    @WrapOperation(method="followThePath()V",at=@At(value="INVOKE",
            target="Lnet/minecraft/world/level/pathfinder/Path;advance()V"),require=1,expect=1,allow=1)
    private void advanceFollowing(Path path,Operation<Void> original) {
        if(!AutomaticNavigationBridge.deferAdvance((PathNavigation)(Object)this,path,true))original.call(path);
    }
    @WrapOperation(method="tick()V",at=@At(value="INVOKE",
            target="Lnet/minecraft/world/level/pathfinder/Path;advance()V"),require=1,expect=1,allow=1)
    private void advanceFalling(Path path,Operation<Void> original) {
        if(!AutomaticNavigationBridge.deferAdvance((PathNavigation)(Object)this,path,false))original.call(path);
    }
    @WrapOperation(method="createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at=@At(value="INVOKE",target="Lnet/minecraft/util/profiling/ProfilerFiller;push(Ljava/lang/String;)V"),
            require=1,expect=1,allow=1)
    private void enter(ProfilerFiller profiler,String label,Operation<Void> original) {
        original.call(profiler,label);
        if(NativeNavigationSearch.raw())NativeNavigationSearch.scope().profilers.push(profiler);
    }
    @WrapOperation(method="createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at=@At(value="INVOKE",target="Lnet/minecraft/util/profiling/ProfilerFiller;pop()V"),
            require=1,expect=1,allow=1)
    private void leave(ProfilerFiller profiler,Operation<Void> original) {
        if(NativeNavigationSearch.raw())NativeNavigationSearch.scope().profilers.pop();
        original.call(profiler);
    }
}
