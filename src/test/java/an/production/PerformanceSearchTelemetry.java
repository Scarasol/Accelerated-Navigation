package an.production;

import java.lang.management.ManagementFactory;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Path;

/** Test-only counters around the actual PathFinder, shared by both backends. */
public final class PerformanceSearchTelemetry {
    public static final Map<Mob, Stats> ACTORS = new IdentityHashMap<>();
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    static {
        if (THREADS.isThreadAllocatedMemorySupported()) THREADS.setThreadAllocatedMemoryEnabled(true);
        if (THREADS.isCurrentThreadCpuTimeSupported()) THREADS.setThreadCpuTimeEnabled(true);
    }
    public static final class Stats {
        public long calls, wallNanos, cpuNanos, allocatedBytes, nullPaths, partialPaths, reachablePaths;
        public int maxReturnedNodes;
    }
    public record Span(Stats stats, long wall, long cpu, long allocated) {}
    public static long allocated() {
        return THREADS.isThreadAllocatedMemoryEnabled() ? THREADS.getThreadAllocatedBytes(Thread.currentThread().getId()) : -1;
    }
    public static long cpu() { return THREADS.isCurrentThreadCpuTimeSupported() ? THREADS.getCurrentThreadCpuTime() : -1; }
    public static Span begin(Mob mob) {
        Stats stats = ACTORS.get(mob);
        return stats == null ? null : new Span(stats, System.nanoTime(), cpu(), allocated());
    }
    public static void end(Span span, Path path) {
        if (span == null) return;
        long wall = System.nanoTime() - span.wall();
        long cpu = cpu() - span.cpu();
        long bytes = allocated() - span.allocated();
        Stats stats = span.stats();
        stats.calls++; stats.wallNanos += wall; stats.cpuNanos += cpu; stats.allocatedBytes += bytes;
        if (path == null) stats.nullPaths++;
        else {
            if (path.canReach()) stats.reachablePaths++; else stats.partialPaths++;
            stats.maxReturnedNodes = Math.max(stats.maxReturnedNodes, path.getNodeCount());
        }
    }
    private PerformanceSearchTelemetry() {}
}
