package com.scarasol.acceleratednavigation.topology;

import java.lang.ref.WeakReference;
import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

// Base row sharing and diagnostics for sharing published boundary graphs.
final class GraphReuse {
    static final Comparator<BaseClusterTopology.GeometryKey> ORDER = Comparator
            .comparingInt(BaseClusterTopology.GeometryKey::heightCells)
            .thenComparingInt(BaseClusterTopology.GeometryKey::widthCells)
            .thenComparingDouble(BaseClusterTopology.GeometryKey::width)
            .thenComparingDouble(BaseClusterTopology.GeometryKey::height)
            .thenComparing(BaseClusterTopology.GeometryKey::canOpenDoors)
            .thenComparing(BaseClusterTopology.GeometryKey::acceptsFluid)
            .thenComparing(BaseClusterTopology.GeometryKey::channel);
    private static boolean containsBody(BaseClusterTopology.GeometryKey donor, BaseClusterTopology.GeometryKey target) {
        return donor.width() >= target.width() && donor.height() >= target.height();
    }
    static boolean preferredRows(BaseClusterTopology.GeometryKey donor, BaseClusterTopology.GeometryKey target) {
        return containsBody(donor,target) && (!donor.acceptsFluid() || target.acceptsFluid());
    }
    static boolean boundaryCompatible(BaseClusterTopology.GeometryKey donor, BaseClusterTopology.GeometryKey target) {
        // Fluid gates the anchors, not the swept collision query. Door differences need a local certificate.
        return supported(donor) && containsBody(donor,target);
    }
    static Comparator<BaseClusterTopology.GeometryKey> nearest(BaseClusterTopology.GeometryKey target, boolean rows) {
        return Comparator.<BaseClusterTopology.GeometryKey>comparingInt(g -> !rows || preferredRows(g,target) ? 0 : 1)
                .thenComparingDouble(g -> Math.abs((double)g.width()-target.width()) + Math.abs((double)g.height()-target.height()))
                .thenComparingInt(g -> g.canOpenDoors()==target.canOpenDoors() ? 0 : 1)
                .thenComparingInt(g -> !rows || g.acceptsFluid()==target.acceptsFluid() ? 0 : 1)
                .thenComparing(ORDER);
    }
    static boolean supported(BaseClusterTopology.GeometryKey g) {
        return g.channel() == BaseClusterTopology.Channel.GROUND && g.widthCells() == 1
                && g.heightCells() >= 2 && g.width() <= 1 && g.height() <= g.heightCells();
    }

    private record Fact(WeakReference<BaseClusterTopology.PackedFacts> ref, boolean absent, long revision) {
        static Fact take(BaseClusterTopology.BuildInput in, int y, long revision) {
            var f = in.atSection(0,y,0);
            return new Fact(new WeakReference<>(f), f == null, f == null ? -1 : in.revisionAtSection(0,y,0,revision));
        }
        boolean matches(BaseClusterTopology.BuildInput in, int y, long generation) {
            var f = in.atSection(0,y,0);
            return absent ? f == null : f != null && ref.get() == f
                    && revision == in.revisionAtSection(0,y,0,generation);
        }
    }

    static final class Rows {
        final BaseClusterTopology.GeometryKey geometry;
        final Fact below, center, above;
        final boolean doors;
        final short[] volume, ground, prism, legal;
        Rows(Rows donor, BaseClusterTopology.GeometryKey target) {
            this(donor, target, donor.volume, donor.ground, donor.prism, donor.legal);
        }
        Rows(Rows donor, BaseClusterTopology.GeometryKey target, short[] volume, short[] ground,
             short[] prism, short[] legal) {
            geometry = target;
            below = donor.below; center = donor.center; above = donor.above;
            doors = donor.doors;
            this.volume = volume; this.ground = ground; this.prism = prism; this.legal = legal;
        }
        Rows(BaseClusterTopology.BuildInput input,long gen,BaseClusterTopology.GeometryKey g,boolean doors,
             int[] volumes,int[] grounds,long[] prisms,long[] legal) {
            geometry=g;
            below=Fact.take(input,-1,gen); center=Fact.take(input,0,gen); above=Fact.take(input,1,gen);
            this.doors=doors;
            int yEnd=16+g.heightCells();
            volume=new short[yEnd*16]; ground=new short[256];
            prism=new short[272]; this.legal=new short[256];
            for(int y=0;y<yEnd;y++) for(int z=0;z<16;z++)
                volume[y*16+z]=(short)(volumes[y*32+z+8] >>> 8);
            for(int y=0;y<17;y++) for(int z=0;z<16;z++) {
                int i=y*16+z,shift=(i&3)*16;
                prism[i]=(short)(prisms[i>>>2] >>> shift);
                if(y<16) {
                    ground[i]=(short)(grounds[y*32+z+8] >>> 8);
                    this.legal[i]=(short)(legal[i>>>2] >>> shift);
                }
            }
        }
        boolean accepts(BaseClusterTopology.BuildInput input,long gen,BaseClusterTopology.GeometryKey g,boolean doors) {
            return geometry.heightCells()==g.heightCells() && !geometry.equals(g)
                    && this.doors==doors && below.matches(input,-1,gen) && center.matches(input,0,gen)
                    && above.matches(input,1,gen);
        }
        int bytes() { return 240 + 2*(volume.length+ground.length+prism.length+legal.length); }
    }

