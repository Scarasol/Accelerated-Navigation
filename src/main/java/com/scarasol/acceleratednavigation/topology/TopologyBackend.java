package com.scarasol.acceleratednavigation.topology;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Fine-navigation boundary that refines a bounded window of a chosen macro corridor. */
public interface TopologyBackend<S> {

    CompletableFuture<Refinement<S>> refine(CorridorWindow window,
                                              BaseClusterTopology.TraversalProfile profile);

    default String backendId() {
        return getClass().getName();
    }

    default Object movementProfileKey(BaseClusterTopology.TraversalProfile profile) {
        return List.of(backendId(), profile);
    }

    record CorridorWindow(List<MacroSearch.Endpoint> endpoints,
                          List<MacroSearch.Connection> connections,
                          int committedEndpointCount) {
        public CorridorWindow {
            endpoints = List.copyOf(endpoints);
            connections = List.copyOf(connections);
            if (endpoints.size() < 2) {
                throw new IllegalArgumentException("a refinement window needs at least two endpoints");
            }
            if (connections.size() + 1 != endpoints.size()) {
                throw new IllegalArgumentException("window connections do not match its endpoints");
            }
            if (committedEndpointCount < 1 || committedEndpointCount >= endpoints.size()) {
                throw new IllegalArgumentException("committed endpoint count is outside the window");
            }
        }

        public MacroSearch.Endpoint start() {
            return endpoints.get(0);
        }

        public MacroSearch.Endpoint goal() {
            return endpoints.get(endpoints.size() - 1);
        }
    }

    record Refinement<S>(Outcome outcome,
                         int reachedEndpointCount,
                         float cost,
                         @Nullable S segment) {
        public Refinement {
            Objects.requireNonNull(outcome, "outcome");
            if (outcome == Outcome.SUCCEEDED) {
                if (reachedEndpointCount < 2 || !Float.isFinite(cost) || cost < 0.0F) {
                    throw new IllegalArgumentException("invalid successful refinement");
                }
                Objects.requireNonNull(segment, "successful refinement requires a segment");
            } else if (segment != null || reachedEndpointCount != 0) {
                throw new IllegalArgumentException("unsuccessful refinement cannot publish a segment");
            }
        }

        public static <S> Refinement<S> succeeded(int reachedEndpointCount, float cost, S segment) {
            return new Refinement<>(Outcome.SUCCEEDED, reachedEndpointCount, cost, segment);
        }

        public static <S> Refinement<S> blocked() {
            return new Refinement<>(Outcome.BLOCKED, 0, Float.POSITIVE_INFINITY, null);
        }

        public static <S> Refinement<S> stale() {
            return new Refinement<>(Outcome.STALE, 0, Float.POSITIVE_INFINITY, null);
        }
    }

    enum Outcome {
        SUCCEEDED,
        BLOCKED,
        STALE
    }
}
