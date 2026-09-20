package com.scarasol.acceleratednavigation.topology;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.SectionPos;

/** Observes captured transition arguments without replacing package-private builder return types. */
public final class TopologyBuildTransitionProbe {
    private TopologyBuildTransitionProbe() { }

    public record Input(String kind, SectionPos section, Object input, Object key, Object snapshot) { }

    public static Input captured(Object transition, Object value) {
        if (value == null || Boolean.FALSE.equals(value)) return null;
        Object key = argument(transition, "SuperCacheKey");
        if (key != null) return new Input("parent", (SectionPos) TopologyTestBridge.invoke(key, "origin"), value, key, value);
        key = argument(transition, "BaseBoundaryCacheKey");
        if (key != null) {
            BaseClusterTopology source = (BaseClusterTopology) TopologyTestBridge.invoke(key, "source");
            return new Input("base-boundary", source.section(), source, key, value);
        }
        key = argument(transition, "SuperBoundaryCacheKey");
        if (key == null) throw new IllegalStateException("Unrecognized build transition capture");
        Object source = TopologyTestBridge.invoke(key, "source");
        return new Input("parent-boundary", (SectionPos) TopologyTestBridge.invoke(source, "origin"), source, key, value);
    }

    public static Object result(Object transition, Input input) {
        return argument(transition, switch (input.kind()) {
            case "parent" -> "SuperClusterTopology";
            case "base-boundary" -> "BoundaryLinks";
            case "parent-boundary" -> "CrossingIndex";
            default -> throw new IllegalArgumentException(input.kind());
        });
    }

    public static Map<String, Object> details(Object runtime, Input input, Object transition, Object result) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskToken", currentTask(runtime));
        row.put("buildKind", input.kind()); row.put("section", input.section());
        row.put("key", input.key().toString()); row.put("inputToken", TopologyValidationAccess.token(input.input()));
        row.put("resultToken", TopologyValidationAccess.token(result)); row.put("completed", result != null);
        if (input.kind().equals("parent")) row.put("children", children((BaseClusterTopology[]) input.snapshot()));
        else if (input.kind().equals("base-boundary")) row.put("children", children(new BaseClusterTopology[]{
                (BaseClusterTopology) input.input(), (BaseClusterTopology) TopologyTestBridge.invoke(input.key(), "target")}));
        else {
            row.put("sourceChildren", children((BaseClusterTopology[]) TopologyTestBridge.invoke(input.snapshot(), "sourceChildren")));
            row.put("targetChildren", children((BaseClusterTopology[]) TopologyTestBridge.invoke(input.snapshot(), "targetChildren")));
        }
        for (Field field : transition.getClass().getDeclaredFields()) {
            if (!Throwable.class.isAssignableFrom(field.getType())) continue;
            try { field.setAccessible(true); row.put("failure", String.valueOf(field.get(transition))); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }
        return row;
    }

    public static long currentTask(Object runtime) {
        Object executor = TopologyTestBridge.readField(runtime, "taskExecutor");
        return TopologyValidationAccess.token(((ThreadLocal<?>) TopologyTestBridge.readField(executor, "currentTask")).get());
    }

    private static java.util.List<Map<String, Object>> children(BaseClusterTopology[] children) {
        return java.util.Arrays.stream(children).map(child -> Map.<String, Object>of("section", child.section(),
                "revision", child.revision(), "fingerprint", child.sourceFingerprint(), "token", TopologyValidationAccess.token(child))).toList();
    }

    private static Object argument(Object transition, String type) {
        Object found = null;
        for (Field field : transition.getClass().getDeclaredFields()) {
            if (!field.getType().getSimpleName().equals(type)) continue;
            if (found != null) throw new IllegalStateException("Ambiguous build capture: " + type);
            try { field.setAccessible(true); found = field.get(transition); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }
        return found;
    }
}
