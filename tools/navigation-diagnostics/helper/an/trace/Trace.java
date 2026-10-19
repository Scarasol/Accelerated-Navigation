package an.trace;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Diagnostic-only primitive buffers. No event objects or output on the measured path. */
public final class Trace {
    public static volatile int trial;
    public static volatile int tick;
    public static final boolean ENABLED = !Boolean.getBoolean("an.trace.disabled");
    private static final boolean CLOSURE = Boolean.getBoolean("an.trace.closure");
    private static final int CAPACITY = Integer.getInteger("an.trace.capacity", 350_000);
    private static final com.sun.management.ThreadMXBean MEMORY =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final List<Buffer> BUFFERS = new CopyOnWriteArrayList<>();
    private static final ThreadLocal<Buffer> LOCAL = ThreadLocal.withInitial(() -> {
        Buffer buffer = new Buffer(); BUFFERS.add(buffer); return buffer;
    });
    public static final List<String> PHASES = new ArrayList<>(List.of("workerRun", "workerEnqueue", "workerSelected",
            "serverEnqueue", "actorBind", "searchParameters", "sharedParameters"));
    private static final ClassValue<Map<String, Field>> FIELDS = new ClassValue<>() {
        protected Map<String, Field> computeValue(Class<?> type) {
            Map<String, Field> result = new HashMap<>();
            for (Class<?> at = type; at != null; at = at.getSuperclass())
                for (Field field : at.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) && field.trySetAccessible())
                        result.putIfAbsent(field.getName(), field);
                }
            return result;
        }
    };
    static {
        if (!MEMORY.isThreadAllocatedMemoryEnabled()) MEMORY.setThreadAllocatedMemoryEnabled(true);
        if (CLOSURE && MEMORY.isCurrentThreadCpuTimeSupported() && !MEMORY.isThreadCpuTimeEnabled())
            MEMORY.setThreadCpuTimeEnabled(true);
    }
    private static final class Buffer {
        final Thread thread = Thread.currentThread();
        final int[] phase = new int[CAPACITY], trials = new int[CAPACITY], ticks = new int[CAPACITY],
                depths = new int[CAPACITY], flags = new int[CAPACITY];
        final long[] start = new long[CAPACITY], end = new long[CAPACITY],
                exclusive = new long[CAPACITY], bytes = new long[CAPACITY], exclusiveBytes = new long[CAPACITY],
                detail = new long[CAPACITY], detail2 = new long[CAPACITY], workState = new long[CAPACITY];
        final Object[] self = new Object[CAPACITY], a = new Object[CAPACITY],
                b = new Object[CAPACITY], result = new Object[CAPACITY];
        final int[] stack = new int[256];
        final long[] childTime = new long[256], childBytes = new long[256], allocated = new long[256];
        final long[] cpu = new long[CLOSURE ? CAPACITY : 0], exclusiveCpu = new long[CLOSURE ? CAPACITY : 0];
        final long[] enteredCpu = new long[256], childCpu = new long[256];
        int size, depth, suppressed;
        long errors, dropped;
    }
    public static void warm() { LOCAL.get(); }
    public static void warmClass(Class<?> type) { FIELDS.get(type); }
    @jdk.jfr.Name("an.trace.Clock")
    @jdk.jfr.Label("Navigation diagnostic clock")
    public static final class Clock extends jdk.jfr.Event {
        public long nanoTime;
        public int trial;
    }
    public static void clock() {
        Clock event = new Clock();event.begin();event.nanoTime=System.nanoTime();event.trial=trial;event.commit();
    }
    private static long allocated() { return MEMORY.getCurrentThreadAllocatedBytes(); }
    public static Object field(Object object, String name) {
        if (object == null) return null;
        try {
            Field field = FIELDS.get(object.getClass()).get(name);
            return field == null ? null : field.get(object);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private static long number(Object object, String name) {
        if (object == null) return 0;
        try {
            Field value = FIELDS.get(object.getClass()).get(name);
            return value == null ? 0 : value.getLong(object);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    public static void enter(int phase, Object self, Object a, Object b) {
        if (!ENABLED) return;
        Buffer buffer = LOCAL.get();
        if (buffer.suppressed != 0) { buffer.suppressed++; buffer.dropped++; return; }
        if (trial == 0 && buffer.depth == 0) return;
        if (buffer.depth == buffer.stack.length || buffer.size == CAPACITY) {
            buffer.dropped++; buffer.suppressed++; return;
        }
        int i = buffer.size++, d = buffer.depth++;
        buffer.stack[d] = i; buffer.childTime[d] = buffer.childBytes[d] = 0;
        buffer.phase[i] = phase; buffer.trials[i] = trial; buffer.ticks[i] = tick; buffer.depths[i] = d;
        buffer.self[i] = self; buffer.a[i] = a; buffer.b[i] = b;
        if (CLOSURE && a == null && b == null && self != null
                && self.getClass().getName().endsWith("TopologyWorkerRuntime$MacroQuery")) {
            buffer.a[i] = field(self, "baseGraph");
            buffer.b[i] = field(self, "superGraph");
        }
        Object work = isWork(self) ? self : isWork(a) ? a : isWork(b) ? b : null;
        if (work != null) {
            buffer.detail[i] = number(work, "segmentPublication");
            buffer.detail2[i] = number(work, "cursor") | (number(work, "stageEnd") << 16)
                    | (number(work, "attempt") << 32) | (Boolean.TRUE.equals(field(work, "last")) ? 1L << 48 : 0);
            buffer.workState[i] = number(work,"stride") | (number(work,"recoveryCount") << 16)
                    | (number(work,"refreshes") << 32)
                    | (Boolean.TRUE.equals(field(work,"nativeStopped")) ? 1L << 48 : 0);
        }
        buffer.allocated[d] = allocated();
        if (CLOSURE) {
            buffer.enteredCpu[d] = MEMORY.getCurrentThreadCpuTime();
            buffer.childCpu[d] = 0;
        }
        buffer.start[i] = System.nanoTime();
    }
    private static boolean isWork(Object object) {
        return object != null && object.getClass().getName().endsWith("SegmentedNavigationService$Work");
    }
    public static void exit(int phase, Object result, boolean completed) {
        if (!ENABLED) return;
        long end = System.nanoTime(), bytes = allocated();
        Buffer buffer = LOCAL.get();
        if (buffer.suppressed != 0) { buffer.suppressed--; return; }
        if (buffer.depth == 0) return;
        int d = --buffer.depth, i = buffer.stack[d];
        if (buffer.phase[i] != phase) { buffer.errors++; buffer.depth=0; return; }
        long elapsed = end - buffer.start[i], allocation = bytes - buffer.allocated[d];
        buffer.end[i] = end; buffer.bytes[i] = allocation; buffer.result[i] = result;
        buffer.exclusive[i] = elapsed - buffer.childTime[d];
        buffer.exclusiveBytes[i] = allocation - buffer.childBytes[d];
        if (CLOSURE) {
            long cpu = MEMORY.getCurrentThreadCpuTime() - buffer.enteredCpu[d];
            buffer.cpu[i] = cpu;
            buffer.exclusiveCpu[i] = cpu - buffer.childCpu[d];
            if (d != 0) buffer.childCpu[d - 1] += cpu;
        }
        buffer.flags[i] = completed ? 0 : 1;
        if (d != 0) { buffer.childTime[d - 1] += elapsed; buffer.childBytes[d - 1] += allocation; }
    }
    public static void point(int phase, Object self, Object a, Object b, long detail, long detail2) {
        if (!ENABLED || trial == 0) return;
        enter(phase, self, a, b);
        Buffer buffer = LOCAL.get();
        if (buffer.suppressed != 0) { exit(phase,null,true); return; }
        int i = buffer.stack[buffer.depth - 1];
        buffer.detail[i] = detail; buffer.detail2[i] = detail2;
        exit(phase, null, true);
    }
    public static void enqueued(Object task) {
        if (!ENABLED || trial == 0 || task == null) return;
        point(1, task, field(task, "command"), field(task, "kind"), number(task, "sequence"), number(task, "enqueuedNanos"));
    }
    public static void selected(Object task) {
        if (!ENABLED || trial == 0 || task == null) return;
        point(2, task, field(task, "command"), field(task, "kind"), number(task, "sequence"), 0);
    }
    public static void run(Runnable command, Object task) {
        if (!ENABLED || trial == 0) { command.run(); return; }
        enter(0, task, command, field(task, "kind"));
        if (ENABLED && LOCAL.get().suppressed==0 && LOCAL.get().depth != 0)
            LOCAL.get().detail[LOCAL.get().stack[LOCAL.get().depth - 1]] = number(task, "sequence");
        boolean completed = false;
        try { command.run(); completed = true; }
        finally { exit(0, null, completed); }
    }
    public static void serverQueued(Object command) { point(3, command, null, null, 0, 0); }
    public static void bind(Object navigation, Object request, Object actor) { point(4, navigation, request, actor, 0, 0); }
    public static void search(Object request, Object targets, int accuracy, float range) {
        point(5, request, targets, null, accuracy, Float.floatToRawIntBits(range));
    }
    public static void qualification(Object profile,Object start,Object goal,int accuracy) {
        point(6,profile,start,goal,accuracy,0);
    }
    public static synchronized void dump(Path directory) throws IOException {
        IdentityHashMap<Object, Integer> ids = new IdentityHashMap<>();
        List<Object> objects = new ArrayList<>();
        try (var out = Files.newBufferedWriter(directory.resolve("events.tsv"), StandardOpenOption.CREATE_NEW)) {
            out.write("thread\ttrial\ttick\tphase\tdepth\tstart\tend\texclusive\tbytes\texclusiveBytes\tself\ta\tb\tresult\tdetail\tdetail2\tfailed\tworkState"
                    +(CLOSURE ? "\tcpu\texclusiveCpu" : "")+"\n");
            for (Buffer buffer : BUFFERS) for (int i = 0; i < buffer.size; i++) {
                if (buffer.trials[i] == 0) continue;
                out.write(buffer.thread.getId()+"\t"+buffer.trials[i]+"\t"+buffer.ticks[i]+"\t"+buffer.phase[i]+"\t"+
                        buffer.depths[i]+"\t"+buffer.start[i]+"\t"+buffer.end[i]+"\t"+buffer.exclusive[i]+"\t"+
                        buffer.bytes[i]+"\t"+buffer.exclusiveBytes[i]+"\t"+id(ids,objects,buffer.self[i])+"\t"+
                        id(ids,objects,buffer.a[i])+"\t"+id(ids,objects,buffer.b[i])+"\t"+id(ids,objects,buffer.result[i])+"\t"+
                                buffer.detail[i]+"\t"+buffer.detail2[i]+"\t"+buffer.flags[i]+"\t"+buffer.workState[i]
                                +(CLOSURE ? "\t"+buffer.cpu[i]+"\t"+buffer.exclusiveCpu[i] : "")+"\n");
            }
        }
        try (var out = Files.newBufferedWriter(directory.resolve("objects.tsv"), StandardOpenOption.CREATE_NEW)) {
            out.write("id\tclass\tfield\tkind\tvalue\n");
            for (int i = 0; i < objects.size(); i++) {
                Object object = objects.get(i); Class<?> type = object.getClass();
                int id = i + 1;
                out.write(id+"\t"+clean(type.getName())+"\t\tlabel\t"+clean(label(object))+"\n");
                if (CLOSURE && object instanceof Collection<?> collection && collection.size() <= 512) {
                    int index=0;
                    for (Object value : collection) if (value != null)
                        out.write(id+"\t"+clean(type.getName())+"\titem"+index+++"\tref\t"+id(ids,objects,value)+"\n");
                }
                if (CLOSURE && object instanceof Object[] array && array.length <= 512)
                    for (int index=0; index<array.length; index++) if (array[index] != null)
                        out.write(id+"\t"+clean(type.getName())+"\titem"+index+"\tref\t"+id(ids,objects,array[index])+"\n");
                if (!type.getName().startsWith("com.scarasol.acceleratednavigation.")
                        && !type.getName().startsWith("an.production.")) continue;
                for (Field field : FIELDS.get(type).values()) {
                    if (field.getName().equals("this$0") || field.getName().equals("level")
                            || field.getName().equals("server") || field.getName().equals("topology") && !CLOSURE
                            || field.getType().isArray() && (!CLOSURE || field.getType().getComponentType().isPrimitive())) continue;
                    try {
                        Object value = field.get(object);
                        if (value == null) continue;
                        boolean scalar = field.getType().isPrimitive() || value instanceof String
                                || value instanceof UUID || value instanceof Enum<?>;
                        if (!scalar && !(value.getClass().getName().startsWith("com.scarasol.acceleratednavigation.")
                                || field.getName().startsWith("arg$") || field.getName().equals("targets")
                                || CLOSURE && closureValue(value))) continue;
                        out.write(id+"\t"+clean(type.getName())+"\t"+field.getName()+"\t"+(scalar?"scalar":"ref")+"\t"+
                                (scalar?clean(String.valueOf(value)):id(ids,objects,value))+"\n");
                    } catch (ReflectiveOperationException error) { throw new IOException(error); }
                }
            }
        }
        try (var out = Files.newBufferedWriter(directory.resolve("trace-status.tsv"), StandardOpenOption.CREATE_NEW)) {
            out.write("thread\tname\tevents\tdepth\terrors\tdropped\n");
            for (Buffer buffer : BUFFERS) out.write(buffer.thread.getId()+"\t"+clean(buffer.thread.getName())+"\t"+
                    buffer.size+"\t"+(buffer.depth+buffer.suppressed)+"\t"+buffer.errors+"\t"+buffer.dropped+"\n");
        }
        Files.write(directory.resolve("phases.txt"), PHASES, StandardOpenOption.CREATE_NEW);
    }
    private static int id(IdentityHashMap<Object,Integer> ids, List<Object> objects, Object value) {
        if (value == null) return 0;
        Integer existing = ids.get(value);
        if (existing != null) return existing;
        int id = objects.size() + 1; objects.add(value); ids.put(value,id); return id;
    }
    private static boolean closureValue(Object value) {
        String name=value.getClass().getName();
        return name.equals("net.minecraft.core.BlockPos") || name.equals("net.minecraft.core.SectionPos")
                || value instanceof Collection<?> collection && collection.size() <= 512
                || value instanceof Object[] array && array.length <= 512;
    }
    private static String label(Object value) {
        String name = value.getClass().getName();
        if (value instanceof UUID || value instanceof Enum<?> || value instanceof Boolean || value instanceof Number || name.equals("net.minecraft.core.BlockPos")
                || name.equals("net.minecraft.core.SectionPos") || value instanceof Collection<?> c && c.size() <= 16)
            return value.toString();
        return "";
    }
    private static String clean(String value) { return value.replace('\t',' ').replace('\r',' ').replace('\n',' '); }
    private Trace() {}
}
