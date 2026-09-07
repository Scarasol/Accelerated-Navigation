package com.scarasol.acceleratednavigation.gametest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/** Test-only route qualification directory and bounded reference search. */
final class TerrainRouteManifest {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final String NORMAL_SELECTION = "normal";
    static final String PRESSURE_SELECTION = "pressure_96";
    static final int NORMAL_ROUTES_PER_BUCKET = 4;
    static final int PRESSURE_ROUTES_PER_DIMENSION = 64;
    static final int MAX_CANDIDATE_ATTEMPTS = 2_048;
    static final int MAX_REFERENCE_NODES = 750_000;
    static final int MAX_SEARCH_WORK_PER_TICK = 8_192;
    static final long MAX_TERRAIN_CLASSIFICATIONS = 8_000_000L;
    static final long MAX_TOTAL_CLASSIFICATIONS = 24_000_000L;
    static final long MAX_ATTEMPT_CLASSIFICATIONS = 2_000_000L;
    private static final int MIN_CROSS_HEIGHT_ROUTES = 1;
    private static final int MIN_DIVERSE_CHUNKS = 2;
    private static final int MIN_PRESSURE_CROSS_HEIGHT_ROUTES = 16;
    private static final int MAX_NORMAL_ROUTES_PER_SOURCE = 2;
    private static final int MAX_PRESSURE_ROUTES_PER_SOURCE = 8;
    private static final int[] SURFACE_LENGTH_BUCKETS = {8, 64, 96, 512};
    private static final int[] UNDERGROUND_LENGTH_BUCKETS = {8, 64, 96};
    private static final int[][] HORIZONTAL_DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };
    private static final int[] HEIGHT_OFFSETS = {0, 1, -1, -2, -3};
    private static final BlockPathTypes[] PATH_TYPES = BlockPathTypes.values();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private TerrainRouteManifest() {
    }

    static Discovery beginDiscovery(ServerLevel level,
                                    String terrain,
                                    long classificationsUsedByBatch) {
        Bounds bounds = Bounds.forTerrain(level, terrain);
        long seed = level.getServer().getWorldData().worldGenOptions().seed()
                ^ ((long) terrain.hashCode() << 32) ^ terrain.hashCode();
        return new Discovery(level, terrain, bounds, classificationsUsedByBatch, seed);
    }

    static final class Discovery {
        private final ServerLevel level;
        private final String terrain;
        private final Bounds bounds;
        private final DiscoveryBudget budget;
        private final CandidatePlan candidates;
        private final List<Route> routes = new ArrayList<>();
        private final Set<String> usedPairs = new HashSet<>();
        private final int[] buckets;
        private final List<List<Route>> routesByBucket = new ArrayList<>();
        private final int[] normalFound;
        private final int[] normalCrossHeight;
        private final List<Set<Long>> normalSources = new ArrayList<>();
        private final List<Set<Long>> normalStartChunks = new ArrayList<>();
        private final List<Set<Long>> normalGoalChunks = new ArrayList<>();
        private final List<Set<String>> normalChunkPairs = new ArrayList<>();
        private final List<List<String>> normalRouteNames = new ArrayList<>();
        private final List<Map<Long, Integer>> normalSourceCounts = new ArrayList<>();
        private final Set<Long> pressureSources = new HashSet<>();
        private final Set<Long> pressureStartChunks = new HashSet<>();
        private final Set<Long> pressureGoalChunks = new HashSet<>();
        private final Set<String> pressureChunkPairs = new HashSet<>();
        private final List<String> pressureRouteNames = new ArrayList<>();
        private final Map<Long, Integer> pressureSourceCounts = new HashMap<>();
        private final Map<Long, Integer> candidateAttemptsByChunk = new HashMap<>();
        private final Map<Integer, Integer> endpointHeightCounts = new TreeMap<>();
        private int pressureFound;
        private int pressureCrossHeight;
        private int attemptedStarts;
        private int invalidCandidates;
        private int referenceAttempts;
        private int budgetExceededAttempts;
        private int nodeLimitHits;
        private long searchNodeTotal;
        private int maximumSearchNodes;
        private int maximumDistance;
        private int searchesWithVerticalTransitions;
        private int noValidHeightColumns;
        private int totalHeightChecks;
        private int minimumHeightChecks = Integer.MAX_VALUE;
        private int maximumHeightChecks;
        private long candidateClassificationTotal;
        private long candidateClassificationNanos;
        private long candidateResolutionNanos;
        private final long candidateOrderBuildNanos;
        private long discoveryStepCount;
        private long discoveryStepNanos;
        private long lastDiscoveryStepNanos;
        private int[] distanceMatches;
        private int[] searchesWithDistanceMatches;
        private SearchState activeSearch;
        private CandidateColumn activeCandidate;
        private String stopReason = "RUNNING";
        private boolean complete;
        private QualificationDiscovery result;

        private Discovery(ServerLevel level,
                          String terrain,
                          Bounds bounds,
                          long classificationsUsedByBatch,
                          long seed) {
            this.level = level;
            this.terrain = terrain;
            this.bounds = bounds;
            this.budget = new DiscoveryBudget(bounds, MAX_TERRAIN_CLASSIFICATIONS,
                    Math.max(0L, MAX_TOTAL_CLASSIFICATIONS - classificationsUsedByBatch));
            this.buckets = lengthBuckets(terrain);
            this.normalFound = new int[buckets.length];
            this.normalCrossHeight = new int[buckets.length];
            this.distanceMatches = new int[buckets.length];
            this.searchesWithDistanceMatches = new int[buckets.length];
            for (int ignored : buckets) {
                routesByBucket.add(new ArrayList<>());
                normalSources.add(new HashSet<>());
                normalStartChunks.add(new HashSet<>());
                normalGoalChunks.add(new HashSet<>());
                normalChunkPairs.add(new HashSet<>());
                normalRouteNames.add(new ArrayList<>());
                normalSourceCounts.add(new HashMap<>());
            }
            long candidateOrderStarted = System.nanoTime();
            this.candidates = candidateColumns(level, terrain, bounds, seed);
            this.candidateOrderBuildNanos = System.nanoTime() - candidateOrderStarted;
        }

        /** Advances complete candidate decisions or bounded search-node expansions. */
        @Nullable
        QualificationDiscovery step(long deadlineNanos) {
            if (result != null) {
                return result;
            }
            budget.beginStep(deadlineNanos);
            int work = 0;
            while (budget.canStartUnit() && work < MAX_SEARCH_WORK_PER_TICK) {
                if (activeSearch != null) {
                    boolean finished;
                    try {
                        finished = activeSearch.expandOne(budget);
                    } catch (BudgetExceeded failure) {
                        budgetExceededAttempts++;
                        settleActiveSearch();
                        work++;
                        if (failure.scope != BudgetScope.ATTEMPT) {
                            stopReason = failure.scope.name() + "_CLASSIFICATION_LIMIT";
                            complete = true;
                            break;
                        }
                        continue;
                    }
                    work++;
                    if (budget.stepStopped()) {
                        break;
                    }
                    if (finished) {
                        SearchResult finishedSearch = activeSearch.finish();
                        acceptSearch(finishedSearch);
                        activeSearch = null;
                    }
                    continue;
                }
                if (activeCandidate == null && shouldFinish()) {
                    complete = true;
                    break;
                }
                if (activeCandidate == null) {
                    activeCandidate = candidates.next();
                    if (activeCandidate == null) {
                        complete = true;
                        break;
                    }
                    attemptedStarts++;
                    candidateAttemptsByChunk.merge(
                            chunkKey(activeCandidate.x, activeCandidate.z), 1, Integer::sum);
                    budget.beginAttempt();
                }
                try {
                    BlockPos start;
                    long candidateStarted = System.nanoTime();
                    long classificationsBefore = budget.classifications;
                    long classificationNanosBefore = budget.classificationNanos;
                    try {
                        start = resolveStart(activeCandidate);
                    } finally {
                        candidateResolutionNanos += System.nanoTime() - candidateStarted;
                        candidateClassificationTotal += budget.classifications - classificationsBefore;
                        candidateClassificationNanos +=
                                budget.classificationNanos - classificationNanosBefore;
                    }
                    activeCandidate = null;
                    if (start == null) {
                        invalidCandidates++;
                        work++;
                        continue;
                    }
                    referenceAttempts++;
                    activeSearch = new SearchState(start, bounds, buckets,
                            furthestNeededDistance(), level, budget);
                    work++;
                } catch (BudgetExceeded failure) {
                    budgetExceededAttempts++;
                    activeCandidate = null;
                    work++;
                    if (failure.scope != BudgetScope.ATTEMPT) {
                        stopReason = failure.scope.name() + "_CLASSIFICATION_LIMIT";
                        complete = true;
                        break;
                    }
                }
            }
            if (!complete && activeCandidate == null && activeSearch == null && shouldFinish()) {
                complete = true;
            }
            if (complete) {
                if ("RUNNING".equals(stopReason)) {
                    stopReason = completionReason();
                }
                result = finishResult();
                return result;
            }
            return null;
        }

        QualificationDiscovery stopAtDeadline() {
            if (result != null) {
                return result;
            }
            if (activeSearch != null) {
                activeSearch.release();
                activeSearch = null;
            }
            activeCandidate = null;
            stopReason = "QUALIFICATION_DEADLINE";
            complete = true;
            result = finishResult();
            return result;
        }

        void abort() {
            if (activeSearch != null) {
                activeSearch.release();
                activeSearch = null;
            }
            activeCandidate = null;
            candidates.release();
            budget.pages.clear();
            stopReason = "ABORTED";
            complete = true;
        }

        boolean hasActiveState() {
            return activeSearch != null || activeCandidate != null || !budget.pages.isEmpty();
        }

        private void settleActiveSearch() {
            SearchResult partial = activeSearch.finish();
            activeSearch = null;
            acceptSearch(partial);
        }

        private int furthestNeededDistance() {
            for (int index = buckets.length - 1; index >= 0; index--) {
                boolean pressureBucket = buckets[index] == 96 && !"cave".equals(terrain);
                int target = pressureBucket
                        ? PRESSURE_ROUTES_PER_DIMENSION : NORMAL_ROUTES_PER_BUCKET;
                if (routesByBucket.get(index).size() < target
                        || normalFound[index] < NORMAL_ROUTES_PER_BUCKET) {
                    return buckets[index];
                }
            }
            throw new IllegalStateException("qualification search started without an unfilled distance group");
        }

        private boolean shouldFinish() {
            return attemptedStarts >= MAX_CANDIDATE_ATTEMPTS
                    || candidates.exhausted()
                    || coverageComplete();
        }

        private String completionReason() {
            if (coverageComplete()) {
                return "COVERAGE_COMPLETE";
            }
            if (attemptedStarts >= MAX_CANDIDATE_ATTEMPTS) {
                return "CANDIDATE_ATTEMPTS_LIMIT";
            }
            return "CANDIDATES_EXHAUSTED";
        }

        void recordStepTiming(long elapsedNanos) {
            discoveryStepCount++;
            lastDiscoveryStepNanos = elapsedNanos;
            discoveryStepNanos += elapsedNanos;
        }

        private BlockPos resolveStart(CandidateColumn column) {
            requireAvailableChunk(level, column.x, column.z);
            if ("surface".equals(terrain)) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        column.x, column.z);
                BlockPos candidate = new BlockPos(column.x, y, column.z);
                boolean valid = validEndpoint(candidate) && level.canSeeSky(candidate);
                recordHeightChecks(1, valid ? candidate : null);
                return valid ? candidate : null;
            }
            int minimum = bounds.minY;
            int maximum = bounds.maxY;
            if ("cave".equals(terrain)) {
                int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        column.x, column.z);
                maximum = Math.min(maximum, surface - 9);
            }
            int heightCount = Math.max(0, maximum - minimum + 1);
            int[] heights = new int[heightCount];
            for (int index = 0; index < heightCount; index++) {
                heights[index] = minimum + index;
            }
            IntArrays.shuffle(heights, new Random(column.orderKey));
            int heightChecks = 0;
            for (int y : heights) {
                heightChecks++;
                BlockPos candidate = new BlockPos(column.x, y, column.z);
                if (validEndpoint(candidate)
                        && (!"cave".equals(terrain) || !level.canSeeSky(candidate))) {
                    recordHeightChecks(heightChecks, candidate);
                    return candidate;
                }
            }
            recordHeightChecks(heightChecks, null);
            return null;
        }

        private void recordHeightChecks(int checks,
                                        @Nullable BlockPos endpoint) {
            totalHeightChecks += checks;
            minimumHeightChecks = Math.min(minimumHeightChecks, checks);
            maximumHeightChecks = Math.max(maximumHeightChecks, checks);
            if (endpoint == null) {
                noValidHeightColumns++;
            } else {
                endpointHeightCounts.merge(endpoint.getY(), 1, Integer::sum);
            }
        }

        private boolean validEndpoint(BlockPos position) {
            return inside(position, bounds) && inside(position.above(), bounds)
                    && walkable(level, position, budget);
        }

        private void acceptSearch(SearchResult search) {
            try {
                searchNodeTotal += search.nodeCount;
                maximumSearchNodes = Math.max(maximumSearchNodes, search.nodeCount);
                maximumDistance = Math.max(maximumDistance, search.maximumDistance);
                if (search.nodeLimitHit) {
                    nodeLimitHits++;
                }
                if (search.hasVerticalTransitions) {
                    searchesWithVerticalTransitions++;
                }
                for (int index = 0; index < buckets.length; index++) {
                    int matches = search.matchCounts[index];
                    distanceMatches[index] += matches;
                    if (matches > 0) {
                        searchesWithDistanceMatches[index]++;
                    }
                }
                SourcePath source = search.recoverSourcePath(needsCrossHeightSource());
                if (source != null) {
                    for (int index = buckets.length - 1; index >= 0; index--) {
                        addDerivedRoutes(index, search, source);
                    }
                }
                LOGGER.info("Qualification reference search complete: terrain={}, start={}, nodes={}, maxDistance={}, sourcePathLength={}, frontierDequeues={}, frontierEnqueues={}, neighborCalls={}, neighborOutside={}, neighborWalkable={}, neighborBlocked={}, neighborOpen={}, neighborOther={}, neighborMillis={}, pageLookups={}, pageLookupMillis={}, expansionCalls={}, expansionMillis={}, attemptClassifications={}, attemptEvaluationCalls={}, attemptClassificationCacheHits={}, attemptClassificationMillis={}",
                        terrain, search.start, search.nodeCount, search.maximumDistance,
                        source == null ? 0 : source.length(),
                        search.frontierDequeueCount, search.frontierEnqueueCount,
                        search.neighborCalls, search.neighborOutside,
                        search.neighborWalkable, search.neighborBlocked,
                        search.neighborOpen, search.neighborOther,
                        millis(search.neighborNanos), search.pageLookupCount,
                        millis(search.pageLookupNanos), search.expansionCalls,
                        millis(search.expansionNanos), budget.attemptClassifications,
                        budget.attemptEvaluationCalls, budget.attemptClassificationCacheHits,
                        millis(budget.attemptClassificationNanos));
            } finally {
                search.release();
            }
        }

        private boolean needsCrossHeightSource() {
            if (!"cave".equals(terrain) && pressureFound < PRESSURE_ROUTES_PER_DIMENSION
                    && pressureCrossHeight < MIN_PRESSURE_CROSS_HEIGHT_ROUTES) {
                return true;
            }
            for (int index = 0; index < buckets.length; index++) {
                if (normalFound[index] < NORMAL_ROUTES_PER_BUCKET
                        && normalCrossHeight[index] < MIN_CROSS_HEIGHT_ROUTES) {
                    return true;
                }
            }
            return false;
        }

        private void addDerivedRoutes(int index, SearchResult search, SourcePath source) {
            boolean pressureBucket = buckets[index] == 96 && !"cave".equals(terrain);
            int target = pressureBucket ? PRESSURE_ROUTES_PER_DIMENSION : NORMAL_ROUTES_PER_BUCKET;
            int sourceLimit = pressureBucket
                    ? MAX_PRESSURE_ROUTES_PER_SOURCE : MAX_NORMAL_ROUTES_PER_SOURCE;
            List<PathSegment> candidates = source.segments(buckets[index]);
            while (routesByBucket.get(index).size() < target
                    && sourceContribution(index, source.key) < sourceLimit) {
                PathSegment segment = selectSegment(index, candidates, pressureBucket);
                if (segment == null) {
                    break;
                }
                Route route = route(terrain, buckets[index], segment.start(), segment.goal(),
                        segment.length(), segment.crossHeightSteps(), source.key, bounds,
                        terrain + "_" + buckets[index] + "_"
                                + String.format("%02d", routesByBucket.get(index).size() + 1));
                usedPairs.add(pairKey(segment.start(), segment.goal()));
                routes.add(route);
                routesByBucket.get(index).add(route);
                if (pressureBucket) {
                    pressureFound++;
                    pressureSourceCounts.merge(source.key, 1, Integer::sum);
                    pressureSources.add(source.key);
                    pressureStartChunks.add(route.startChunk);
                    pressureGoalChunks.add(route.goalChunk);
                    pressureChunkPairs.add(route.startChunk + ":" + route.goalChunk);
                    if (route.crossHeightSteps > 0) {
                        pressureCrossHeight++;
                    }
                    pressureRouteNames.add(route.name);
                }
                refreshNormalSelection(index);
            }
        }

        private int sourceContribution(int index, long sourceKey) {
            int count = 0;
            for (Route route : routesByBucket.get(index)) {
                if (route.sourceKey == sourceKey) {
                    count++;
                }
            }
            return count;
        }

        @Nullable
        private PathSegment selectSegment(int index,
                                          List<PathSegment> candidates,
                                          boolean pressureBucket) {
            int found = pressureBucket ? pressureFound : normalFound[index];
            int required = pressureBucket ? PRESSURE_ROUTES_PER_DIMENSION : NORMAL_ROUTES_PER_BUCKET;
            int crossHeight = pressureBucket ? pressureCrossHeight : normalCrossHeight[index];
            int requiredCrossHeight = pressureBucket
                    ? MIN_PRESSURE_CROSS_HEIGHT_ROUTES : MIN_CROSS_HEIGHT_ROUTES;
            boolean crossHeightRequired = requiredCrossHeight - crossHeight >= required - found;
            PathSegment best = null;
            int bestCrossPenalty = Integer.MAX_VALUE;
            int bestNovelty = Integer.MAX_VALUE;
            for (PathSegment candidate : candidates) {
                String exactPair = pairKey(candidate.start(), candidate.goal());
                String chunkPair = chunkKey(candidate.start()) + ":" + chunkKey(candidate.goal());
                if (usedPairs.contains(exactPair)
                        || (pressureBucket && pressureChunkPairs.contains(chunkPair))
                        || (crossHeightRequired && candidate.crossHeightSteps() == 0)) {
                    continue;
                }
                int crossPenalty = crossHeight < requiredCrossHeight
                        && candidate.crossHeightSteps() == 0 ? 1 : 0;
                Set<Long> startChunks = pressureBucket
                        ? pressureStartChunks : normalStartChunks.get(index);
                Set<Long> goalChunks = pressureBucket
                        ? pressureGoalChunks : normalGoalChunks.get(index);
                int remaining = required - found;
                boolean newStartRequired = MIN_DIVERSE_CHUNKS - startChunks.size() >= remaining;
                boolean newGoalRequired = MIN_DIVERSE_CHUNKS - goalChunks.size() >= remaining;
                if ((newStartRequired && startChunks.contains(chunkKey(candidate.start())))
                        || (newGoalRequired && goalChunks.contains(chunkKey(candidate.goal())))) {
                    continue;
                }
                int novelty = (startChunks.contains(chunkKey(candidate.start())) ? 1 : 0)
                        + (goalChunks.contains(chunkKey(candidate.goal())) ? 1 : 0);
                if (best == null || crossPenalty < bestCrossPenalty
                        || (crossPenalty == bestCrossPenalty && novelty < bestNovelty)
                        || (crossPenalty == bestCrossPenalty && novelty == bestNovelty
                        && candidate.compareTo(best) < 0)) {
                    best = candidate;
                    bestCrossPenalty = crossPenalty;
                    bestNovelty = novelty;
                }
            }
            return best;
        }

        private void refreshNormalSelection(int index) {
            normalFound[index] = 0;
            normalCrossHeight[index] = 0;
            normalSources.get(index).clear();
            normalStartChunks.get(index).clear();
            normalGoalChunks.get(index).clear();
            normalChunkPairs.get(index).clear();
            normalRouteNames.get(index).clear();
            normalSourceCounts.get(index).clear();
            Set<String> selected = new HashSet<>();
            while (normalFound[index] < NORMAL_ROUTES_PER_BUCKET) {
                Route best = null;
                int bestCrossPenalty = Integer.MAX_VALUE;
                int bestNovelty = Integer.MAX_VALUE;
                int remaining = NORMAL_ROUTES_PER_BUCKET - normalFound[index];
                boolean crossRequired = MIN_CROSS_HEIGHT_ROUTES - normalCrossHeight[index] >= remaining;
                boolean newStartRequired = MIN_DIVERSE_CHUNKS
                        - normalStartChunks.get(index).size() >= remaining;
                boolean newGoalRequired = MIN_DIVERSE_CHUNKS
                        - normalGoalChunks.get(index).size() >= remaining;
                for (Route candidate : routesByBucket.get(index)) {
                    if (selected.contains(candidate.name)
                            || normalSourceCounts.get(index).getOrDefault(candidate.sourceKey, 0)
                            >= MAX_NORMAL_ROUTES_PER_SOURCE
                            || (crossRequired && candidate.crossHeightSteps == 0)
                            || (newStartRequired
                            && normalStartChunks.get(index).contains(candidate.startChunk))
                            || (newGoalRequired
                            && normalGoalChunks.get(index).contains(candidate.goalChunk))) {
                        continue;
                    }
                    int crossPenalty = normalCrossHeight[index] < MIN_CROSS_HEIGHT_ROUTES
                            && candidate.crossHeightSteps == 0 ? 1 : 0;
                    int novelty = (normalStartChunks.get(index).contains(candidate.startChunk) ? 1 : 0)
                            + (normalGoalChunks.get(index).contains(candidate.goalChunk) ? 1 : 0);
                    if (best == null || crossPenalty < bestCrossPenalty
                            || (crossPenalty == bestCrossPenalty && novelty < bestNovelty)
                            || (crossPenalty == bestCrossPenalty && novelty == bestNovelty
                            && candidate.name.compareTo(best.name) < 0)) {
                        best = candidate;
                        bestCrossPenalty = crossPenalty;
                        bestNovelty = novelty;
                    }
                }
                if (best == null) {
                    break;
                }
                selected.add(best.name);
                normalFound[index]++;
                normalSourceCounts.get(index).merge(best.sourceKey, 1, Integer::sum);
                normalSources.get(index).add(best.sourceKey);
                normalStartChunks.get(index).add(best.startChunk);
                normalGoalChunks.get(index).add(best.goalChunk);
                normalChunkPairs.get(index).add(best.startChunk + ":" + best.goalChunk);
                if (best.crossHeightSteps > 0) {
                    normalCrossHeight[index]++;
                }
                normalRouteNames.get(index).add(best.name);
            }
        }

        private boolean coverageComplete() {
            if (!"cave".equals(terrain) && !coverageSatisfied(
                    pressureFound, PRESSURE_ROUTES_PER_DIMENSION, pressureCrossHeight,
                    MIN_PRESSURE_CROSS_HEIGHT_ROUTES, pressureSources.size(), 8,
                    maximumContribution(pressureSourceCounts), MAX_PRESSURE_ROUTES_PER_SOURCE,
                    pressureStartChunks.size(), pressureGoalChunks.size(),
                    pressureChunkPairs.size(), PRESSURE_ROUTES_PER_DIMENSION)) {
                return false;
            }
            for (int index = 0; index < buckets.length; index++) {
                if (!coverageSatisfied(
                        normalFound[index], NORMAL_ROUTES_PER_BUCKET, normalCrossHeight[index],
                        MIN_CROSS_HEIGHT_ROUTES, normalSources.get(index).size(), 2,
                        maximumContribution(normalSourceCounts.get(index)), MAX_NORMAL_ROUTES_PER_SOURCE,
                        normalStartChunks.get(index).size(), normalGoalChunks.get(index).size(),
                        normalChunkPairs.get(index).size(), MIN_DIVERSE_CHUNKS)) {
                    return false;
                }
            }
            return true;
        }

        String progressSummary() {
            int activeNodes = activeSearch == null ? 0 : activeSearch.nodeCount;
            int frontierSize = activeSearch == null ? 0 : activeSearch.frontier.size();
            long activeExpansionCalls = activeSearch == null ? 0 : activeSearch.expansionCalls;
            long activeExpansionNanos = activeSearch == null ? 0 : activeSearch.expansionNanos;
            long activeNeighborCalls = activeSearch == null ? 0 : activeSearch.neighborCalls;
            long activeNeighborNanos = activeSearch == null ? 0 : activeSearch.neighborNanos;
            long activePageLookups = activeSearch == null ? 0 : activeSearch.pageLookupCount;
            long activePageLookupNanos = activeSearch == null ? 0 : activeSearch.pageLookupNanos;
            long activeFrontierDequeues = activeSearch == null ? 0 : activeSearch.frontierDequeueCount;
            long activeFrontierEnqueues = activeSearch == null ? 0 : activeSearch.frontierEnqueueCount;
            return "attemptedStarts=" + attemptedStarts
                    + " invalidCandidates=" + invalidCandidates
                    + " referenceAttempts=" + referenceAttempts
                    + " discoverySteps=" + discoveryStepCount
                    + " discoveryStepMillis=" + millis(discoveryStepNanos)
                    + " lastDiscoveryStepMillis=" + millis(lastDiscoveryStepNanos)
                    + " activeSearch=" + (activeSearch != null)
                    + " activeSearchNodes=" + activeNodes
                    + " frontierSize=" + frontierSize
                    + " expansionCalls=" + activeExpansionCalls
                    + " expansionMillis=" + millis(activeExpansionNanos)
                    + " frontierDequeues=" + activeFrontierDequeues
                    + " frontierEnqueues=" + activeFrontierEnqueues
                    + " neighborCalls=" + activeNeighborCalls
                    + " neighborMillis=" + millis(activeNeighborNanos)
                    + " pageLookups=" + activePageLookups
                    + " pageLookupMillis=" + millis(activePageLookupNanos)
                    + " candidatesMaterialized=" + candidates.materializedPositionCount()
                    + " classifications=" + budget.classifications
                    + " classificationCalls=" + budget.evaluationCalls
                    + " classificationCacheHits=" + budget.classificationCacheHits
                    + " classificationMillis=" + millis(budget.classificationNanos)
                    + " candidateMillis=" + millis(candidateResolutionNanos)
                    + " candidateClassifications=" + candidateClassificationTotal
                    + " candidateClassificationMillis=" + millis(candidateClassificationNanos)
                    + " budgetExceededAttempts=" + budgetExceededAttempts
                    + " completedSearchNodes=" + searchNodeTotal
                    + " searchesWithVerticalTransitions=" + searchesWithVerticalTransitions
                    + " distanceMatches=" + java.util.Arrays.toString(distanceMatches)
                    + " searchesWithDistanceMatches="
                    + java.util.Arrays.toString(searchesWithDistanceMatches);
        }

        private static boolean coverageSatisfied(int found,
                                                 int requiredFound,
                                                 int crossHeightFound,
                                                 int requiredCrossHeight,
                                                 int sourceCount,
                                                 int requiredSourceCount,
                                                 int maximumSourceContribution,
                                                 int allowedSourceContribution,
                                                 int startChunkCount,
                                                 int goalChunkCount,
                                                 int chunkPairCount,
                                                 int requiredChunkPairCount) {
            return found >= requiredFound
                    && crossHeightFound >= requiredCrossHeight
                    && sourceCount >= requiredSourceCount
                    && maximumSourceContribution <= allowedSourceContribution
                    && startChunkCount >= MIN_DIVERSE_CHUNKS
                    && goalChunkCount >= MIN_DIVERSE_CHUNKS
                    && chunkPairCount >= requiredChunkPairCount;
        }

        private QualificationDiscovery finishResult() {
            List<SelectionCoverage> coverage = new ArrayList<>();
            for (int index = 0; index < buckets.length; index++) {
                coverage.add(selectionCoverage(NORMAL_SELECTION, terrain, buckets[index],
                        NORMAL_ROUTES_PER_BUCKET, normalFound[index], normalCrossHeight[index],
                        normalSources.get(index).size(), maximumContribution(normalSourceCounts.get(index)),
                        normalStartChunks.get(index).size(),
                        normalGoalChunks.get(index).size(), normalChunkPairs.get(index).size(),
                        normalRouteNames.get(index)));
            }
            if (!"cave".equals(terrain)) {
                coverage.add(selectionCoverage(PRESSURE_SELECTION,
                        "surface".equals(terrain) ? "overworld" : terrain, 96,
                        PRESSURE_ROUTES_PER_DIMENSION, pressureFound, pressureCrossHeight,
                        pressureSources.size(), maximumContribution(pressureSourceCounts),
                        pressureStartChunks.size(), pressureGoalChunks.size(),
                        pressureChunkPairs.size(), pressureRouteNames));
            }
            LOGGER.info("Terrain qualification discovery terrain={} candidatesMaterialized={} "
                            + "plannedChunks={} materializedChunks={} attempts={} invalid={} references={} "
                            + "chunkAttemptsMin={} chunkAttemptsMax={} heightChecksTotal={} "
                            + "heightChecksMin={} heightChecksMax={} noValidHeightColumns={} "
                            + "endpointHeights={} candidateOrderMillis={} candidateMillis={} "
                            + "candidateClassifications={} candidateClassificationMillis={} "
                            + "classifications={} classificationMillis={} nodeLimitHits={} maxNodes={} "
                            + "maxDistance={} completedSearchNodes={} searchesWithVerticalTransitions={} "
                            + "distanceMatches={} searchesWithDistanceMatches={} budgetExceededAttempts={} "
                            + "stopReason={} normalFound={} pressureFound={} coverage={}",
                    terrain, candidates.materializedPositionCount(), candidates.chunkCount(),
                    candidates.materializedChunkCount(), attemptedStarts, invalidCandidates, referenceAttempts,
                    minimumChunkAttempts(), maximumChunkAttempts(), totalHeightChecks,
                    minimumHeightChecks == Integer.MAX_VALUE ? 0 : minimumHeightChecks,
                    maximumHeightChecks, noValidHeightColumns, endpointHeightCounts,
                    millis(candidateOrderBuildNanos), millis(candidateResolutionNanos),
                    candidateClassificationTotal, millis(candidateClassificationNanos),
                    budget.classifications, millis(budget.classificationNanos), nodeLimitHits,
                    maximumSearchNodes, maximumDistance, searchNodeTotal,
                    searchesWithVerticalTransitions, java.util.Arrays.toString(distanceMatches),
                    java.util.Arrays.toString(searchesWithDistanceMatches), budgetExceededAttempts, stopReason,
                    java.util.Arrays.toString(normalFound), pressureFound,
                    coverage.stream().map(value -> value.requestedDistance + ":" + value.foundCount
                            + "/" + value.requestedCount + ":" + value.status).toList());
            budget.pages.clear();
            return new QualificationDiscovery(routes, coverage, budget.classifications);
        }

        private int minimumChunkAttempts() {
            int minimum = Integer.MAX_VALUE;
            for (int index = 0; index < candidates.chunkCount(); index++) {
                ChunkPos chunk = candidates.chunk(index);
                minimum = Math.min(minimum,
                        candidateAttemptsByChunk.getOrDefault(chunk.toLong(), 0));
            }
            return minimum == Integer.MAX_VALUE ? 0 : minimum;
        }

        private int maximumChunkAttempts() {
            int maximum = 0;
            for (int attempts : candidateAttemptsByChunk.values()) {
                maximum = Math.max(maximum, attempts);
            }
            return maximum;
        }

    }

    static QualificationDiscovery deadlineCoverage(String terrain) {
        List<SelectionCoverage> coverage = new ArrayList<>();
        for (int bucket : lengthBuckets(terrain)) {
            coverage.add(selectionCoverage(NORMAL_SELECTION, terrain, bucket,
                    NORMAL_ROUTES_PER_BUCKET, 0, 0, 0, 0,
                    0, 0, 0, List.of()));
        }
        if (!"cave".equals(terrain)) {
            coverage.add(selectionCoverage(PRESSURE_SELECTION,
                    "surface".equals(terrain) ? "overworld" : terrain, 96,
                    PRESSURE_ROUTES_PER_DIMENSION, 0, 0, 0, 0,
                    0, 0, 0, List.of()));
        }
        return new QualificationDiscovery(List.of(), coverage, 0L);
    }

    private static int[] lengthBuckets(String terrain) {
        return "surface".equals(terrain) ? SURFACE_LENGTH_BUCKETS : UNDERGROUND_LENGTH_BUCKETS;
    }

    private static CandidatePlan candidateColumns(ServerLevel level,
                                                  String terrain,
                                                  Bounds bounds,
                                                  long seed) {
        List<ChunkPos> chunks = plannedChunks(level, terrain);
        for (ChunkPos chunk : chunks) {
            if (level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null) {
                throw new IllegalStateException("planned qualification chunk is unavailable: " + chunk);
            }
        }
        int[] order = new int[chunks.size()];
        for (int index = 0; index < order.length; index++) {
            order[index] = index;
        }
        IntArrays.shuffle(order, new Random(seed));
        return new CandidatePlan(List.copyOf(chunks), order, bounds, seed);
    }

    static List<ChunkPos> plannedChunks(ServerLevel level, String terrain) {
        boolean nether = "nether".equals(terrain)
                || level.dimension().location().toString().equals("minecraft:the_nether");
        int minimumX = nether ? -2 : 13;
        int maximumX = nether ? 38 : 56;
        int minimumZ = nether ? -6 : -18;
        int maximumZ = nether ? 6 : -6;
        List<ChunkPos> chunks = new ArrayList<>((maximumX - minimumX + 1)
                * (maximumZ - minimumZ + 1));
        for (int chunkX = minimumX; chunkX <= maximumX; chunkX++) {
            for (int chunkZ = minimumZ; chunkZ <= maximumZ; chunkZ++) {
                chunks.add(new ChunkPos(chunkX, chunkZ));
            }
        }
        return List.copyOf(chunks);
    }

    private static long mix(long seed, int x, int z) {
        long value = seed ^ (long) x * 0x9E3779B97F4A7C15L ^ (long) z * 0xC2B2AE3D27D4EB4FL;
        value ^= value >>> 33;
        value *= 0xFF51AFD7ED558CCDL;
        value ^= value >>> 33;
        return value;
    }

    private static Route route(String terrain,
                               int requestedDistance,
                               BlockPos start,
                               BlockPos goal,
                               int pathLength,
                               int crossHeightSteps,
                               long sourceKey,
                               Bounds bounds,
                               String name) {
        Route route = new Route();
        route.name = name;
        route.terrain = terrain;
        route.dimension = dimensionForTerrain(terrain);
        route.requestedDistance = requestedDistance;
        route.start = coordinate(start);
        route.goal = coordinate(goal);
        route.status = "REACHABLE";
        route.referenceMovement = "single-cell classification; four horizontal directions; ordered same-height, step-up and drop transitions";
        route.referencePathLength = pathLength;
        route.crossHeightSteps = crossHeightSteps;
        route.sourceKey = sourceKey;
        route.startChunk = chunkKey(start);
        route.goalChunk = chunkKey(goal);
        route.regionBounds = bounds.toRegionBounds();
        return route;
    }

    private static SelectionCoverage selectionCoverage(String selection,
                                                        String terrain,
                                                        int requestedDistance,
                                                        int requestedCount,
                                                        int foundCount,
                                                        int crossHeightFound,
                                                        int sourceCount,
                                                        int maximumSourceContribution,
                                                        int startChunkCount,
                                                        int goalChunkCount,
                                                        int chunkPairCount,
                                                        List<String> routeNames) {
        SelectionCoverage coverage = new SelectionCoverage();
        coverage.selection = selection;
        coverage.terrain = terrain;
        coverage.requestedDistance = requestedDistance;
        coverage.requestedCount = requestedCount;
        coverage.foundCount = foundCount;
        coverage.requiredCrossHeight = selection.equals(PRESSURE_SELECTION)
                ? MIN_PRESSURE_CROSS_HEIGHT_ROUTES : MIN_CROSS_HEIGHT_ROUTES;
        coverage.crossHeightFound = crossHeightFound;
        coverage.sourceCount = sourceCount;
        coverage.maximumSourceContribution = maximumSourceContribution;
        coverage.startChunkCount = startChunkCount;
        coverage.goalChunkCount = goalChunkCount;
        coverage.requiredSourceCount = selection.equals(PRESSURE_SELECTION) ? 8 : 2;
        coverage.allowedSourceContribution = selection.equals(PRESSURE_SELECTION)
                ? MAX_PRESSURE_ROUTES_PER_SOURCE : MAX_NORMAL_ROUTES_PER_SOURCE;
        coverage.requiredChunkPairCount = selection.equals(PRESSURE_SELECTION)
                ? requestedCount : MIN_DIVERSE_CHUNKS;
        coverage.chunkPairCount = chunkPairCount;
        coverage.routeNames = List.copyOf(routeNames);
        coverage.status = foundCount >= requestedCount
                && crossHeightFound >= coverage.requiredCrossHeight
                && sourceCount >= coverage.requiredSourceCount
                && maximumSourceContribution <= coverage.allowedSourceContribution
                && startChunkCount >= MIN_DIVERSE_CHUNKS
                && goalChunkCount >= MIN_DIVERSE_CHUNKS
                && chunkPairCount >= coverage.requiredChunkPairCount
                ? "COVERED" : "COVERAGE_GAP";
        return coverage;
    }

    static void write(Path path, Manifest manifest) {
        validateContents(manifest);
        Path absolute = path.toAbsolutePath();
        Path temporary = absolute.resolveSibling(absolute.getFileName() + ".tmp");
        try {
            if (absolute.getParent() != null) {
                Files.createDirectories(absolute.getParent());
            }
            Files.writeString(temporary, GSON.toJson(manifest), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("atomic move is required for terrain qualification manifest", unsupported);
            }
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw new IllegalStateException("could not write terrain qualification directory", failure);
        }
    }

    static Manifest load(Path path) {
        try (Reader reader = Files.newBufferedReader(path.toAbsolutePath(), StandardCharsets.UTF_8)) {
            Manifest manifest = GSON.fromJson(reader, Manifest.class);
            if (manifest == null || manifest.routes == null || manifest.selectionCoverage == null) {
                throw new IllegalStateException("terrain qualification directory is incomplete");
            }
            return manifest;
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("could not load terrain qualification directory "
                    + path.toAbsolutePath(), failure);
        }
    }

    static Manifest loadGenerated(Path path) {
        Manifest manifest = load(path);
        validateContents(manifest);
        return manifest;
    }

    private static void validateContents(Manifest manifest) {
        Map<String, Route> routesByName = new HashMap<>();
        Set<String> endpointPairs = new HashSet<>();
        for (int index = 0; index < manifest.routes.size(); index++) {
            Route route = manifest.routes.get(index);
            String issue = routeIssue(route);
            if (issue != null || routesByName.putIfAbsent(route.name, route) != null
                    || !endpointPairs.add(pairKey(route.start.toBlockPos(), route.goal.toBlockPos()))) {
                throw new IllegalStateException("terrain qualification route is invalid at index "
                        + index + ": " + (issue == null ? "duplicate identity" : issue));
            }
        }
        Set<String> coverageIdentities = new HashSet<>();
        for (int index = 0; index < manifest.selectionCoverage.size(); index++) {
            SelectionCoverage coverage = manifest.selectionCoverage.get(index);
            String issue = coverageIssue(coverage);
            String identity = issue == null ? coverageIdentity(coverage) : "";
            if (issue != null || !coverageIdentities.add(identity)) {
                throw new IllegalStateException("terrain qualification coverage is invalid at index "
                        + index + ": " + (issue == null ? "duplicate identity" : issue));
            }
            Set<String> names = new HashSet<>();
            List<Route> selectedRoutes = new ArrayList<>();
            for (String name : coverage.routeNames) {
                Route route = routesByName.get(name);
                if (route == null || !names.add(name)
                        || !coverageMatchesRoute(coverage, route)
                        || coverage.requestedDistance != route.requestedDistance) {
                    throw new IllegalStateException(
                            "terrain qualification coverage references an invalid route at index " + index);
                }
                selectedRoutes.add(route);
            }
            SelectionCoverage calculated = recalculateCoverage(coverage, selectedRoutes);
            if (!sameCoverage(coverage, calculated)) {
                throw new IllegalStateException(
                        "terrain qualification coverage statistics disagree with routes at index " + index);
            }
        }
        if (!coverageIdentities.equals(expectedCoverageIdentities())) {
            throw new IllegalStateException("terrain qualification coverage identities are incomplete");
        }
    }

    @Nullable
    static String routeIssue(@Nullable Route route) {
        if (route == null) {
            return "null route";
        }
        if (route.name == null || route.name.isBlank()) {
            return "missing name";
        }
        if (!"REACHABLE".equals(route.status)) {
            return "status=" + route.status;
        }
        if (!validRouteTerrain(route.terrain)
                || !dimensionForTerrain(route.terrain).equals(route.dimension)
                || route.start == null || route.goal == null || !validBounds(route.regionBounds)) {
            return "missing or inconsistent terrain, dimension, endpoint or bounds";
        }
        BlockPos start = route.start.toBlockPos();
        BlockPos goal = route.goal.toBlockPos();
        int pathLength = (int) Math.rint(route.referencePathLength);
        if (start.equals(goal) || route.requestedDistance <= 0
                || route.referencePathLength != pathLength
                || !withinTolerance(pathLength, route.requestedDistance)
                || route.crossHeightSteps < 0 || route.crossHeightSteps > pathLength
                || !validRouteDistance(route.terrain, route.requestedDistance)
                || route.startChunk != chunkKey(start) || route.goalChunk != chunkKey(goal)
                || !inside(start, bounds(route.regionBounds)) || !inside(goal, bounds(route.regionBounds))) {
            return "route geometry or qualification fields are inconsistent";
        }
        if (route.referenceMovement == null || route.referenceMovement.isBlank()) {
            return "missing reference explanation";
        }
        return null;
    }

    @Nullable
    static String coverageIssue(@Nullable SelectionCoverage coverage) {
        if (coverage == null || !validSelection(coverage.selection)
                || !validTerrain(coverage.selection, coverage.terrain)
                || !validCoverageDistance(coverage.selection, coverage.terrain,
                coverage.requestedDistance) || coverage.routeNames == null) {
            return "missing or unknown selection identity";
        }
        int requested = PRESSURE_SELECTION.equals(coverage.selection)
                ? PRESSURE_ROUTES_PER_DIMENSION : NORMAL_ROUTES_PER_BUCKET;
        if (coverage.requestedCount != requested || coverage.foundCount < 0
                || coverage.foundCount > requested || coverage.routeNames.size() != coverage.foundCount
                || !("COVERED".equals(coverage.status) || "COVERAGE_GAP".equals(coverage.status))) {
            return "selection count or status is invalid";
        }
        return null;
    }

    static SelectionCoverage recalculateCoverage(SelectionCoverage source, List<Route> routes) {
        Set<Long> sources = new HashSet<>();
        Set<Long> startChunks = new HashSet<>();
        Set<Long> goalChunks = new HashSet<>();
        Set<String> chunkPairs = new HashSet<>();
        Map<Long, Integer> sourceCounts = new HashMap<>();
        int crossHeight = 0;
        List<String> names = new ArrayList<>(routes.size());
        for (Route route : routes) {
            names.add(route.name);
            sources.add(route.sourceKey);
            sourceCounts.merge(route.sourceKey, 1, Integer::sum);
            startChunks.add(route.startChunk);
            goalChunks.add(route.goalChunk);
            chunkPairs.add(route.startChunk + ":" + route.goalChunk);
            if (route.crossHeightSteps > 0) {
                crossHeight++;
            }
        }
        return selectionCoverage(source.selection, source.terrain, source.requestedDistance,
                PRESSURE_SELECTION.equals(source.selection)
                        ? PRESSURE_ROUTES_PER_DIMENSION : NORMAL_ROUTES_PER_BUCKET,
                routes.size(), crossHeight, sources.size(), maximumContribution(sourceCounts),
                startChunks.size(), goalChunks.size(), chunkPairs.size(), names);
    }

    static boolean sameCoverage(SelectionCoverage first, SelectionCoverage second) {
        return first.requestedCount == second.requestedCount
                && first.foundCount == second.foundCount
                && first.requiredCrossHeight == second.requiredCrossHeight
                && first.crossHeightFound == second.crossHeightFound
                && first.sourceCount == second.sourceCount
                && first.maximumSourceContribution == second.maximumSourceContribution
                && first.startChunkCount == second.startChunkCount
                && first.goalChunkCount == second.goalChunkCount
                && first.chunkPairCount == second.chunkPairCount
                && first.requiredSourceCount == second.requiredSourceCount
                && first.allowedSourceContribution == second.allowedSourceContribution
                && first.requiredChunkPairCount == second.requiredChunkPairCount
                && first.status.equals(second.status)
                && first.routeNames.equals(second.routeNames);
    }

    private static Set<String> expectedCoverageIdentities() {
        Set<String> identities = new HashSet<>();
        for (int bucket : SURFACE_LENGTH_BUCKETS) {
            identities.add(NORMAL_SELECTION + ":surface:" + bucket);
        }
        for (int bucket : UNDERGROUND_LENGTH_BUCKETS) {
            identities.add(NORMAL_SELECTION + ":cave:" + bucket);
            identities.add(NORMAL_SELECTION + ":nether:" + bucket);
        }
        identities.add(PRESSURE_SELECTION + ":overworld:96");
        identities.add(PRESSURE_SELECTION + ":nether:96");
        return identities;
    }

    private static String coverageIdentity(SelectionCoverage coverage) {
        return coverage.selection + ":" + coverage.terrain + ":" + coverage.requestedDistance;
    }

    private static boolean validSelection(String selection) {
        return NORMAL_SELECTION.equals(selection) || PRESSURE_SELECTION.equals(selection);
    }

    private static boolean validTerrain(String selection, String terrain) {
        return PRESSURE_SELECTION.equals(selection)
                ? "overworld".equals(terrain) || "nether".equals(terrain)
                : "surface".equals(terrain) || "cave".equals(terrain) || "nether".equals(terrain);
    }

    private static boolean validRouteTerrain(String terrain) {
        return "surface".equals(terrain) || "cave".equals(terrain) || "nether".equals(terrain);
    }

    private static String dimensionForTerrain(String terrain) {
        return "nether".equals(terrain) ? "minecraft:the_nether" : "minecraft:overworld";
    }

    private static boolean validRouteDistance(String terrain, int requestedDistance) {
        int[] distances = "surface".equals(terrain)
                ? SURFACE_LENGTH_BUCKETS : UNDERGROUND_LENGTH_BUCKETS;
        for (int distance : distances) {
            if (distance == requestedDistance) {
                return true;
            }
        }
        return false;
    }

    private static boolean validCoverageDistance(String selection,
                                                 String terrain,
                                                 int requestedDistance) {
        if (PRESSURE_SELECTION.equals(selection)) {
            return requestedDistance == 96
                    && ("overworld".equals(terrain) || "nether".equals(terrain));
        }
        return validRouteDistance(terrain, requestedDistance);
    }

    static boolean coverageMatchesRoute(SelectionCoverage coverage, Route route) {
        String routeTerrain = PRESSURE_SELECTION.equals(coverage.selection)
                && "overworld".equals(coverage.terrain) ? "surface" : coverage.terrain;
        return routeTerrain.equals(route.terrain);
    }

    private static boolean validBounds(RegionBounds bounds) {
        return bounds != null && bounds.minX <= bounds.maxX
                && bounds.minY <= bounds.maxY && bounds.minZ <= bounds.maxZ;
    }

    private static Bounds bounds(RegionBounds bounds) {
        return new Bounds(bounds.minX, bounds.maxX, bounds.minY, bounds.maxY,
                bounds.minZ, bounds.maxZ);
    }

    private static int maximumContribution(Map<Long, Integer> counts) {
        int maximum = 0;
        for (int count : counts.values()) {
            maximum = Math.max(maximum, count);
        }
        return maximum;
    }

    private static boolean inside(BlockPos position, Bounds bounds) {
        return position.getX() >= bounds.minX && position.getX() <= bounds.maxX
                && position.getY() >= bounds.minY && position.getY() <= bounds.maxY
                && position.getZ() >= bounds.minZ && position.getZ() <= bounds.maxZ;
    }

    private static void requireAvailableChunk(ServerLevel level, int blockX, int blockZ) {
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
            throw new IllegalStateException("planned qualification chunk became unavailable: "
                    + new ChunkPos(chunkX, chunkZ));
        }
    }

    private static void requireAvailableClassificationChunks(ServerLevel level, BlockPos position) {
        int minimumChunkX = (position.getX() - 1) >> 4;
        int maximumChunkX = (position.getX() + 1) >> 4;
        int minimumChunkZ = (position.getZ() - 1) >> 4;
        int maximumChunkZ = (position.getZ() + 1) >> 4;
        for (int chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
            for (int chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++) {
                if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
                    throw new IllegalStateException(
                            "planned qualification classification neighbor became unavailable: "
                                    + new ChunkPos(chunkX, chunkZ));
                }
            }
        }
    }

    record DiagnosticReference(List<BlockPos> path, Map<String, Object> result) {}

    static DiagnosticReference diagnosticReference(ServerLevel level, Route route) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        List<BlockPos> path = new ArrayList<>();
        long began = System.nanoTime();
        try {
            RegionBounds r = route.regionBounds;
            Bounds bounds = new Bounds(r.minX, r.maxX, r.minY, r.maxY, r.minZ, r.maxZ);
            DiscoveryBudget budget = new DiscoveryBudget(bounds, Long.MAX_VALUE, Long.MAX_VALUE);
            budget.beginStep(Long.MAX_VALUE);
            SearchState search = new SearchState(route.start.toBlockPos(), bounds,
                    new int[]{route.requestedDistance}, route.requestedDistance, level, budget);
            BlockPos goal = route.goal.toBlockPos();
            boolean exhausted = false;
            while (search.state(goal) < SearchPage.PARENT_BASE && !exhausted) {
                if (System.nanoTime() - began > 30_000_000_000L) {
                    result.put("referenceStatus", "TIME_LIMIT");
                    break;
                }
                exhausted = search.expandOne(budget);
            }
            if (search.state(goal) >= SearchPage.PARENT_BASE) {
                BlockPos current = goal;
                while (!current.equals(search.start)) {
                    path.add(current);
                    int code = search.state(current) - SearchPage.PARENT_BASE;
                    if (code < 0 || code >= HORIZONTAL_DIRECTIONS.length * HEIGHT_OFFSETS.length) {
                        throw new IllegalStateException("incomplete diagnostic reference parent");
                    }
                    int[] direction = HORIZONTAL_DIRECTIONS[code / HEIGHT_OFFSETS.length];
                    current = current.offset(-direction[0], -HEIGHT_OFFSETS[code % HEIGHT_OFFSETS.length],
                            -direction[1]);
                    if (path.size() > MAX_REFERENCE_NODES) throw new IllegalStateException("reference cycle");
                }
                path.add(search.start);
                java.util.Collections.reverse(path);
                result.put("referenceStatus", "REACHABLE");
            } else result.putIfAbsent("referenceStatus", search.nodeLimitHit ? "NODE_LIMIT" : "NOT_REACHED");
            result.put("referenceNodes", search.nodeCount);
            result.put("referenceLength", Math.max(0, path.size() - 1));
        } catch (RuntimeException failure) {
            result.put("referenceStatus", "OBSERVATION_FAILED");
            result.put("error", failure.toString());
        }
        result.put("referenceNanos", System.nanoTime() - began);
        return new DiagnosticReference(List.copyOf(path), Map.copyOf(result));
    }

    private static boolean walkable(ServerLevel level, BlockPos position, DiscoveryBudget budget) {
        return budget.classify(level, position) && budget.open(level, position.above());
    }

    @Nullable
    private static BlockPos findNeighbor(ServerLevel level,
                                         BlockPos current,
                                         int[] direction,
                                         SearchState search,
                                         DiscoveryBudget budget) {
        long started = System.nanoTime();
        int branch = SearchState.NEIGHBOR_OTHER;
        try {
            int nextX = current.getX() + direction[0];
            int nextZ = current.getZ() + direction[1];
            BlockPos sameHeight = new BlockPos(nextX, current.getY(), nextZ);
            if (!inside(sameHeight, search.bounds)) {
                branch = SearchState.NEIGHBOR_OUTSIDE;
                return null;
            }
            BlockPathTypes sameType = budget.type(level, sameHeight);
            if (sameType == BlockPathTypes.WALKABLE) {
                branch = SearchState.NEIGHBOR_WALKABLE;
                if (inside(sameHeight.above(), search.bounds)
                        && budget.open(level, sameHeight.above())) {
                    return sameHeight;
                }
                search.markRejected(sameHeight);
                return null;
            }
            if (sameType == BlockPathTypes.BLOCKED) {
                branch = SearchState.NEIGHBOR_BLOCKED;
                search.markRejected(sameHeight);
                BlockPos stepUp = sameHeight.above();
                if (!inside(stepUp, search.bounds)
                        || !inside(stepUp.above(), search.bounds)
                        || !inside(current.above(2), search.bounds)) {
                    return null;
                }
                BlockPathTypes stepType = budget.type(level, stepUp);
                if (stepType == BlockPathTypes.WALKABLE
                        && budget.open(level, stepUp.above())
                        && budget.open(level, current.above(2))) {
                    return stepUp;
                }
                if (stepType != BlockPathTypes.WALKABLE
                        || !budget.open(level, stepUp.above())) {
                    search.markRejected(stepUp);
                }
                return null;
            }
            if (sameType == BlockPathTypes.OPEN) {
                branch = SearchState.NEIGHBOR_OPEN;
                search.markRejected(sameHeight);
                if (!inside(sameHeight.above(), search.bounds)
                        || !budget.open(level, sameHeight.above())) {
                    return null;
                }
                for (int depth = 1; depth <= 3; depth++) {
                    BlockPos foot = new BlockPos(nextX, current.getY() - depth, nextZ);
                    if (!inside(foot, search.bounds) || !inside(foot.above(), search.bounds)) {
                        return null;
                    }
                    BlockPathTypes footType = budget.type(level, foot);
                    if (footType == BlockPathTypes.OPEN) {
                        search.markRejected(foot);
                        continue;
                    }
                    if (footType == BlockPathTypes.WALKABLE) {
                        if (budget.open(level, foot.above())) {
                            return foot;
                        }
                        search.markRejected(foot);
                    } else {
                        search.markRejected(foot);
                    }
                    return null;
                }
            } else {
                search.markRejected(sameHeight);
            }
            return null;
        } finally {
            search.recordNeighbor(branch, System.nanoTime() - started);
        }
    }

    private static String pairKey(BlockPos start, BlockPos goal) {
        return start.asLong() + ":" + goal.asLong();
    }

    private static long chunkKey(BlockPos position) {
        return chunkKey(position.getX(), position.getZ());
    }

    private static long chunkKey(int x, int z) {
        return ChunkPos.asLong(x >> 4, z >> 4);
    }

    private static Coordinate coordinate(BlockPos position) {
        return new Coordinate(position.getX(), position.getY(), position.getZ());
    }

    private static long sectionKey(BlockPos position) {
        return SectionPos.asLong(position.getX() >> 4,
                position.getY() >> 4, position.getZ() >> 4);
    }

    private static int localIndex(BlockPos position) {
        return (position.getX() & 15)
                | ((position.getZ() & 15) << 4)
                | ((position.getY() & 15) << 8);
    }

    private static boolean withinTolerance(int pathLength, int requestedDistance) {
        return Math.abs(pathLength - requestedDistance)
                <= Math.max(4.0D, requestedDistance * 0.10D);
    }

    private static int upperTolerance(int requestedDistance) {
        return requestedDistance + (int) Math.floor(Math.max(4.0D, requestedDistance * 0.10D));
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0D;
    }

    static final class Manifest {
        List<SelectionCoverage> selectionCoverage = new ArrayList<>();
        List<Route> routes = new ArrayList<>();
    }

    static final class QualificationDiscovery {
        final List<Route> routes;
        final List<SelectionCoverage> selectionCoverage;
        final long classifications;

        QualificationDiscovery(List<Route> routes,
                               List<SelectionCoverage> selectionCoverage,
                               long classifications) {
            this.routes = routes;
            this.selectionCoverage = selectionCoverage;
            this.classifications = classifications;
        }
    }

    static final class SelectionCoverage {
        String selection;
        String terrain;
        int requestedDistance;
        int requestedCount;
        int foundCount;
        int requiredCrossHeight;
        int crossHeightFound;
        int sourceCount;
        int maximumSourceContribution;
        int startChunkCount;
        int goalChunkCount;
        int chunkPairCount;
        List<String> routeNames = new ArrayList<>();
        int requiredSourceCount;
        int allowedSourceContribution;
        int requiredChunkPairCount;
        String status;
    }

    static final class Route {
        String name;
        String terrain;
        String dimension;
        int requestedDistance;
        Coordinate start;
        Coordinate goal;
        String status;
        String referenceMovement;
        double referencePathLength;
        int crossHeightSteps;
        long sourceKey;
        long startChunk;
        long goalChunk;
        RegionBounds regionBounds;
    }

    static final class RegionBounds {
        int minX;
        int maxX;
        int minY;
        int maxY;
        int minZ;
        int maxZ;

        RegionBounds() {
        }

        RegionBounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.minZ = minZ;
            this.maxZ = maxZ;
        }
    }

    static final class Coordinate {
        int x;
        int y;
        int z;

        Coordinate() {
        }

        Coordinate(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        BlockPos toBlockPos() {
            return new BlockPos(x, y, z);
        }
    }

    private static final class CandidateColumn {
        private final int x;
        private final int z;
        private final long orderKey;

        private CandidateColumn(int x, int z, long orderKey) {
            this.x = x;
            this.z = z;
            this.orderKey = orderKey;
        }
    }

    private static final class CandidatePlan {
        private final List<ChunkPos> chunks;
        private final int[] chunkOrder;
        private final Bounds bounds;
        private final long seed;
        private final int[][] positions;
        private int round;
        private int chunkCursor;
        private int materializedChunks;
        private int materializedPositionCount;
        private boolean exhausted;

        private CandidatePlan(List<ChunkPos> chunks,
                              int[] chunkOrder,
                              Bounds bounds,
                              long seed) {
            this.chunks = chunks;
            this.chunkOrder = chunkOrder;
            this.bounds = bounds;
            this.seed = seed;
            this.positions = new int[chunks.size()][];
        }

        @Nullable
        private CandidateColumn next() {
            if (exhausted) {
                exhausted = true;
                return null;
            }
            while (true) {
                while (chunkCursor < chunkOrder.length) {
                    int chunkIndex = chunkOrder[chunkCursor++];
                    int[] localPositions = positions[chunkIndex];
                    if (localPositions == null) {
                        localPositions = buildPositions(chunks.get(chunkIndex));
                        positions[chunkIndex] = localPositions;
                        materializedChunks++;
                        materializedPositionCount += localPositions.length;
                    }
                    if (round >= localPositions.length) {
                        continue;
                    }
                    int packed = localPositions[round];
                    ChunkPos chunk = chunks.get(chunkIndex);
                    int x = chunk.getMinBlockX() + ((packed >>> 4) & 15);
                    int z = chunk.getMinBlockZ() + (packed & 15);
                    return new CandidateColumn(x, z, mix(seed, x, z));
                }
                {
                    round++;
                    chunkCursor = 0;
                    boolean any = false;
                    for (int index = 0; index < positions.length; index++) {
                        int[] local = positions[index];
                        if (local == null || round < local.length) {
                            any = true;
                            break;
                        }
                    }
                    if (!any) {
                        exhausted = true;
                        return null;
                    }
                }
            }
        }

        private int[] buildPositions(ChunkPos chunk) {
            int minX = Math.max(bounds.minX, chunk.getMinBlockX());
            int maxX = Math.min(bounds.maxX, chunk.getMaxBlockX());
            int minZ = Math.max(bounds.minZ, chunk.getMinBlockZ());
            int maxZ = Math.min(bounds.maxZ, chunk.getMaxBlockZ());
            int count = Math.max(0, maxX - minX + 1) * Math.max(0, maxZ - minZ + 1);
            int[] local = new int[count];
            int offset = 0;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    local[offset++] = ((x - chunk.getMinBlockX()) << 4)
                            | (z - chunk.getMinBlockZ());
                }
            }
            IntArrays.shuffle(local, new Random(seed ^ chunk.toLong()));
            return local;
        }

        private boolean exhausted() {
            return exhausted;
        }

        private int chunkCount() {
            return chunks.size();
        }

        private ChunkPos chunk(int index) {
            return chunks.get(index);
        }

        private int materializedChunkCount() {
            return materializedChunks;
        }

        private int materializedPositionCount() {
            return materializedPositionCount;
        }

        private void release() {
            java.util.Arrays.fill(positions, null);
            exhausted = true;
        }
    }

    private static final class SearchState {
        private static final int NEIGHBOR_OUTSIDE = 0;
        private static final int NEIGHBOR_WALKABLE = 1;
        private static final int NEIGHBOR_BLOCKED = 2;
        private static final int NEIGHBOR_OPEN = 3;
        private static final int NEIGHBOR_OTHER = 4;
        private final ServerLevel level;
        private final Bounds bounds;
        private final BlockPos start;
        private final int[] buckets;
        private final int maximumAcceptedDistance;
        private final LongArrayFIFOQueue frontier = new LongArrayFIFOQueue();
        private final Long2ObjectOpenHashMap<SearchPage> pages;
        private final int[] matchCounts;
        private final int[] farthestMatchDistances;
        private final long[] farthestMatches;
        private final int[] farthestCrossHeightMatchDistances;
        private final long[] farthestCrossHeightMatches;
        private int nodeCount;
        private int maximumDistance;
        private boolean hasVerticalTransitions;
        private boolean nodeLimitHit;
        private long expansionCalls;
        private long expansionNanos;
        private long neighborCalls;
        private long neighborNanos;
        private long neighborOutside;
        private long neighborWalkable;
        private long neighborBlocked;
        private long neighborOpen;
        private long neighborOther;
        private long pageLookupCount;
        private long pageLookupNanos;
        private long frontierDequeueCount;
        private long frontierEnqueueCount;

        private SearchState(BlockPos start,
                            Bounds bounds,
                            int[] buckets,
                            int furthestNeededDistance,
                            ServerLevel level,
                            DiscoveryBudget budget) {
            this.start = start.immutable();
            this.bounds = bounds;
            this.buckets = buckets;
            this.maximumAcceptedDistance = upperTolerance(furthestNeededDistance);
            this.level = level;
            this.pages = budget.pages;
            this.matchCounts = new int[buckets.length];
            this.farthestMatchDistances = new int[buckets.length];
            this.farthestMatches = new long[buckets.length];
            this.farthestCrossHeightMatchDistances = new int[buckets.length];
            this.farthestCrossHeightMatches = new long[buckets.length];
            enqueue(this.start.asLong());
            setState(this.start, SearchPage.START);
            setDistance(this.start, 0);
            nodeCount = 1;
            recordMatches(this.start, 0);
        }

        private boolean expandOne(DiscoveryBudget budget) {
            long started = System.nanoTime();
            expansionCalls++;
            long currentKey = 0L;
            try {
                if (frontier.isEmpty()) {
                    return true;
                }
                currentKey = dequeue();
                BlockPos current = BlockPos.of(currentKey);
                int currentDistance = distance(currentKey);
                if (currentDistance >= maximumAcceptedDistance) {
                    frontier.clear();
                    return true;
                }
                for (int directionIndex = 0; directionIndex < HORIZONTAL_DIRECTIONS.length; directionIndex++) {
                    BlockPos next = findNeighbor(level, current, HORIZONTAL_DIRECTIONS[directionIndex],
                            this, budget);
                    if (next == null || state(next) != SearchPage.UNSEEN) {
                        continue;
                    }
                    if (nodeCount >= MAX_REFERENCE_NODES) {
                        nodeLimitHit = true;
                        frontier.clear();
                        return true;
                    }
                    int verticalIndex = verticalIndex(current, next);
                    int nextDistance = currentDistance + 1;
                    setState(next, SearchPage.parentCode(directionIndex, verticalIndex));
                    setDistance(next, nextDistance);
                    setCrossHeightPath(next, crossHeightPath(current) || verticalIndex != 0);
                    enqueue(next.asLong());
                    nodeCount++;
                    maximumDistance = Math.max(maximumDistance, nextDistance);
                    if (next.getY() != current.getY()) {
                        hasVerticalTransitions = true;
                    }
                    recordMatches(next, nextDistance);
                    if (nodeCount >= MAX_REFERENCE_NODES) {
                        nodeLimitHit = true;
                        frontier.clear();
                        return true;
                    }
                }
                if (nodeCount >= MAX_REFERENCE_NODES) {
                    nodeLimitHit = true;
                    frontier.clear();
                }
                return frontier.isEmpty();
            } finally {
                expansionNanos += System.nanoTime() - started;
            }
        }

        private void recordMatches(BlockPos position, int pathLength) {
            for (int index = 0; index < buckets.length; index++) {
                if (withinTolerance(pathLength, buckets[index])) {
                    matchCounts[index]++;
                    long key = position.asLong();
                    if (pathLength > farthestMatchDistances[index]
                            || (pathLength == farthestMatchDistances[index]
                            && (farthestMatches[index] == 0L || key < farthestMatches[index]))) {
                        farthestMatchDistances[index] = pathLength;
                        farthestMatches[index] = key;
                    }
                    if (crossHeightPath(position)
                            && (pathLength > farthestCrossHeightMatchDistances[index]
                            || (pathLength == farthestCrossHeightMatchDistances[index]
                            && (farthestCrossHeightMatches[index] == 0L
                            || key < farthestCrossHeightMatches[index])))) {
                        farthestCrossHeightMatchDistances[index] = pathLength;
                        farthestCrossHeightMatches[index] = key;
                    }
                }
            }
        }

        private SearchResult finish() {
            return new SearchResult(start, pages, buckets, matchCounts,
                    farthestMatchDistances, farthestMatches,
                    farthestCrossHeightMatchDistances, farthestCrossHeightMatches,
                    maximumDistance, hasVerticalTransitions, nodeLimitHit, nodeCount,
                    pageLookupCount, pageLookupNanos, frontierDequeueCount,
                    frontierEnqueueCount, neighborCalls, neighborNanos,
                    neighborOutside, neighborWalkable, neighborBlocked,
                    neighborOpen, neighborOther, expansionCalls, expansionNanos);
        }

        private void markRejected(BlockPos position) {
            if (state(position) == SearchPage.UNSEEN) {
                setState(position, SearchPage.CLASSIFIED_BLOCKED);
            }
        }

        private byte state(BlockPos position) {
            SearchPage page = lookupPage(position);
            return page == null ? SearchPage.UNSEEN : page.state(localIndex(position));
        }

        private void setState(BlockPos position, byte value) {
            SearchPage page = writablePage(position);
            page
                    .setState(localIndex(position), value);
        }

        private void setDistance(BlockPos position, int value) {
            SearchPage page = writablePage(position);
            page
                    .setDistance(localIndex(position), value);
        }

        private boolean crossHeightPath(BlockPos position) {
            SearchPage page = lookupPage(position);
            return page != null && page.crossHeightPath(localIndex(position));
        }

        private void setCrossHeightPath(BlockPos position, boolean value) {
            writablePage(position).setCrossHeightPath(localIndex(position), value);
        }

        private int distance(long key) {
            SearchPage page = lookupPage(BlockPos.of(key));
            return page == null ? 0 : page.distance(localIndex(BlockPos.of(key)));
        }

        private SearchPage lookupPage(BlockPos position) {
            long started = System.nanoTime();
            SearchPage page = pages.get(sectionKey(position));
            pageLookupCount++;
            pageLookupNanos += System.nanoTime() - started;
            return page;
        }

        private SearchPage writablePage(BlockPos position) {
            long started = System.nanoTime();
            SearchPage page = pages.computeIfAbsent(sectionKey(position), ignored -> new SearchPage());
            pageLookupCount++;
            pageLookupNanos += System.nanoTime() - started;
            return page;
        }

        private long dequeue() {
            long key = frontier.dequeueLong();
            frontierDequeueCount++;
            return key;
        }

        private void enqueue(long key) {
            frontier.enqueue(key);
            frontierEnqueueCount++;
        }

        private void recordNeighbor(int branch, long elapsedNanos) {
            neighborCalls++;
            neighborNanos += elapsedNanos;
            switch (branch) {
                case NEIGHBOR_OUTSIDE -> neighborOutside++;
                case NEIGHBOR_WALKABLE -> neighborWalkable++;
                case NEIGHBOR_BLOCKED -> neighborBlocked++;
                case NEIGHBOR_OPEN -> neighborOpen++;
                default -> neighborOther++;
            }
        }

        private void release() {
            frontier.clear();
            pages.clear();
        }

        private static int verticalIndex(BlockPos current, BlockPos next) {
            int vertical = next.getY() - current.getY();
            for (int index = 0; index < HEIGHT_OFFSETS.length; index++) {
                if (HEIGHT_OFFSETS[index] == vertical) {
                    return index;
                }
            }
            throw new IllegalStateException("reference neighbor has unsupported height change: " + vertical);
        }
    }

    private static final class SearchResult {
        private final BlockPos start;
        private final Long2ObjectOpenHashMap<SearchPage> pages;
        private final int[] buckets;
        private final int[] matchCounts;
        private final int[] farthestMatchDistances;
        private final long[] farthestMatches;
        private final int[] farthestCrossHeightMatchDistances;
        private final long[] farthestCrossHeightMatches;
        private final int maximumDistance;
        private final boolean hasVerticalTransitions;
        private final boolean nodeLimitHit;
        private final int nodeCount;
        private long pageLookupCount;
        private long pageLookupNanos;
        private final long frontierDequeueCount;
        private final long frontierEnqueueCount;
        private final long neighborCalls;
        private final long neighborNanos;
        private final long neighborOutside;
        private final long neighborWalkable;
        private final long neighborBlocked;
        private final long neighborOpen;
        private final long neighborOther;
        private final long expansionCalls;
        private final long expansionNanos;

        private SearchResult(BlockPos start,
                             Long2ObjectOpenHashMap<SearchPage> pages,
                             int[] buckets,
                             int[] matchCounts,
                             int[] farthestMatchDistances,
                             long[] farthestMatches,
                             int[] farthestCrossHeightMatchDistances,
                             long[] farthestCrossHeightMatches,
                             int maximumDistance,
                             boolean hasVerticalTransitions,
                             boolean nodeLimitHit,
                             int nodeCount,
                             long pageLookupCount,
                             long pageLookupNanos,
                             long frontierDequeueCount,
                             long frontierEnqueueCount,
                             long neighborCalls,
                             long neighborNanos,
                             long neighborOutside,
                             long neighborWalkable,
                             long neighborBlocked,
                             long neighborOpen,
                             long neighborOther,
                             long expansionCalls,
                             long expansionNanos) {
            this.start = start;
            this.pages = pages;
            this.buckets = buckets;
            this.matchCounts = matchCounts;
            this.farthestMatchDistances = farthestMatchDistances;
            this.farthestMatches = farthestMatches;
            this.farthestCrossHeightMatchDistances = farthestCrossHeightMatchDistances;
            this.farthestCrossHeightMatches = farthestCrossHeightMatches;
            this.maximumDistance = maximumDistance;
            this.hasVerticalTransitions = hasVerticalTransitions;
            this.nodeLimitHit = nodeLimitHit;
            this.nodeCount = nodeCount;
            this.pageLookupCount = pageLookupCount;
            this.pageLookupNanos = pageLookupNanos;
            this.frontierDequeueCount = frontierDequeueCount;
            this.frontierEnqueueCount = frontierEnqueueCount;
            this.neighborCalls = neighborCalls;
            this.neighborNanos = neighborNanos;
            this.neighborOutside = neighborOutside;
            this.neighborWalkable = neighborWalkable;
            this.neighborBlocked = neighborBlocked;
            this.neighborOpen = neighborOpen;
            this.neighborOther = neighborOther;
            this.expansionCalls = expansionCalls;
            this.expansionNanos = expansionNanos;
        }

        @Nullable
        private SourcePath recoverSourcePath(boolean preferCrossHeight) {
            BlockPos goal = null;
            int bestDistance = -1;
            if (preferCrossHeight) {
                for (int bucketIndex = 0; bucketIndex < buckets.length; bucketIndex++) {
                    int distance = farthestCrossHeightMatchDistances[bucketIndex];
                    long match = farthestCrossHeightMatches[bucketIndex];
                    if (distance > bestDistance
                            || (distance == bestDistance && distance > 0
                            && (goal == null || match < goal.asLong()))) {
                        goal = BlockPos.of(match);
                        bestDistance = distance;
                    }
                }
            }
            if (goal == null || bestDistance <= 0) {
                goal = null;
                bestDistance = -1;
                for (int bucketIndex = 0; bucketIndex < buckets.length; bucketIndex++) {
                    if (matchCounts[bucketIndex] == 0) {
                        continue;
                    }
                    int distance = farthestMatchDistances[bucketIndex];
                    long match = farthestMatches[bucketIndex];
                    if (distance > bestDistance
                            || (distance == bestDistance && distance > 0
                            && (goal == null || match < goal.asLong()))) {
                        goal = BlockPos.of(match);
                        bestDistance = distance;
                    }
                }
            }
            if (goal == null || bestDistance <= 0) {
                return null;
            }
            LongArrayList reverse = new LongArrayList(bestDistance + 1);
            long current = goal.asLong();
            reverse.add(current);
            while (current != start.asLong()) {
                BlockPos position = BlockPos.of(current);
                SearchPage page = lookupPage(position);
                if (page == null) {
                    throw new IllegalStateException("reference path page disappeared during recovery");
                }
                int code = page.state(localIndex(position)) - SearchPage.PARENT_BASE;
                if (code < 0 || code >= HORIZONTAL_DIRECTIONS.length * HEIGHT_OFFSETS.length) {
                    throw new IllegalStateException("reference path parent is incomplete at " + position);
                }
                int directionIndex = code / HEIGHT_OFFSETS.length;
                int verticalIndex = code % HEIGHT_OFFSETS.length;
                int[] direction = HORIZONTAL_DIRECTIONS[directionIndex];
                current = new BlockPos(position.getX() - direction[0],
                        position.getY() - HEIGHT_OFFSETS[verticalIndex],
                        position.getZ() - direction[1]).asLong();
                reverse.add(current);
                if (reverse.size() > bestDistance + 1) {
                    throw new IllegalStateException("reference path parent chain exceeds recorded distance");
                }
            }
            LongArrayList path = new LongArrayList(reverse.size());
            for (int index = reverse.size() - 1; index >= 0; index--) {
                path.add(reverse.getLong(index));
            }
            if (path.size() != bestDistance + 1) {
                throw new IllegalStateException("reference path length disagrees with breadth-first distance");
            }
            return new SourcePath(path);
        }

        private SearchPage lookupPage(BlockPos position) {
            long started = System.nanoTime();
            SearchPage page = pages.get(sectionKey(position));
            pageLookupCount++;
            pageLookupNanos += System.nanoTime() - started;
            return page;
        }

        private void release() {
            pages.clear();
        }
    }

    private static final class SourcePath {
        private final LongArrayList positions;
        private final int[] crossHeightPrefix;
        private final long key;

        private SourcePath(LongArrayList positions) {
            this.positions = positions;
            this.crossHeightPrefix = new int[positions.size()];
            long hash = 0xcbf29ce484222325L;
            for (int index = 0; index < positions.size(); index++) {
                long position = positions.getLong(index);
                hash ^= position;
                hash *= 0x100000001b3L;
                if (index > 0) {
                    BlockPos previous = BlockPos.of(positions.getLong(index - 1));
                    BlockPos current = BlockPos.of(position);
                    crossHeightPrefix[index] = crossHeightPrefix[index - 1]
                            + (previous.getY() == current.getY() ? 0 : 1);
                }
            }
            this.key = hash;
        }

        private int length() {
            return positions.size() - 1;
        }

        private List<PathSegment> segments(int requestedDistance) {
            int pathLength = length();
            if (pathLength <= 0) {
                return List.of();
            }
            int segmentLength = Math.min(requestedDistance, pathLength);
            if (!withinTolerance(segmentLength, requestedDistance)) {
                return List.of();
            }
            List<PathSegment> segments = new ArrayList<>(pathLength - segmentLength + 1);
            for (int startIndex = 0; startIndex + segmentLength < positions.size(); startIndex++) {
                int goalIndex = startIndex + segmentLength;
                segments.add(new PathSegment(
                        BlockPos.of(positions.getLong(startIndex)),
                        BlockPos.of(positions.getLong(goalIndex)),
                        segmentLength,
                        crossHeightPrefix[goalIndex] - crossHeightPrefix[startIndex]));
            }
            return segments;
        }
    }

    private record PathSegment(BlockPos start,
                               BlockPos goal,
                               int length,
                               int crossHeightSteps) implements Comparable<PathSegment> {
        @Override
        public int compareTo(PathSegment other) {
            int startOrder = Long.compare(start.asLong(), other.start.asLong());
            return startOrder != 0 ? startOrder : Long.compare(goal.asLong(), other.goal.asLong());
        }
    }

    private static final class SearchPage {
        private static final byte UNSEEN = 0;
        private static final byte CLASSIFIED_BLOCKED = 1;
        private static final byte START = 2;
        private static final byte PARENT_BASE = 3;
        private final byte[] states = new byte[4096];
        private final short[] distances = new short[4096];
        private final byte[] classifications = new byte[4096];
        private final byte[] crossHeightPaths = new byte[4096];

        private static byte parentCode(int directionIndex, int verticalIndex) {
            return (byte) (PARENT_BASE + directionIndex * HEIGHT_OFFSETS.length + verticalIndex);
        }

        private byte state(int index) {
            return states[index];
        }

        private void setState(int index, byte value) {
            states[index] = value;
        }

        private int distance(int index) {
            return Short.toUnsignedInt(distances[index]);
        }

        private void setDistance(int index, int value) {
            if (value < 0 || value > Short.MAX_VALUE) {
                throw new IllegalArgumentException("reference distance exceeds compact page range: " + value);
            }
            distances[index] = (short) value;
        }

        private int classification(int index) {
            return Byte.toUnsignedInt(classifications[index]);
        }

        private void setClassification(int index, BlockPathTypes type) {
            classifications[index] = (byte) (type.ordinal() + 1);
        }

        private boolean crossHeightPath(int index) {
            return crossHeightPaths[index] != 0;
        }

        private void setCrossHeightPath(int index, boolean value) {
            crossHeightPaths[index] = value ? (byte) 1 : 0;
        }
    }

    private static final class DiscoveryBudget {
        private final Bounds bounds;
        private final long terrainLimit;
        private final long batchLimit;
        private long classifications;
        private long classificationNanos;
        private long evaluationCalls;
        private long classificationCacheHits;
        private long attemptClassifications;
        private long attemptClassificationNanos;
        private long attemptEvaluationCalls;
        private long attemptClassificationCacheHits;
        private long stepDeadlineNanos;
        private boolean stepStopped;
        private final Long2ObjectOpenHashMap<SearchPage> pages = new Long2ObjectOpenHashMap<>();

        private DiscoveryBudget(Bounds bounds, long terrainLimit, long batchLimit) {
            this.bounds = bounds;
            this.terrainLimit = terrainLimit;
            this.batchLimit = batchLimit;
        }

        private boolean classify(ServerLevel level, BlockPos position) {
            return evaluate(level, position) == BlockPathTypes.WALKABLE;
        }

        private boolean open(ServerLevel level, BlockPos position) {
            BlockPathTypes type = evaluate(level, position);
            return type == BlockPathTypes.OPEN || type == BlockPathTypes.WALKABLE;
        }

        private BlockPathTypes type(ServerLevel level, BlockPos position) {
            return evaluate(level, position);
        }

        private void beginStep(long deadlineNanos) {
            stepDeadlineNanos = deadlineNanos;
            stepStopped = false;
        }

        private boolean canStartUnit() {
            if (stepStopped) {
                return false;
            }
            if (System.nanoTime() >= stepDeadlineNanos) {
                stepStopped = true;
                return false;
            }
            return true;
        }

        private boolean stepStopped() {
            return stepStopped;
        }

        private BlockPathTypes evaluate(ServerLevel level, BlockPos position) {
            evaluationCalls++;
            attemptEvaluationCalls++;
            long pageKey = sectionKey(position);
            int localIndex = localIndex(position);
            SearchPage page = pages.get(pageKey);
            int cached = page == null ? 0 : page.classification(localIndex);
            if (cached > 0) {
                classificationCacheHits++;
                attemptClassificationCacheHits++;
                return PATH_TYPES[cached - 1];
            }
            if (classifications >= batchLimit) {
                throw new BudgetExceeded(BudgetScope.BATCH);
            }
            if (classifications >= terrainLimit) {
                throw new BudgetExceeded(BudgetScope.TERRAIN);
            }
            if (attemptClassifications >= MAX_ATTEMPT_CLASSIFICATIONS) {
                throw new BudgetExceeded(BudgetScope.ATTEMPT);
            }
            if (!inside(position, bounds)) {
                throw new IllegalStateException(
                        "terrain qualification classification escaped its approved bounds: " + position);
            }
            requireAvailableClassificationChunks(level, position);
            classifications++;
            attemptClassifications++;
            long started = System.nanoTime();
            BlockPathTypes value;
            try {
                value = WalkNodeEvaluator.getBlockPathTypeStatic(level,
                        new BlockPos.MutableBlockPos(position.getX(), position.getY(), position.getZ()));
            } finally {
                long elapsed = System.nanoTime() - started;
                classificationNanos += elapsed;
                attemptClassificationNanos += elapsed;
            }
            if (page == null) {
                page = new SearchPage();
                pages.put(pageKey, page);
            }
            page.setClassification(localIndex, value);
            return value;
        }

        private void beginAttempt() {
            attemptClassifications = 0;
            attemptClassificationNanos = 0L;
            attemptEvaluationCalls = 0L;
            attemptClassificationCacheHits = 0L;
            pages.clear();
        }
    }

    private enum BudgetScope {
        ATTEMPT,
        TERRAIN,
        BATCH
    }

    private static final class BudgetExceeded extends RuntimeException {
        private final BudgetScope scope;

        private BudgetExceeded(BudgetScope scope) {
            super("terrain qualification " + scope.name().toLowerCase()
                    + " classification budget exhausted");
            this.scope = scope;
        }
    }

    private record Bounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        private RegionBounds toRegionBounds() {
            return new RegionBounds(minX, maxX, minY, maxY, minZ, maxZ);
        }

        private static Bounds forTerrain(ServerLevel level, String terrain) {
            List<ChunkPos> chunks = plannedChunks(level, terrain);
            ChunkPos first = chunks.get(0);
            ChunkPos last = chunks.get(chunks.size() - 1);
            int minX = first.getMinBlockX() + 1;
            int maxX = last.getMaxBlockX() - 1;
            int minZ = Math.min(first.getMinBlockZ(), last.getMinBlockZ()) + 1;
            int maxZ = Math.max(first.getMaxBlockZ(), last.getMaxBlockZ()) - 1;
            boolean nether = "nether".equals(terrain)
                    || level.dimension().location().toString().equals("minecraft:the_nether");
            return nether
                    ? new Bounds(minX, maxX, level.getMinBuildHeight() + 1,
                    Math.min(level.getMaxBuildHeight() - 2, 128), minZ, maxZ)
                    : new Bounds(minX, maxX, level.getMinBuildHeight() + 1,
                    level.getMaxBuildHeight() - 2, minZ, maxZ);
        }
    }
}
