package com.scarasol.acceleratednavigation.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(NodeEvaluator.class)
public abstract class NativeNodeCacheMixin {
    @Shadow @Final protected Int2ObjectMap<Node> nodes;

    /**
     * @author AcceleratedNavigation
     * @reason Avoid evaluating a capturing factory on an existing node.
     */
    @Overwrite
    protected Node getNode(int x, int y, int z) {
        int key = Node.createHash(x, y, z);
        Node node = nodes.get(key);
        if (node != nodes.defaultReturnValue()) return node;
        return nodes.computeIfAbsent(key, ignored -> new Node(x, y, z));
    }
}
