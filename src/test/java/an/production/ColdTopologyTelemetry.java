package an.production;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Inclusive intervals for diagnosis; overlapping intervals are never summed as latency. */
public final class ColdTopologyTelemetry {
    public static final boolean ENABLED=Boolean.getBoolean("an.production.coldStages");
    private static final List<Map<String,Object>> EVENTS=new ArrayList<>();
    private static final List<String> ERRORS=new ArrayList<>();
    private static final ThreadLocal<ArrayDeque<Start>> STACK=ThreadLocal.withInitial(ArrayDeque::new);
    private static int open;
    private static boolean truncated;
    private record Start(String name,long nanos) {}
    public static void begin(String name) {
        if(!ENABLED)return;
        STACK.get().push(new Start(name,System.nanoTime()));
        synchronized(EVENTS){open++;}
    }
    public static void end(String name,Object result) {
        if(!ENABLED)return;
        long now=System.nanoTime();
        var start=STACK.get().pop();
        if(!start.name().equals(name))throw new IllegalStateException("phase nesting "+name);
        var row=new LinkedHashMap<String,Object>();
        row.put("phase",name);row.put("start",start.nanos());row.put("end",now);
        row.put("thread",Thread.currentThread().getName());
        if(result!=null&&name.equals("base")) {
            for(String method:List.of("section","geometry","sourceFingerprint","signature")) {
                try {
                    Method getter=result.getClass().getDeclaredMethod(method);getter.setAccessible(true);
                    row.put(method,String.valueOf(getter.invoke(result)));
                } catch(ReflectiveOperationException error){
                    synchronized(EVENTS){ERRORS.add(error.toString());}
                }
            }
        }
        synchronized(EVENTS){open--;add(row);}
    }
    public static void mark(String name,Object detail) {
        if(!ENABLED)return;
        synchronized(EVENTS){add(Map.of("phase",name,"start",System.nanoTime(),
                "thread",Thread.currentThread().getName(),"detail",String.valueOf(detail)));}
    }
    public static void queue(Object task) {
        if(!ENABLED||task==null)return;
        try {
            var enqueued=task.getClass().getDeclaredField("enqueuedNanos");enqueued.setAccessible(true);
            var kind=task.getClass().getDeclaredField("kind");kind.setAccessible(true);
            long now=System.nanoTime(),at=enqueued.getLong(task);
            synchronized(EVENTS){add(Map.of("phase","worker-queue","start",at,"end",now,
                    "thread",Thread.currentThread().getName(),"kind",String.valueOf(kind.get(task))));}
        } catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
    }
    private static void add(Map<String,Object> row) {
        if(EVENTS.size()<100_000)EVENTS.add(row);else truncated=true;
    }
    public static Map<String,Object> snapshot() {
        synchronized(EVENTS){return Map.of("events",List.copyOf(EVENTS),"openIntervals",open,
                "truncated",truncated,"diagnostic",ENABLED,"errors",List.copyOf(ERRORS));}
    }
    private ColdTopologyTelemetry() {}
}
