package an.budget;

import com.scarasol.acceleratednavigation.mixin.NavigationAccess;
import com.scarasol.acceleratednavigation.navigation.NativeQueryProof;
import com.scarasol.acceleratednavigation.navigation.NavigationRequestPath;
import com.scarasol.acceleratednavigation.topology.GroundSegmentWindow;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/** Limited diagnostic records for the four previously identified routes. */
public final class HandoffTrace {
    private static final boolean ENABLED = Boolean.getBoolean("an.budget.trace");
    private static final Map<Class<?>, Map<String, Field>> FIELDS = new HashMap<>();
    private static final Map<Object, int[]> WATCHED = new IdentityHashMap<>();
    private HandoffTrace() {}

    private static Object field(Object owner, String name) {
        if (owner == null) return null;
        try {
            Map<String, Field> fields = FIELDS.computeIfAbsent(owner.getClass(), ignored -> new HashMap<>());
            Field field = fields.get(name);
            if (field == null) {
                field = owner.getClass().getDeclaredField(name);
                field.setAccessible(true);
                fields.put(name, field);
            }
            return field.get(owner);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(name, failure); }
    }

    private static int[] point(BlockPos p) {
        return p == null ? null : new int[] { p.getX(), p.getY(), p.getZ() };
    }
    private static double[] vector(Vec3 p) { return new double[] { p.x, p.y, p.z }; }

    public static void watch(Object work) {
        if (!ENABLED) return;
        NavigationRequestPath request = (NavigationRequestPath)field(work, "request");
        BlockPos initial = request.mob.blockPosition();
        if (initial.getX() == -17 && Set.of(-137, 23, -128, 32).contains(initial.getZ()))
            WATCHED.put(work, point(initial));
    }

    public static void observe(String event) {
        if (!ENABLED) return;
        for (Object work : new ArrayList<>(WATCHED.keySet())) {
            NavigationRequestPath request = (NavigationRequestPath)field(work, "request");
            Path path = ((NavigationAccess)request.navigation).acceleratedNavigation$path();
            if (!(boolean)field(work, "last") && path != null && path.getNodeCount() > 0
                    && path.getNextNodeIndex() >= path.getNodeCount() - 1) event(work, event, null);
        }
    }

    public static void nativeStop(Object service, PathNavigation navigation, String event) {
        if (!ENABLED) return;
        Object work = ((Map<?, ?>)field(service, "active")).get(navigation);
        event(work, event, null);
    }

    public static void event(Object work, String event, Object result) {
        if (!ENABLED || !WATCHED.containsKey(work)) return;
        NavigationRequestPath request = (NavigationRequestPath)field(work, "request");
        var mob = request.mob;
        var state = BudgetPrototype.state(((ServerLevel)mob.level()).getServer());
        if (state == null) return;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sequence", state.handoffs.size() + 1);
        row.put("event", event);
        row.put("result", result);
        row.put("actor", mob.getUUID().toString());
        row.put("query", request.queryId.toString());
        row.put("initial", WATCHED.get(work));
        row.put("finalTarget", point(request.getTarget()));
        row.put("tick", mob.level().getGameTime());
        row.put("tickOffsetNanos", state.inTick ? System.nanoTime() - state.started : -1);
        row.put("position", vector(mob.position()));
        row.put("block", point(mob.blockPosition()));
        row.put("velocity", vector(mob.getDeltaMovement()));
        row.put("onGround", mob.onGround());
        row.put("requestState", request.state.name());
        for (String name : List.of("cursor", "stageEnd", "stride", "attempt", "refreshes", "searches",
                "recoveryCount", "stable", "segmentPublication", "doneSince", "last", "queued",
                "preparing", "running", "nativeStopped", "segmentReachable"))
            row.put(name, field(work, name));
        List<?> edges = (List<?>)field(work, "edges");
        row.put("edgeCount", edges.size());
        row.put("lastNode", point((BlockPos)field(work, "lastNode")));
        row.put("avoided", ((Set<BlockPos>)field(work, "avoided")).stream().map(HandoffTrace::point).toList());
        row.put("failedTargets", ((Set<BlockPos>)field(work, "failedTargets")).stream().map(HandoffTrace::point).toList());
        Path current = ((NavigationAccess)request.navigation).acceleratedNavigation$path();
        row.put("hasPath", current != null);
        if (current != null) {
            row.put("pathIsRequest", current == request);
            row.put("pathDone", current.isDone());
            row.put("nodeIndex", current.getNextNodeIndex());
            row.put("nodeCount", current.getNodeCount());
            row.put("pathEnd", current.getEndNode() == null ? null : point(current.getEndNode().asBlockPos()));
        }
        GroundSegmentWindow window = (GroundSegmentWindow)field(work, "window");
        NativeQueryProof proof = (NativeQueryProof)field(work, "nativeProof");
        row.put("windowPresent", window != null);
        row.put("windowCurrent", window != null && window.current());
        row.put("containsCurrentBlock", window != null && window.contains(mob.blockPosition()));
        row.put("proofPresent", proof != null);
        row.put("proofCurrent", proof != null && proof.current());
        if (window != null) {
            row.put("windowSection", point(window.destination.section().origin()));
            row.put("windowRevision", window.destination.revision());
            row.put("windowComponent", window.destination.componentId());
        }
        Object entry = field(work, "segmentEntry");
        row.put("entryPresent", entry != null);
        if (entry != null) {
            Map<String, Object> record = new LinkedHashMap<>();
            for (String name : List.of("publication", "windowRevision", "destinationComponent", "tick", "nodeIndex"))
                record.put(name, field(entry, name));
            record.put("requestSame", field(entry, "request") == request);
            record.put("proofSame", field(entry, "nativeProof") == proof);
            record.put("node", point((BlockPos)field(entry, "node")));
            record.put("block", point((BlockPos)field(entry, "blockPosition")));
            record.put("position", vector((Vec3)field(entry, "position")));
            row.put("entry", record);
        }
        if (event.equals("recover-head") || event.equals("native-stop-head"))
            row.put("stack", StackWalker.getInstance().walk(stream -> stream
                    .filter(frame -> !frame.getClassName().equals(HandoffTrace.class.getName()))
                    .limit(12).map(frame -> frame.getClassName() + "." + frame.getMethodName()
                            + ":" + frame.getLineNumber()).toList()));
        state.handoffs.add(row);
        if (event.equals("finish-return")) WATCHED.remove(work);
    }
}
