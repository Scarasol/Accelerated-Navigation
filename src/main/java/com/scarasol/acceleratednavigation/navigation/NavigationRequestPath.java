package com.scarasol.acceleratednavigation.navigation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

/** Stable API handle containing only the current segment's real native nodes. */
public final class NavigationRequestPath extends Path {
    public enum State { PENDING, REACHABLE, UNREACHABLE, UNAVAILABLE, SUCCEEDED, EXHAUSTED, FAILED, CANCELLED }
    public final UUID queryId = UUID.randomUUID();
    public final UUID macroOwner = UUID.randomUUID();
    public final PathNavigation navigation;
    public final Mob mob;
    public final NavigationIntent intent;
    public final Object controller;
    public final long controlGeneration;
    private final List<Node> nodes;
    private BlockPos target;
    private boolean selected;
    public State state = State.PENDING;
    public boolean adopted;
    public boolean nativeBackend, nativeReachable;
    public double speed;
    public long createdTick;
    public long lastObservedTick = -1;

    NavigationRequestPath(PathNavigation navigation, Mob mob, NavigationIntent intent, Object controller,
            long generation, long tick) {
        this(navigation, mob, intent, controller, generation, tick, new ArrayList<>());
    }

    private NavigationRequestPath(PathNavigation navigation, Mob mob, NavigationIntent intent, Object controller,
            long generation, long tick, List<Node> nodes) {
        super(nodes, intent.targets().iterator().next(), false);
        this.navigation = navigation;
        this.mob = mob;
        this.intent = intent;
        this.controller = controller;
        this.controlGeneration = generation;
        this.createdTick = tick;
        this.nodes = nodes;
        this.target = intent.targets().iterator().next().immutable();
        selected = intent.targets().size() == 1;
    }

    public boolean terminal() {
        return state == State.SUCCEEDED || state == State.EXHAUSTED || state == State.FAILED || state == State.CANCELLED
                || state == State.UNAVAILABLE || state == State.UNREACHABLE;
    }

    public void select(BlockPos target) {
        this.target = target.immutable();
        selected = true;
    }

    public void publish(Path nativePath) {
        nodes.clear();
        setNextNodeIndex(0);
        if (nativePath != null) for (int i = 0; i < nativePath.getNodeCount(); i++) nodes.add(nativePath.getNode(i));
    }

    public void clearNodes() { nodes.clear(); setNextNodeIndex(0); }
    @Override public BlockPos getTarget() { return target; }
    @Override public boolean canReach() {
        return (state == State.REACHABLE || state == State.SUCCEEDED) && (!nativeBackend || nativeReachable);
    }
    @Override public float getDistToTarget() {
        return getEndNode() == null ? Float.MAX_VALUE : getEndNode().distanceManhattan(target);
    }

    @Override public void writeToStream(FriendlyByteBuf buffer) {
        if (!selected) throw new IllegalStateException("unselected multi-target query has no debug snapshot");
        buffer.writeBoolean(canReach());
        buffer.writeInt(getNextNodeIndex());
        buffer.writeInt(0);
        buffer.writeInt(target.getX());
        buffer.writeInt(target.getY());
        buffer.writeInt(target.getZ());
        buffer.writeInt(nodes.size());
        for (Node node : nodes) node.writeToStream(buffer);
        buffer.writeInt(0);
        buffer.writeInt(0);
    }
}
