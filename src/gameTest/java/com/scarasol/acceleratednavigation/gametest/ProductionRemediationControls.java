package com.scarasol.acceleratednavigation.gametest;

import com.scarasol.acceleratednavigation.topology.TopologyGenerationProbe;
import com.scarasol.acceleratednavigation.topology.TopologyService;
import com.scarasol.acceleratednavigation.topology.TopologyTestBridge;
import com.scarasol.acceleratednavigation.topology.TopologyValidationAccess;
import com.scarasol.acceleratednavigation.topology.TopologyHandoffScenario;
import com.scarasol.acceleratednavigation.topology.TopologyHandoffFailureScenario;
import com.scarasol.acceleratednavigation.topology.TopologySectionEventProbe;
import com.scarasol.acceleratednavigation.topology.TopologyEndpointFailureScenario;
import com.scarasol.acceleratednavigation.topology.TopologyFinalResultScenario;
import com.scarasol.acceleratednavigation.topology.TopologyRequestLifecycleScenario;
import com.scarasol.acceleratednavigation.topology.TopologyConnectionScenario;
import com.scarasol.acceleratednavigation.topology.TopologyDerivedLifecycleScenario;
import com.scarasol.acceleratednavigation.topology.TopologyPrewarmScenario;
import com.scarasol.acceleratednavigation.topology.TopologyShutdownScenario;
import com.scarasol.acceleratednavigation.topology.TopologyDependencyLimitScenario;
import com.scarasol.acceleratednavigation.topology.TopologyMixedFailureScenario;
import com.scarasol.acceleratednavigation.topology.BaseClusterTopology;
import com.scarasol.acceleratednavigation.topology.MacroSearch;
import com.scarasol.acceleratednavigation.scheduler.NavigationScheduler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.ticks.ProtoChunkTicks;

/** Independent correctness controls use the same server lifecycle and report chain as terrain. */
final class ProductionRemediationControls implements RealTerrainTopologyBenchmark.ServerLifecycle {
    private final MinecraftServer server;
    private final TopologyService topology;
    private final ProductionRemediationPlan.Options options;
    private final ProductionRemediationReport.Journal journal;
    private final ArrayDeque<ProductionRemediationPlan.Target> pending;
    private final Map<String, Object> summary = new LinkedHashMap<>();
    private final long started = System.nanoTime();
    private ProductionRemediationPlan.Target current;
    private Map<String, Object> evidence;
    private long targetStarted;
    private LevelChunk tracked;
    private ServerLevel targetLevel;
    private ChunkPos targetChunk;
    private long cleanupStarted;
    private String completedOutcome, completedReason;
    private boolean completedReached;
    private TopologyHandoffScenario handoff;
    private TopologyHandoffFailureScenario handoffFailure;
    private TopologyEndpointFailureScenario endpointFailure;
    private TopologyFinalResultScenario finalResult;
    private TopologyRequestLifecycleScenario requestLifecycle;
    private TopologyConnectionScenario connection;
    private TopologyDerivedLifecycleScenario derivedLifecycle;
    private TopologyPrewarmScenario prewarm;
    private TopologyShutdownScenario shutdown;
    private TopologyDependencyLimitScenario dependencyLimit;
    private TopologyMixedFailureScenario mixedFailure;
    private com.scarasol.acceleratednavigation.topology.TopologyThreadingScenario threading;
    private Set<Long> cleanupChunks = Set.of();
    private java.util.concurrent.CompletableFuture<Void> pendingFaultEdit;
    private TopologySectionEventProbe sectionProbe;
    private Map<Integer, Byte> expectedFirstDelta, expectedSecondDelta;
    private TopologyService.MacroRequest generationConsumer;
    private TopologyValidationAccess.Capture generationCapture;
    private int generationQueries;
    private int stage, stageTick;
    private boolean requestedStop, published;

