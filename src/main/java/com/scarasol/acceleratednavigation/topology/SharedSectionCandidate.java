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

    SharedSectionCandidate(SectionPos section, RuntimeSharedTopology.Result source) {
        this.source = source;
        anchors = source.cells().stream().map(cell -> new BlockPos(
                section.minBlockX() + BaseClusterTopology.x(cell),
                section.minBlockY() + BaseClusterTopology.y(cell),
                section.minBlockZ() + BaseClusterTopology.z(cell))).toList();
    }

    public boolean current() { return source.current(); }
    public boolean candidate() { return source.candidate(); }
    public String state() { return source.state(); }
    public List<BlockPos> anchors() { return anchors; }
    public long newRows() { return source.newRows(); }

    public static boolean supports(BaseClusterTopology.TraversalProfile profile,
                                   BlockPos start, BlockPos goal, int accuracy) {
        var body = profile.geometry(BaseClusterTopology.Channel.GROUND);
        return accuracy == 0 && body.width() <= 1 && body.heightCells() >= 2
                && SectionPos.of(start).equals(SectionPos.of(goal));
    }
}