    private final Map<String,Long> counts = new TreeMap<>();
    private final boolean enabled;
    GraphReuse(boolean enabled) {
        this.enabled=enabled;
    }
    private final boolean detailed=Boolean.getBoolean("an.reuse.diagnostics");
    boolean diagnostics() { return detailed; }
    boolean enabled() { return enabled; }
    synchronized void mark(String event,long n) { if(n!=0 && (detailed || traceDonors)) counts.merge(event,n,Long::sum); }
    synchronized Map<String,Long> counts() { return Map.copyOf(counts); }

    record ViewIdentity(String dimension,int x,int y,int z,long revision,BaseClusterTopology.GeometryKey geometry) {}
    record Choice(String consumer,String result,ViewIdentity target,ViewIdentity adjacent,
                  ViewIdentity donor,ViewIdentity donorAdjacent,String face,long count) {}
    private final boolean traceDonors=Boolean.getBoolean("an.reuse.traceDonors");
    private static final int MAX_CHOICES=256;
    private final List<Choice> choices=new ArrayList<>();
    synchronized List<Choice> drainChoices() {
        var snapshot=List.copyOf(choices);
        choices.clear();
        return snapshot;
    }
    private static ViewIdentity identity(String dim,SectionPos p,long rev,BaseClusterTopology.GeometryKey g) {
        return new ViewIdentity(dim,p.x(),p.y(),p.z(),rev,g);
    }
    private static ViewIdentity identity(String dim,BaseClusterTopology view) {
        return identity(dim,view.section(),view.revision(),view.geometry());
    }
    synchronized void baseChoice(String outcome,String dim,SectionPos p,long rev,
                                 BaseClusterTopology.GeometryKey target,Rows donor) {
        if(!traceDonors) return;
        if(choices.size()>=MAX_CHOICES) { mark("trace.dropped",1); return; }
        choices.add(new Choice("base.rows",outcome,identity(dim,p,rev,target),null,
                donor==null?null:identity(dim,p,rev,donor.geometry),null,null,1));
    }
    private synchronized void boundaryChoice(String consumer,String outcome,String dim,BaseClusterTopology a,
                                             BaseClusterTopology b,BoundaryDonor donor,Direction face,long n) {
        if(!traceDonors || n==0) return;
        if(choices.size()>=MAX_CHOICES) { mark("trace.dropped",1); return; }
        choices.add(new Choice(consumer,outcome,identity(dim,a),identity(dim,b),
                donor==null?null:identity(dim,donor.from),donor==null?null:identity(dim,donor.to),face.name(),n));
    }
    synchronized void clear() { choices.clear(); }

    record BoundaryDonor(BaseClusterTopology from, BaseClusterTopology to,
                         SuperClusterTopology.BoundaryLinks links) {}

    BoundaryWork boundaryWork(String consumer, ResourceKey<Level> dimension, BaseClusterTopology from,
                              BaseClusterTopology to, Direction face) {
        return detailed || traceDonors ? new BoundaryWork(consumer, dimension.location().toString(),
                from, to, face) : null;
    }

    final class BoundaryWork {
        private final String consumer, dimension;
        private final BaseClusterTopology from, to;
        private final Direction face;
        long exact, inherited;
        BoundaryWork(String consumer, String dimension, BaseClusterTopology from, BaseClusterTopology to,
                     Direction face) {
            this.consumer = consumer;
            this.dimension = dimension;
            this.from = from;
            this.to = to;
            this.face = face;
        }
        void donor(BoundaryDonor donor, long reused, long doorFallback) {
            boolean doorDiffers = from.geometry().canOpenDoors() != donor.from.geometry().canOpenDoors();
            inherited += reused;
            mark(consumer + ".doorFallback", doorFallback);
            if (doorDiffers) mark(consumer + ".crossDoor", reused);
            boundaryChoice(consumer, "PUBLISHED_POSITIVE", dimension, from, to, donor, face, reused);
            boundaryChoice(consumer, "DOOR_RECHECK", dimension, from, to, donor, face, doorFallback);
        }
        void finish(int donors) {
            mark(consumer + ".donors", donors);
            mark(consumer + ".inherited", inherited);
            mark(consumer + ".exact", exact);
            boundaryChoice(consumer, donors == 0 ? "EXACT_NO_DONOR" : "EXACT_REMAINDER",
                    dimension, from, to, null, face, exact);
        }
    }
}
