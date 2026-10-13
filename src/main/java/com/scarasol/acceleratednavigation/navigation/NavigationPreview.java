package com.scarasol.acceleratednavigation.navigation;

import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.pathfinder.Path;

/** A query-only preview; its virtual actor is never added to a level or ticked. */
public final class NavigationPreview {
    public final CompletableFuture<NavigationRequestPath.State> future=new CompletableFuture<>();
    private final Runnable cancellation;
    NavigationPreview(Runnable cancellation) { this.cancellation=cancellation; }
    public void cancel() { cancellation.run(); }
}
