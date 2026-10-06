package com.scarasol.acceleratednavigation.mixin;

import com.scarasol.acceleratednavigation.topology.TopologyService;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;

@Mixin(ChunkSerializer.class)
abstract class ChunkSerializerTopologyMixin {

    private static final String VERSIONS_TAG = "accelerated_navigation_facts_versions";
    private static final String SECTION_TAG = "section";
    private static final String VERSION_TAG = "version";

    @Inject(method = "read", at = @At("RETURN"))
    private static void acceleratedNavigation$readFactVersions(
            ServerLevel level,
            PoiManager poiManager,
            ChunkPos position,
            CompoundTag chunkTag,
            CallbackInfoReturnable<ProtoChunk> callback) {
        ChunkAccess target = callback.getReturnValue();
        if (target instanceof ImposterProtoChunk imposter) {
            target = imposter.getWrapped();
        }
        if (!(target instanceof TopologyService.ChunkFactsCarrier carrier)) {
            return;
        }
        Map<Integer, Long> versions = new HashMap<>();
        boolean valid = true;
        Tag encoded = chunkTag.get(VERSIONS_TAG);
        if (encoded != null) {
            if (!(encoded instanceof ListTag list)) {
                valid = false;
            } else {
                for (Tag element : list) {
                    if (!(element instanceof CompoundTag entry)
                            || !entry.contains(SECTION_TAG, Tag.TAG_INT)
                            || !entry.contains(VERSION_TAG, Tag.TAG_LONG)) {
                        valid = false;
                        break;
                    }
                    int sectionY = entry.getInt(SECTION_TAG);
                    long version = entry.getLong(VERSION_TAG);
                    if (version < 0L || versions.put(sectionY, version) != null) {
                        valid = false;
                        break;
                    }
                }
            }
        }
        carrier.acceleratedNavigation$factsState().loadedVersions(versions, valid);
    }

    @Inject(method = "write", at = @At("RETURN"))
    private static void acceleratedNavigation$writeFactVersions(
            ServerLevel level,
            ChunkAccess chunk,
            CallbackInfoReturnable<CompoundTag> callback) {
        ChunkAccess source = chunk instanceof ImposterProtoChunk imposter
                ? imposter.getWrapped() : chunk;
        if (!(source instanceof TopologyService.ChunkFactsCarrier carrier)) {
            return;
        }
        if (!TopologyService.canReuseFactVersions(level.getServer())) {
            callback.getReturnValue().remove(VERSIONS_TAG);
            return;
        }
        ListTag encoded = new ListTag();
        carrier.acceleratedNavigation$factsState().versions().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(version -> {
                    CompoundTag entry = new CompoundTag();
                    entry.putInt(SECTION_TAG, version.getKey());
                    entry.putLong(VERSION_TAG, version.getValue());
                    encoded.add(entry);
                });
        if (encoded.isEmpty() || !TopologyService.canReuseFactVersions(level.getServer())) {
            callback.getReturnValue().remove(VERSIONS_TAG);
        } else {
            callback.getReturnValue().put(VERSIONS_TAG, encoded);
        }
    }
}
