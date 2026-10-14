package an.budget;

import com.scarasol.acceleratednavigation.mixin.NavigationAccess;
import com.scarasol.acceleratednavigation.navigation.NativeQueryProof;
import com.scarasol.acceleratednavigation.navigation.NavigationRequestPath;
import com.scarasol.acceleratednavigation.topology.GroundSegmentWindow;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/** One-variable experiment; the existing arrival decision runs after a bounded grace. */
public final class HandoffTiming {
    public static final String MODE = System.getProperty("an.budget.handoff", "unchanged");
    private static final Map<String, Field> FIELDS = new HashMap<>();
    private HandoffTiming() {}

    private static Object field(Object work, String name) {
        try {
            Field field = FIELDS.get(name);
            if (field == null) {
                field = work.getClass().getDeclaredField(name);
                field.setAccessible(true);
                FIELDS.put(name, field);
            }
            return field.get(work);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(name, failure); }
    }

    public static boolean defer(Object work) {
        if (MODE.equals("unchanged")) return false;
        int grace = switch (MODE) {
            case "grace1" -> 1;
            case "grace2" -> 2;
            default -> throw new IllegalArgumentException("Unknown handoff experiment: " + MODE);
        };
        if (!(boolean)field(work, "segmented") || (boolean)field(work, "last")
                || !(boolean)field(work, "segmentReachable") || (boolean)field(work, "nativeStopped")) return false;
        NavigationRequestPath request = (NavigationRequestPath)field(work, "request");
        if (!request.adopted || request.terminal()
                || ((NavigationAccess)request.navigation).acceleratedNavigation$path() != request
                || !request.isDone() || request.getNodeCount() == 0) return false;
        long doneSince = (long)field(work, "doneSince");
        long elapsed = request.mob.level().getGameTime() - doneSince;
        if (doneSince < 0 || elapsed < 0 || elapsed >= grace) return false;
        GroundSegmentWindow window = (GroundSegmentWindow)field(work, "window");
        NativeQueryProof proof = (NativeQueryProof)field(work, "nativeProof");
        if (window == null || !window.current() || proof == null || !proof.current()
                || field(work, "segmentEntry") != null || window.contains(request.mob.blockPosition())) return false;
        var state = BudgetPrototype.state(((net.minecraft.server.level.ServerLevel)request.mob.level()).getServer());
        state.handoffDeferrals++;
        HandoffTrace.event(work, MODE + "-deferred", true);
        return true;
    }
}
