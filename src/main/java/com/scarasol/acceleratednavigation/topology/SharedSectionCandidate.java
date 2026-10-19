package com.scarasol.acceleratednavigation.topology;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

/** A current macro candidate; its anchors never replace a real native Path. */
public final class SharedSectionCandidate {
    public static final class UnsupportedInputException extends IllegalArgumentException {
        UnsupportedInputException(String message) { super(message); }
    }
    public static final class AdmissionRejectedException extends java.util.concurrent.RejectedExecutionException {
        AdmissionRejectedException(String message) { super(message); }
    }
    private final RuntimeSharedTopology.Result source;
    private final List<BlockPos> anchors;
    private final int walkingSuffix;

    SharedSectionCandidate(SectionPos section, RuntimeSharedTopology.Result source) {
        this.source = source;
        anchors = source.cells().stream().map(cell -> new BlockPos(
                section.minBlockX() + BaseClusterTopology.x(cell),
                section.minBlockY() + BaseClusterTopology.y(cell),
                section.minBlockZ() + BaseClusterTopology.z(cell))).toList();
        int suffix=anchors.size()-1;
        while(suffix>0) {
            BlockPos from=anchors.get(suffix-1),to=anchors.get(suffix);
            if(from.getY()!=to.getY()||from.distManhattan(to)!=1)break;
            suffix--;
        }
        walkingSuffix=suffix;
    }

    public boolean current() { return source.current(); }
    public boolean candidate() { return source.candidate(); }
    public String state() { return source.state(); }
    public List<BlockPos> anchors() { return anchors; }
    public long newRows() { return source.newRows(); }

    public int stageEnd(int cursor,float range) {
        return stageEnd(cursor,range,true);
    }

    public int stageEnd(int cursor,float range,boolean coalesce) {
        if(cursor<0||cursor>=anchors.size())throw new IndexOutOfBoundsException("shared cursor");
        if(!Float.isFinite(range)||range<0)throw new IllegalArgumentException("shared range");
        int last=anchors.size()-1;
        // A level unit-step suffix stays in one ordinary Base component.
        // Native PathFinder requires walkedDistance < range, including at the target.
        return coalesce&&cursor>=walkingSuffix&&last-cursor<range?last:Math.min(last,cursor+4);
    }

    public static boolean supports(BaseClusterTopology.TraversalProfile profile,
                                   BlockPos start, BlockPos goal, int accuracy) {
        var body = profile.geometry(BaseClusterTopology.Channel.GROUND);
        return accuracy == 0 && body.width() <= 1 && body.heightCells() >= 2
                && SectionPos.of(start).equals(SectionPos.of(goal));
    }
}
