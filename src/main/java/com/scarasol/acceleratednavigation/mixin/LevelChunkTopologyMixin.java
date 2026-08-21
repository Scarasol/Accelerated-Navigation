package com.scarasol.acceleratednavigation.mixin;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalLongRef;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
abstract class LevelChunkTopologyMixin implements TopologyService.ChunkFactsCarrier {

    @Shadow
    @Final
    private Level level;

    @Unique
    private TopologyService.ChunkFactsState acceleratedNavigation$facts =
            new TopologyService.ChunkFactsState();

    @Inject(
            method = "<init>(Lnet/minecraft/server/level/ServerLevel;"
                    + "Lnet/minecraft/world/level/chunk/ProtoChunk;"
                    + "Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V",
            at = @At("RETURN")
    )
    private void acceleratedNavigation$takeGeneratedFacts(
            ServerLevel serverLevel,
            ProtoChunk source,
            LevelChunk.PostLoadProcessor postLoad,
            CallbackInfo callback) {
        if (source instanceof TopologyService.ChunkFactsCarrier carrier) {
            acceleratedNavigation$facts = carrier.acceleratedNavigation$factsState();
        }
    }

    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void acceleratedNavigation$captureOldFacts(
            BlockPos position,
            BlockState state,
            boolean moved,
            CallbackInfoReturnable<BlockState> callback,
            @Share("oldFacts") LocalLongRef oldFacts) {
        if (level instanceof ServerLevel serverLevel
                && TopologyService.tracksFacts(serverLevel, (LevelChunk) (Object) this)) {
            oldFacts.set(TopologyService.sampleColumnFacts(
                    (LevelChunk) (Object) this, position));
        }
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void acceleratedNavigation$publishNewFacts(
            BlockPos position,
            BlockState state,
            boolean moved,
            CallbackInfoReturnable<BlockState> callback,
            @Share("oldFacts") LocalLongRef oldFacts) {
        if (!(level instanceof ServerLevel serverLevel)
                || !TopologyService.tracksFacts(serverLevel, (LevelChunk) (Object) this)
                || callback.getReturnValue() == null) {
            return;
        }
        long current = TopologyService.sampleColumnFacts(
                (LevelChunk) (Object) this, position);
        if (current != oldFacts.get()) {
            TopologyService.onColumnFactsChanged(
                    serverLevel,
                    (LevelChunk) (Object) this,
                    position,
                    oldFacts.get(),
                    current);
        }
    }

    @Override
    public TopologyService.ChunkFactsState acceleratedNavigation$factsState() {
        return acceleratedNavigation$facts;
    }

}
