package com.scarasol.acceleratednavigation.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.datafixers.util.Pair;
import com.scarasol.acceleratednavigation.navigation.NavigationPoiAdapter;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.AcquirePoi;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.pathfinder.Path;
import org.apache.commons.lang3.mutable.MutableLong;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AcquirePoi.class)
@SuppressWarnings("target")
public abstract class NavigationPoiMixin {
    private static final String TRIGGER="lambda$create$6(ZLorg/apache/commons/lang3/mutable/MutableLong;Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;Ljava/util/function/Predicate;Lnet/minecraft/world/entity/ai/behavior/declarative/MemoryAccessor;Ljava/util/Optional;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/PathfinderMob;J)Z";
    @Dynamic("Synthetic trigger verified against Forge 1.20.1 bytecode and mappings")
    @Inject(method=TRIGGER,at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerLevel;getRandom()Lnet/minecraft/util/RandomSource;"),
            cancellable=true,require=1,expect=1,allow=1)
    private static void retry(CallbackInfoReturnable<Boolean> callback,@Local(argsOnly=true) MutableLong clock,
            @Local(argsOnly=true) PathfinderMob mob,@Local(argsOnly=true) Predicate<Holder<PoiType>> types) {
        if(NavigationPoiAdapter.beforeRetry(clock,mob)) { callback.setReturnValue(true);return; }
        var batch=NavigationPoiAdapter.get(clock,mob);
        if(batch!=null&&batch.resume&&!NavigationPoiAdapter.validSelection(batch,((net.minecraft.server.level.ServerLevel)mob.level()).getPoiManager(),types)) {
            NavigationPoiAdapter.afterBusiness(clock,mob);callback.setReturnValue(true);
        }
    }
    @Dynamic
    @Inject(method=TRIGGER,at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/ai/behavior/AcquirePoi;findPathToPois(Lnet/minecraft/world/entity/Mob;Ljava/util/Set;)Lnet/minecraft/world/level/pathfinder/Path;"),
            cancellable=true,require=1,expect=1,allow=1)
    private static void query(CallbackInfoReturnable<Boolean> callback,@Local(argsOnly=true) MutableLong clock,
            @Local(argsOnly=true) PathfinderMob mob,@Local Set<Pair<Holder<PoiType>,BlockPos>> candidates) {
        if(NavigationPoiAdapter.beforeQuery(clock,mob,candidates))callback.setReturnValue(true);
    }
    @Dynamic
    @WrapOperation(method=TRIGGER,at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/ai/village/poi/PoiManager;findAllClosestFirstWithType(Ljava/util/function/Predicate;Ljava/util/function/Predicate;Lnet/minecraft/core/BlockPos;ILnet/minecraft/world/entity/ai/village/poi/PoiManager$Occupancy;)Ljava/util/stream/Stream;"),
            require=1,expect=1,allow=1)
    private static Stream<Pair<Holder<PoiType>,BlockPos>> batch(PoiManager manager,Predicate<Holder<PoiType>> types,
            Predicate<BlockPos> positions,BlockPos center,int range,PoiManager.Occupancy occupancy,
            Operation<Stream<Pair<Holder<PoiType>,BlockPos>>> original,@Local(argsOnly=true) MutableLong clock,
            @Local(argsOnly=true) PathfinderMob mob) {
        var batch=NavigationPoiAdapter.get(clock,mob);
        return batch!=null&&batch.resume?batch.candidates.stream():original.call(manager,types,positions,center,range,occupancy);
    }
    @Dynamic
    @WrapOperation(method=TRIGGER,at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/ai/behavior/AcquirePoi;findPathToPois(Lnet/minecraft/world/entity/Mob;Ljava/util/Set;)Lnet/minecraft/world/level/pathfinder/Path;"),
            require=1,expect=1,allow=1)
    private static Path result(Mob mob,Set<Pair<Holder<PoiType>,BlockPos>> candidates,Operation<Path> original,
            @Local(argsOnly=true) MutableLong clock,@Local LocalRef<Set<?>> currentCandidates) {
        var batch=NavigationPoiAdapter.get(clock,(PathfinderMob)mob);
        if(batch==null||!batch.resume)return original.call(mob,candidates);
        currentCandidates.set(batch.candidates);
        return batch.request;
    }
    @Dynamic
    @Inject(method=TRIGGER,at=@At("RETURN"),require=1)
    private static void consumed(CallbackInfoReturnable<Boolean> callback,@Local(argsOnly=true) MutableLong clock,
            @Local(argsOnly=true) PathfinderMob mob) {
        NavigationPoiAdapter.afterBusiness(clock,mob);
    }
}
