package com.scarasol.acceleratednavigation.navigation;

import java.util.ArrayList;
import java.util.List;
import java.util.Collection;
import java.lang.ref.WeakReference;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Deferred native decisions retain identities and revisions, without forcing or pinning chunks. */
public final class NativeQueryProof {
    private record Entry(int x,int z,WeakReference<ChunkAccess> chunk,boolean present,long revision) {}
    private final ServerLevel level;
    private final List<Entry> entries;
    private NativeQueryProof(ServerLevel level,List<Entry> entries) { this.level=level;this.entries=List.copyOf(entries); }
    static NativeQueryProof capture(ServerLevel level,PathNavigationRegion region) {
        var snapshot=(NavigationRegionSnapshot)region;
        var chunks=snapshot.acceleratedNavigation$chunks();
        List<Entry> entries=new ArrayList<>();
        for(int x=0;x<chunks.length;x++)for(int z=0;z<chunks[x].length;z++) {
            if(entries.size()>=256)return null;
            var chunk=chunks[x][z];
            long revision=chunk instanceof NavigationChunkRevision tracked?tracked.acceleratedNavigation$navigationRevision():0;
            entries.add(new Entry(x+snapshot.acceleratedNavigation$centerX(),z+snapshot.acceleratedNavigation$centerZ(),
                    new WeakReference<>(chunk),chunk!=null,revision));
        }
        return new NativeQueryProof(level,entries);
    }
    static NativeQueryProof capture(ServerLevel level,Collection<ChunkPos> positions) {
        List<Entry> entries=new ArrayList<>();
        for(ChunkPos position:positions) {
            var chunk=level.getChunkSource().getChunkNow(position.x,position.z);
            if(chunk==null||entries.size()>=256)return null;
            long revision=chunk instanceof NavigationChunkRevision tracked?tracked.acceleratedNavigation$navigationRevision():0;
            entries.add(new Entry(position.x,position.z,new WeakReference<>(chunk),true,revision));
        }
        return new NativeQueryProof(level,entries);
    }
    public boolean current() {
        for(Entry entry:entries) {
            var chunk=level.getChunkSource().getChunkNow(entry.x,entry.z);
            ChunkAccess expected=entry.chunk.get();
            if(entry.present&&expected==null||chunk!=expected)return false;
            if(chunk instanceof NavigationChunkRevision tracked&&tracked.acceleratedNavigation$navigationRevision()!=entry.revision)return false;
        }
        return true;
    }
}
