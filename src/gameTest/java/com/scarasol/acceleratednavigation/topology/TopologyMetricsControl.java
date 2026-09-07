package com.scarasol.acceleratednavigation.topology;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** The M/off fixture drops only enumerated statistics writes in the loaded test image. */
public final class TopologyMetricsControl implements IMixinConfigPlugin {
    private static final String PREFIX = "com/scarasol/acceleratednavigation/topology/";
    private static final Map<String, Set<String>> FIELDS = Map.of(
            "TopologyService", Set.of("highestMacroRequests", "highestQueuedRecoveries", "recoveredSections", "failedRecoveries",
                    "degradedRecoveryCells", "successfulMacroQueries", "failedMacroQueries", "cancelledMacroQueries",
                    "exceptionalMacroQueries", "finalValidationRejections", "successfulStaleRetries"),
            "TopologyService$MacroRequest", Set.of(),
            "TopologyWorkerRuntime", Set.of("highestLogicalRequests", "highestEndpointResolutions", "highestPhysicalSearches",
                    "highestBuildDemands", "highestDependencyConsumers", "highestLiveSearchDependencies", "highestPrewarmCandidates",
                    "highestPrewarmAdmitted", "highestActiveReferences", "highestBaseCacheEntries", "highestBaseRetainedBytes",
                    "highestSuperCacheEntries", "highestSuperRetainedBytes", "highestCorridorCacheEntries", "highestCompletedCorridorBytes",
                    "completedCacheHits", "physicalSearchesStarted", "physicalSearchesSucceeded", "physicalSearchesFailed",
                    "workerStaleResults", "automaticStaleRetries", "staleRetryExhaustions", "highestPendingEventKeys",
                    "highestEventBatchKeys", "highestPendingEventEstimatedBytes", "highestEventBatchEstimatedBytes",
                    "completedEventBatches", "failedEventBatches", "longestEventWaitNanos", "longestEventBatchNanos"),
            "TopologyStore", Set.of("highestPendingChunks", "highestQueuedTasks", "readRequests", "recordsFound", "recordsMissing",
                    "readFailures", "writeRequests", "recordsWritten", "recordsCoalesced", "writeFailures", "saveRequests", "flushes", "flushFailures"),
            "TopologyTaskExecutor", Set.of("highestQueuedByKind", "highestRunningByKind", "completedByKind", "failedByKind"));
    private static final Map<String, List<String>> WRITES = new LinkedHashMap<>();

    static boolean selected(FieldInsnNode field) {
        return field.owner.startsWith(PREFIX) && FIELDS.getOrDefault(field.owner.substring(PREFIX.length()), Set.of()).contains(field.name);
    }

    static List<String> suppress(ClassNode type) throws AnalyzerException {
        List<String> writes = new ArrayList<>();
        for (var method : type.methods) {
            if (method.name.equals("<init>")) continue;
            var interpreter = new SourceInterpreter(Opcodes.ASM9) {
                @Override public SourceValue copyOperation(AbstractInsnNode instruction, SourceValue value) { return value; }
            };
            var frames = new Analyzer<>(interpreter).analyze(type.name, method);
            AbstractInsnNode[] instructions = method.instructions.toArray();
            for (int index = 0; index < instructions.length; index++) {
                var instruction = instructions[index];
                FieldInsnNode target = null;
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD && selected(field)
                        && (field.desc.equals("I") || field.desc.equals("J"))) target = field;
                if (instruction.getOpcode() == Opcodes.LASTORE && frames[index] != null) {
                    var frame = frames[index];
                    var sources = frame.getStack(frame.getStackSize() - 3).insns;
                    if (sources.size() == 1 && sources.iterator().next() instanceof FieldInsnNode field && selected(field)) target = field;
                }
                if (target == null) continue;
                InsnList replacement = new InsnList();
                replacement.add(new InsnNode(Opcodes.POP2));
                if (instruction.getOpcode() == Opcodes.LASTORE) replacement.add(new InsnNode(Opcodes.POP2));
                else if (target.desc.equals("J")) replacement.add(new InsnNode(Opcodes.POP));
                method.instructions.insertBefore(instruction, replacement);
                method.instructions.remove(instruction);
                writes.add(method.name + method.desc + ":" + target.owner + "." + target.name);
            }
        }
        return List.copyOf(writes);
    }

    public static synchronized Map<String, List<String>> appliedWrites() { return Map.copyOf(WRITES); }
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return "M".equals(System.getProperty("acceleratedNavigation.validation.case"))
                && "off".equals(System.getProperty("acceleratedNavigation.validation.variant"));
    }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        try {
            List<String> writes = suppress(node);
            if (writes.isEmpty()) throw new IllegalStateException("No statistics writes found in " + target);
            synchronized (TopologyMetricsControl.class) { WRITES.put(target, writes); }
        } catch (AnalyzerException failure) { throw new IllegalStateException("Cannot isolate statistics writes in " + target, failure); }
    }
}