    ProductionRemediationControls(MinecraftServer server) {
        this.server = server; topology = TopologyService.forServer(server);
        options = ProductionRemediationPlan.Options.configured();
        pending = new ArrayDeque<>(ProductionRemediationPlan.targets(options));
        try { journal = new ProductionRemediationReport.Journal(Path.of(System.getProperty("acceleratedNavigation.validation.output")), options); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
        summary.put("runScope", Map.of("case", options.scenario(), "plannedControls", pending.size()));
        summary.put("routes", List.of()); summary.put("pressureStages", List.of());
        summary.put("historyBaseline", Map.of("status", "NOT_APPLICABLE", "reason", "correctness control"));
    }

    @Override public void tick() {
        if (requestedStop) return;
        if (System.nanoTime() - started > 2_700_000_000_000L) throw new IllegalStateException("control process exceeded 45 minutes");
        if (completedOutcome != null) { settle(); return; }
        if (current == null) {
            current = pending.pollFirst();
            if (current == null) {
                if (TopologyGenerationProbe.COST) {
                    Map<String, Object> cost = TopologyGenerationProbe.generationCost();
                    if (((Number) cost.get("activeStages")).intValue() != 0) return;
                    summary.put("generationCost", cost);
                }
                requestedStop = true; server.halt(false); return;
            }
            evidence = new LinkedHashMap<>(); stage = 0; targetStarted = System.nanoTime();
            targetLevel = null; targetChunk = null; cleanupChunks = Set.of();
            if ("missed".equals(options.control())) { complete("NOT_ENTERED", "FIXTURE_TARGET_SKIPPED", false); return; }
        }
        if (System.nanoTime() - targetStarted > 30_000_000_000L) { complete("NOT_ENTERED", "CONTROL_TIMEOUT", false); return; }
        try {
            boolean done = switch (current.kind()) {
                case "G01" -> constructors();
                case "G02" -> generatedChunk();
                case "G03", "G03/shape" -> writes();
                case "G04" -> responsibilitySwitch();
                case "G05" -> partialReload();
                case "H01", "H04", "H05" -> handoff();
                case "H02/baseline", "H02/recovery", "H03" -> handoffFailure();
                case "F01/direct", "F01/alternate" -> endpointFailure();
                case "F02", "F02/node-tie", "F02/section-tie", "F03/alternative", "F03/rejected" -> mixedFailure();
                case "F04" -> finalResult();
                case "L04/capacity", "L05" -> requestLifecycle();
                case "L01/ground", "L01/volume", "L02", "L02/vertical" -> connection();
                case "L03" -> derivedLifecycle();
                case "L04/prewarm" -> prewarm();
                case "L04/dependencies" -> dependencyLimit();
                case "D06" -> threading();
                case "W01", "W02", "W03", "W04", "W05", "W06", "W07", "W08", "W09" -> shutdown();
                default -> throw new IllegalStateException("No executor installed for " + current.kind());
            };
            if (done) {
                boolean detected = !"mutated".equals(options.control()) || Boolean.TRUE.equals(evidence.get("mutationDetected"));
                String outcome = String.valueOf(evidence.getOrDefault("outcome", detected ? "PASS" : "FAIL"));
                complete(outcome, detected ? "ASSERTIONS_COMPLETE" : "MUTATION_NOT_DETECTED", !"NOT_APPLICABLE".equals(outcome));
            }
        } catch (NotEntered missing) {
            evidence.put("missingEntry", missing.getMessage());
            complete("NOT_ENTERED", "TARGET_ENTRY_NOT_OBSERVED", false);
        } catch (AssertionError failure) {
            evidence.put("assertion", failure.getMessage());
            complete("FAIL", "ASSERTION_REJECTED", true);
        }
    }

    private boolean constructors() {
        String constructor = parameter("constructor");
        ServerLevel level = server.overworld();
        ProtoChunk proto = proto(level, constructor.equals("delegating"), new ChunkPos(96, 96));
        require(state(proto).versions().isEmpty(), "new air has no changed versions");
        require((boolean) TopologyTestBridge.readField(state(proto), "generationInitialized"), "generation initialized at constructor return");
        ChunkAccess owner = proto;
        if (List.of("imposter", "promotion", "load").contains(constructor)) {
            LevelChunk promoted = new LevelChunk(level, proto, null);
            require(state(promoted) == state(proto), "promotion transfers the same state");
            owner = promoted;
            if (constructor.equals("imposter")) {
                new ImposterProtoChunk(promoted, false);
                require(boundOwner(promoted, 0) == promoted, "imposter constructor preserves wrapped section owner");
            }
            if (constructor.equals("load")) {
                tracked = promoted;
                targetLevel = level; targetChunk = promoted.getPos();
                TopologyService.onChunkLoaded(level, promoted);
                owner = null;
            }
        }
        ChunkAccess inspected = tracked != null ? tracked : owner != null ? owner : proto;
        require(boundOwner(inspected, 0) == owner, "section binds only its current generation owner");
        if ("mutated".equals(options.control())) {
            ((TopologyService.GenerationSection) inspected.getSection(inspected.getSectionIndexFromSectionY(0)))
                    .acceleratedNavigation$bind(owner == null ? proto : null, 0);
            detectMutation(boundOwner(inspected, 0) == owner, "section owner");
            ((TopologyService.GenerationSection) inspected.getSection(inspected.getSectionIndexFromSectionY(0))).acceleratedNavigation$bind(owner, 0);
        }
        evidence.put("constructor", constructor); evidence.put("tracking", tracked != null);
        return true;
    }

    private boolean generatedChunk() {
        boolean nether = parameter("dimension").equals("nether");
        ServerLevel level = nether ? server.getLevel(Level.NETHER) : server.overworld();
        int x = integer("x"), z = integer("z");
        targetLevel = level; targetChunk = new ChunkPos(x, z);
        level.getChunk(x, z, ChunkStatus.FULL, true);
        Map<String, Object> observed = TopologyGenerationProbe.loaded(level.dimension().location().toString(), x, z);
        if (observed == null) return false;
        evidence.putAll(observed);
        for (String field : List.of("noiseCalls", "oreCalls", "noiseWrites", "oreWrites")) {
            if (((Number) observed.get(field)).longValue() == 0) throw new NotEntered(field);
        }
        if (!TopologyGenerationProbe.COST) {
            @SuppressWarnings("unchecked") Map<String, Object> comparison = (Map<String, Object>) observed.get("cellComparison");
            long equal = ((Number) comparison.get("equal")).longValue();
            require(equal == ((Number) comparison.get("checked")).longValue(), "all final generated cells equal independent four-bit oracle");
            if ("mutated".equals(options.control())) detectMutation(equal - 1 == ((Number) comparison.get("checked")).longValue(), "cell comparison");
        }
        return true;
    }

    private boolean writes() {
        ProtoChunk chunk = proto(server.overworld(), false, new ChunkPos(96, 96));
        BlockPos position = current.kind().equals("G03/shape") ? new BlockPos(1537, 1, 1537)
                : new BlockPos(chunk.getPos().getMinBlockX() + integer("x"), integer("section") * 16 + integer("y"), chunk.getPos().getMinBlockZ() + integer("z"));
        List<BlockState> sequence = current.kind().equals("G03/shape")
                ? List.of(parameter("block").equals("oak_fence") ? Blocks.OAK_FENCE.defaultBlockState() : Blocks.STONE_SLAB.defaultBlockState())
                : List.of(Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState(), Blocks.AIR.defaultBlockState());
        List<Map<String, Object>> steps = new java.util.ArrayList<>();
        for (BlockState block : sequence) {
            Map<Integer, Long> before = state(chunk).versions();
            int beforeCell = flag(chunk, position), beforeAbove = flag(chunk, position.above());
            var section = chunk.getSection(chunk.getSectionIndex(position.getY()));
            if ("four".equals(current.parameters().get("setter"))) section.setBlockState(position.getX() & 15, position.getY() & 15, position.getZ() & 15, block);
            else section.setBlockState(position.getX() & 15, position.getY() & 15, position.getZ() & 15, block, false);
            int expected = TopologyGenerationProbe.independentFlags(chunk, position), expectedAbove = TopologyGenerationProbe.independentFlags(chunk, position.above());
            int observed = flag(chunk, position);
            require(observed == expected && flag(chunk, position.above()) == expectedAbove, "writer maintains current and above cells");
            if ("mutated".equals(options.control())) detectMutation((observed ^ 1) == expected, "written cell flags");
            Map<Integer, Long> after = state(chunk).versions();
            long changes = (expected == beforeCell ? 0 : 1) + (expectedAbove == beforeAbove ? 0 : 1);
            long versionDelta = after.values().stream().mapToLong(Long::longValue).sum() - before.values().stream().mapToLong(Long::longValue).sum();
            require(changes == versionDelta, "one version increment per real cell change");
            steps.add(Map.of("before", before, "after", after, "current", observed, "above", expectedAbove, "changes", changes));
        }
        evidence.put("steps", steps);
        return true;
    }

    private boolean responsibilitySwitch() {
        ServerLevel level = server.overworld();
        BlockPos position = new BlockPos(1537, 1, 1537);
        if (stage == 0) {
            ProtoChunk proto = proto(level, false, new ChunkPos(96, 96));
            tracked = new LevelChunk(level, proto, null);
            targetLevel = level; targetChunk = tracked.getPos();
            tracked.setBlockState(position, Blocks.STONE.defaultBlockState(), false);
            require(!state(tracked).versions().isEmpty(), "promotion before load still collects generation changes");
            sectionProbe = TopologySectionEventProbe.watch(level.dimension(), net.minecraft.core.SectionPos.of(position));
            TopologyService.onChunkLoaded(level, tracked);
            require(boundOwner(tracked, 0) == null, "formal load releases generation owner");
            evidence.put("before", state(tracked).versions());
            Map<Integer, Byte> beforeCells = oracleCells(tracked, List.of(position, position.above(), position.offset(1, 0, 0), position.offset(1, 1, 0)));
            tracked.setBlockState(position, Blocks.AIR.defaultBlockState(), false);
            tracked.setBlockState(position, Blocks.WATER.defaultBlockState(), false);
            tracked.setBlockState(position.offset(1, 0, 0), Blocks.STONE.defaultBlockState(), false);
            expectedFirstDelta = changedCells(beforeCells, oracleCells(tracked, List.of(position, position.above(), position.offset(1, 0, 0), position.offset(1, 1, 0))));
            evidence.put("sameTick", state(tracked).versions());
            stageTick = server.getTickCount(); stage = 1;
            return false;
        }
        if (stage == 1 && server.getTickCount() <= stageTick) return false;
        if (stage == 1) {
            Map<Integer, Byte> beforeCells = oracleCells(tracked, List.of(position, position.above()));
            tracked.setBlockState(position, Blocks.AIR.defaultBlockState(), false);
            expectedSecondDelta = changedCells(beforeCells, oracleCells(tracked, List.of(position, position.above())));
            evidence.put("nextTick", state(tracked).versions());
            @SuppressWarnings("unchecked") Map<Integer, Long> before = (Map<Integer, Long>) evidence.get("before");
            @SuppressWarnings("unchecked") Map<Integer, Long> same = (Map<Integer, Long>) evidence.get("sameTick");
            long actual = state(tracked).versions().get(0);
            require(same.get(0) == before.get(0) + 1 && actual == same.get(0) + 1, "formal changes advance once per tick, not once per write");
            stageTick = server.getTickCount(); stage = 2;
            return false;
        }
        if (server.getTickCount() <= stageTick || !targetSettled()) return false;
        List<TopologySectionEventProbe.Publication> deltas = sectionProbe.publications().stream().filter(event -> !event.changes().isEmpty()).toList();
        verifyTickDeltas(deltas, expectedFirstDelta, expectedSecondDelta);
        require(sectionProbe.scans().isEmpty(), "normal generation-to-load changes never scan a section");
        comparePublishedSection(tracked, 0);
        if ("mutated".equals(options.control())) {
            var second = deltas.get(1);
            var corrupt = new TopologySectionEventProbe.Publication(second.load(), second.previousVersion() + 1,
                    second.version(), second.state(), second.factsToken(), second.changes());
            boolean rejected = false;
            try { verifyTickDeltas(List.of(deltas.get(0), corrupt), expectedFirstDelta, expectedSecondDelta); }
            catch (AssertionError expected) { rejected = true; }
            require(rejected, "qualification rejects a broken final-delta version chain"); evidence.put("mutationDetected", true);
        }
        evidence.put("sectionPublications", sectionProbe.publications()); evidence.put("scanCounts", sectionProbe.scans());
        return true;
    }

    private boolean partialReload() {
        ServerLevel level = server.overworld();
        if (stage == 0) {
            int slot = List.of("normal", "missing", "corrupt").indexOf(parameter("version")) * 2 + (parameter("section").equals("sparse") ? 1 : 0);
            ChunkPos pos = new ChunkPos(112 + slot, 112);
            targetLevel = level; targetChunk = pos;
            ProtoChunk proto = proto(level, false, pos);
            if (parameter("section").equals("sparse")) proto.setBlockState(new BlockPos(pos.getMinBlockX() + 1, 7, pos.getMinBlockZ() + 1), Blocks.STONE.defaultBlockState(), false);
            CompoundTag tag = ChunkSerializer.write(level, proto);
            if (parameter("version").equals("missing")) tag.remove("accelerated_navigation_facts_versions");
            else if (parameter("version").equals("corrupt")) tag.putString("accelerated_navigation_facts_versions", "invalid");
            ProtoChunk resumed = ChunkSerializer.read(level, level.getPoiManager(), pos, tag);
            require((boolean) TopologyTestBridge.readField(state(resumed), "loadedFromDisk"), "NBT reload cannot inherit new-air baseline");
            for (int y : List.of(0, 15)) resumed.getSection(resumed.getSectionIndex(y)).setBlockState(1, y, 1, Blocks.STONE.defaultBlockState(), false);
            require(!state(resumed).versions().containsKey(0), "resumed write invalidates touched persisted section version");
            require(((Map<?, ?>) TopologyTestBridge.readField(state(resumed), "generatedCells")).isEmpty(), "sparse resumed writes never claim complete facts");
            CompoundTag saved = ChunkSerializer.write(level, resumed);
            ProtoChunk roundTrip = ChunkSerializer.read(level, level.getPoiManager(), pos, saved);
            long actual = state(roundTrip).versions().getOrDefault(0, -1L);
            require(actual == -1L, "invalidated version remains absent after second NBT round trip");
            if ("mutated".equals(options.control())) detectMutation(0 == actual, "persisted invalid version");
            level.getChunkSource().chunkMap.write(pos, saved);
            sectionProbe = TopologySectionEventProbe.watch(level.dimension(), net.minecraft.core.SectionPos.of(pos.x, 0, pos.z));
            level.getChunk(pos.x, pos.z, ChunkStatus.FULL, true);
            evidence.put("chunkX", pos.x); evidence.put("chunkZ", pos.z); generationQueries = 0; stage = 1;
            return false;
        }
        Object loaded = sectionProbe.loaded(topology);
        if (loaded == null || !(boolean) TopologyTestBridge.readField(loaded, "recoveryAttempted") || !targetSettled()) return false;
        long load = (long) TopologyTestBridge.readField(TopologyTestBridge.readField(loaded, "chunk"), "identity");
        require(!(boolean) TopologyTestBridge.readField(loaded, "recoveryFailed"), "partial reload recovery reaches one successful terminal");
        require(sectionProbe.scans().equals(Map.of(load, 1)), "partial reload has exactly one observed scan in its load identity");
        if (stage == 1) {
            comparePublishedSection(targetLevel.getChunk(targetChunk.x, targetChunk.z), 0);
            generationCapture = TopologyValidationAccess.beginCapture();
            BlockPos start = new BlockPos(targetChunk.getMinBlockX() + 7, 7, targetChunk.getMinBlockZ() + 7);
            generationConsumer = topology.requestMacroQuery(level, java.util.UUID.randomUUID(), start, start.offset(1, 0, 0),
                    BaseClusterTopology.Channel.VOLUME, BaseClusterTopology.TraversalProfile.DEFAULT_GROUND, NavigationScheduler.Priority.ACTIVE);
            stage = 2; return false;
        }
        if (!generationConsumer.future().isDone()) return false;
        generationConsumer.future().join();
        MacroSearch.Failure failure = generationConsumer.progress().failure();
        require(failure != MacroSearch.Failure.FACTS_RECOVERY_FAILED && failure != MacroSearch.Failure.FACTS_PERSISTENCE_UNAVAILABLE
                && failure != MacroSearch.Failure.UNAVAILABLE_CHUNK, "real consumer receives recovered facts rather than an availability failure");
        TopologyValidationAccess.endCapture(generationCapture);
        require(generationCapture.used().keySet().stream().anyMatch(key -> key.startsWith("facts/")), "the real endpoint consumer captured recovered facts");
        evidence.put("consumer" + generationQueries, Map.of("failure", failure.name(), "objects", generationCapture.used(),
                "request", TopologyValidationAccess.requestIdentity(generationConsumer)));
        generationCapture = null; TopologyValidationAccess.forgetRequest(generationConsumer); generationConsumer = null;
        if (++generationQueries < 2) { stage = 1; return false; }
        evidence.put("recoveryAttempted", true); evidence.put("loadIdentity", load); evidence.put("scanCounts", sectionProbe.scans());
        evidence.put("sectionPublications", sectionProbe.publications()); return true;
    }

    private static Map<Integer, Byte> oracleCells(ChunkAccess chunk, List<BlockPos> positions) {
        Map<Integer, Byte> cells = new LinkedHashMap<>();
        for (BlockPos pos : positions) cells.put((pos.getX() & 15) | (pos.getZ() & 15) << 4 | (pos.getY() & 15) << 8,
                (byte) TopologyGenerationProbe.independentFlags(chunk, pos));
        return cells;
    }
    private static Map<Integer, Byte> changedCells(Map<Integer, Byte> before, Map<Integer, Byte> after) {
        Map<Integer, Byte> result = new LinkedHashMap<>(after); result.entrySet().removeIf(entry -> entry.getValue().equals(before.get(entry.getKey())));
        return Map.copyOf(result);
    }
    private static void verifyTickDeltas(List<TopologySectionEventProbe.Publication> deltas, Map<Integer, Byte> first, Map<Integer, Byte> second) {
        require(deltas.size() == 2, "exactly one server delta per changed tick");
        var a = deltas.get(0); var b = deltas.get(1);
        require(a.load() == b.load() && a.version() == a.previousVersion() + 1 && b.previousVersion() == a.version()
                && b.version() == b.previousVersion() + 1, "server deltas preserve a continuous load/version chain");
        require(a.changes().equals(first) && b.changes().equals(second), "server deltas contain precisely the final changed cells");
        require(a.factsToken() == 0 && b.factsToken() == 0, "server changes transfer deltas without forming a second complete facts object");
    }
    private boolean targetSettled() {
        Object loaded = sectionProbe.loaded(topology);
        return loaded != null && !(boolean) TopologyTestBridge.readField(loaded, "readInFlight")
                && !(boolean) TopologyTestBridge.readField(loaded, "persistencePending")
                && ((Map<?, ?>) TopologyTestBridge.readField(loaded, "pendingChanges")).isEmpty()
                && Boolean.TRUE.equals(TopologyValidationAccess.chunkSettlement(topology, targetLevel.dimension(), Set.of(targetChunk.toLong())).get("settled"));
    }
    private void comparePublishedSection(ChunkAccess chunk, int sectionY) {
        sectionProbe.comparePublishedSection(topology, chunk, sectionY);
    }

    private static ProtoChunk proto(ServerLevel level, boolean direct, ChunkPos pos) {
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        return direct ? new ProtoChunk(pos, UpgradeData.EMPTY, null, new ProtoChunkTicks<>(), new ProtoChunkTicks<>(), level, biomes, null)
                : new ProtoChunk(pos, UpgradeData.EMPTY, level, biomes, null);
    }

    private boolean handoff() {
        if (handoff == null) {
            handoff = new TopologyHandoffScenario(topology, server.overworld(), current, options.control());
            targetLevel = server.overworld(); targetChunk = handoff.chunkPosition();
        }
        Map<String, Object> result = handoff.tick();
        if (result == null) return false;
        evidence.putAll(result); return true;
    }
    private boolean handoffFailure() {
        if (handoffFailure == null) {
            handoffFailure = new TopologyHandoffFailureScenario(topology, server.overworld(), current, options.control());
            targetLevel = server.overworld(); targetChunk = handoffFailure.chunkPosition();
        }
        Map<String, Object> result = handoffFailure.tick();
        if (result == null) return false;
        evidence.putAll(result); return true;
    }
    private boolean endpointFailure() {
        if (endpointFailure == null) {
            endpointFailure = new TopologyEndpointFailureScenario(topology, server.overworld(), current, options.control());
            targetLevel = server.overworld(); cleanupChunks = endpointFailure.chunkPositions();
        }
        Map<String, Object> result = endpointFailure.tick();
        if (result == null) return false;
        evidence.putAll(result); return true;
    }
    private boolean mixedFailure() {
        if (mixedFailure == null) {
            mixedFailure = new TopologyMixedFailureScenario(topology, server.overworld(), current, options.control());
            targetLevel = server.overworld(); cleanupChunks = mixedFailure.chunkPositions();
        }
        Map<String, Object> result = mixedFailure.tick();
        if (result == null) return false;
        evidence.putAll(result); return true;
    }
    private boolean finalResult() {
        if (finalResult == null) {
            finalResult = new TopologyFinalResultScenario(topology, server.overworld(), current, options.control());
            targetLevel = server.overworld(); cleanupChunks = finalResult.chunkPositions();
        }
        Map<String, Object> result = finalResult.tick();
        if (result == null) return false;
        evidence.putAll(result); return true;
    }
    private boolean requestLifecycle() {
        if (requestLifecycle == null) {
            requestLifecycle = new TopologyRequestLifecycleScenario(topology, server.overworld(), current, options.control());
            targetLevel = server.overworld(); targetChunk = requestLifecycle.chunkPosition();
        }
        Map<String, Object> result = requestLifecycle.tick();
        if (result == null) return false;
        evidence.putAll(result); return true;
    }
    private boolean connection() {
        if (connection == null) {
            connection = new TopologyConnectionScenario(topology, server.overworld(), current, options.control());
            targetLevel = server.overworld(); cleanupChunks = connection.chunkPositions();
        }
        Map<String, Object> result = connection.tick();
        if (result == null) return false;
        evidence.putAll(result); return true;
    }
    private boolean derivedLifecycle() {
        if (derivedLifecycle == null) {
            derivedLifecycle = new TopologyDerivedLifecycleScenario(topology, server.overworld(), current, options.control());
            targetLevel = server.overworld(); cleanupChunks = derivedLifecycle.chunkPositions();
        }
        Map<String, Object> result = derivedLifecycle.tick();
        if (result == null) return false;
        evidence.putAll(result); return true;
    }
    private boolean prewarm() {
        if (prewarm == null) {
            prewarm = new TopologyPrewarmScenario(topology, server.overworld(), current, options.control());
            targetLevel = server.overworld(); cleanupChunks = prewarm.chunkPositions();
        }
        Map<String, Object> result = prewarm.tick();
        if (result == null) return false;
        evidence.putAll(result); return true;
    }
    private boolean shutdown() {
        if (shutdown == null) {
            shutdown = new TopologyShutdownScenario(topology, server.overworld(), current, options.control());
            targetLevel = server.overworld(); cleanupChunks = shutdown.chunkPositions();
        }
        if (shutdown.prepare()) { requestedStop = true; server.halt(false); }
        return false;
    }
    private boolean dependencyLimit() {
        if (dependencyLimit == null) {
            dependencyLimit = new TopologyDependencyLimitScenario(topology, server.overworld(), current, options.control());
            targetLevel = server.overworld(); cleanupChunks = dependencyLimit.chunkPositions();
        }
        Map<String, Object> result = dependencyLimit.tick();
        if (result == null) return false;
        evidence.putAll(result); return true;
    }

    private boolean threading() {
        if (threading == null) {
            threading = new com.scarasol.acceleratednavigation.topology.TopologyThreadingScenario(topology, server.overworld(), current, options.control());
            targetLevel = server.overworld(); cleanupChunks = threading.chunkPositions();
        }
        var result = threading.tick();
        if (result == null) return false;
        evidence.putAll(result); return true;
    }
    private static TopologyService.ChunkFactsState state(ChunkAccess chunk) { return ((TopologyService.ChunkFactsCarrier) chunk).acceleratedNavigation$factsState(); }
    private static Object boundOwner(ChunkAccess chunk, int sectionY) { return TopologyTestBridge.readField(chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY)), "acceleratedNavigation$owner"); }
    private static int flag(ChunkAccess chunk, BlockPos pos) {
        synchronized (state(chunk)) {
            byte[] cells = (byte[]) ((Map<?, ?>) TopologyTestBridge.readField(state(chunk), "generatedCells")).get(pos.getY() >> 4);
            return cells == null ? 1 : cells[(pos.getX() & 15) | (pos.getZ() & 15) << 4 | (pos.getY() & 15) << 8] & 15;
        }
    }
    private String parameter(String name) { return current.parameters().get(name); }
    private int integer(String name) { return Integer.parseInt(parameter(name)); }
    private static void require(boolean condition, String relationship) { if (!condition) throw new AssertionError(relationship); }

    private void detectMutation(boolean corruptedRelationship, String relationship) {
        boolean rejected = false;
        try { require(corruptedRelationship, relationship); }
        catch (AssertionError expected) { rejected = true; }
        require(rejected, "fixture failed to reject its deliberate mutation: " + relationship);
        evidence.put("mutationDetected", true); evidence.put("mutatedRelationship", relationship);
    }

    private static final class NotEntered extends RuntimeException {
        private NotEntered(String entry) { super(entry); }
    }

    private void complete(String outcome, String reason, boolean reached) {
        completedOutcome = outcome; completedReason = reason; completedReached = reached;
        cleanupStarted = System.nanoTime();
        if (generationCapture != null) { TopologyValidationAccess.endCapture(generationCapture); generationCapture = null; }
        if (generationConsumer != null) { generationConsumer.cancel(); TopologyValidationAccess.forgetRequest(generationConsumer); generationConsumer = null; }
        if (sectionProbe != null) { sectionProbe.close(); sectionProbe = null; }
        if (handoff != null) { handoff.close(); handoff = null; }
        if (handoffFailure != null) {
            handoffFailure.close(); pendingFaultEdit = handoffFailure.pendingDiskEdit(); handoffFailure = null;
        }
        if (endpointFailure != null) {
            endpointFailure.close(); pendingFaultEdit = endpointFailure.pendingDiskEdit(); endpointFailure = null;
        }
        if (mixedFailure != null) {
            mixedFailure.close(); pendingFaultEdit = mixedFailure.pendingDiskEdit(); mixedFailure = null;
        }
        if (finalResult != null) {
            finalResult.close(); pendingFaultEdit = finalResult.pendingDiskEdit(); finalResult = null;
        }
        if (requestLifecycle != null) { requestLifecycle.close(); requestLifecycle = null; }
        if (connection != null) { connection.close(); connection = null; }
        if (derivedLifecycle != null) { derivedLifecycle.close(); pendingFaultEdit = derivedLifecycle.pendingDiskEdit(); derivedLifecycle = null; }
        if (prewarm != null) { prewarm.close(); prewarm = null; }
        if (shutdown != null) { shutdown.close(); shutdown = null; }
        if (dependencyLimit != null) { dependencyLimit.close(); dependencyLimit = null; }
        if (threading != null) { threading.close(); threading = null; }
        if (tracked != null) { TopologyService.onChunkUnloaded(targetLevel, tracked); tracked = null; }
    }

    private void settle() {
        Set<Long> chunks = cleanupChunks.isEmpty() && targetChunk != null ? Set.of(targetChunk.toLong()) : cleanupChunks;
        Map<String, Object> cleanup = chunks.isEmpty() ? Map.of("settled", true)
                : TopologyValidationAccess.chunkSettlement(topology, targetLevel.dimension(), chunks);
        boolean faultEditSettled = pendingFaultEdit == null || pendingFaultEdit.isDone();
        boolean settled = Boolean.TRUE.equals(cleanup.get("settled")) && faultEditSettled;
        if (!settled && System.nanoTime() - cleanupStarted < 30_000_000_000L) return;
        evidence.put("cleanup", cleanup); evidence.put("faultEditSettled", faultEditSettled); evidence.put("cleaned", settled);
        evidence.put("outcome", settled ? completedOutcome : "FAIL");
        evidence.put("reason", settled ? completedReason : "CLEANUP_TIMEOUT");
        evidence.put("originalOutcome", completedOutcome); evidence.put("reached", completedReached);
        journal.control(current, evidence); current = null; completedOutcome = null; pendingFaultEdit = null;
        if (!settled) { requestedStop = true; server.halt(false); }
    }
    @Override public void failAndStop(RuntimeException failure) {
        summary.put("failure", TerrainTestSupport.failureSummary(failure));
        if (current != null) complete("FAIL", "HARNESS_EXCEPTION", false);
        requestedStop = true; server.halt(false);
    }
    @Override public void serverStopping() {
        if (shutdown == null) return;
        try { shutdown.serverStopping(); }
        catch (RuntimeException | AssertionError failure) { evidence.putAll(shutdown.failure(failure)); shutdown.close(); }
    }
    @Override public void serverStopped() {
        if (shutdown != null && current != null) {
            try { if (!"FAIL".equals(evidence.get("outcome"))) evidence.putAll(shutdown.serverStopped()); }
            catch (RuntimeException | AssertionError failure) { evidence.putAll(shutdown.failure(failure)); }
            finally { shutdown.close(); shutdown = null; }
            journal.control(current, evidence); current = null;
        }
        publish(); RealTerrainTopologyBenchmark.clearController();
    }
    @Override public void recordStoppedFailure(RuntimeException failure) {
        summary.put("shutdownFailure", TerrainTestSupport.failureSummary(failure)); publish(); RealTerrainTopologyBenchmark.clearController();
    }
    private void publish() {
        if (published) return;
        published = true;
        if (current != null) {
            evidence.put("outcome", "NOT_ENTERED"); evidence.put("reason", "SHUTDOWN_BEFORE_CLEANUP_PROOF");
            evidence.put("originalOutcome", completedOutcome); evidence.put("cleaned", false);
            evidence.put("reached", completedReached); journal.control(current, evidence);
            current = null;
        }
        summary.put("state", pending.isEmpty() && current == null ? "DATA_COMPLETE" : "DATA_PARTIAL");
        try { journal.finish(summary, "CONTROL_NOT_EXECUTED"); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
}
