package an.trace;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import jdk.jfr.consumer.*;

/** Reads only; converts JFR time with in-recording monotonic clock anchors. */
public final class JfrExtract {
    record Anchor(Instant instant,long nanos,long width) {}
    static String clean(String value) { return value.replace('\t',' ').replace('\r',' ').replace('\n',' '); }
    public static void main(String[] args) throws Exception {
        Path recording=Path.of(args[0]),destination=Path.of(args[1]);
        List<Anchor> anchors=new ArrayList<>();
        try(var file=new RecordingFile(recording)) {
            while(file.hasMoreEvents()) {
                var event=file.readEvent();
                if(event.getEventType().getName().equals("an.trace.Clock"))
                    anchors.add(new Anchor(event.getStartTime(),event.getLong("nanoTime"),event.getDuration().toNanos()));
            }
        }
        if(anchors.isEmpty())throw new IllegalStateException("No JFR monotonic clock anchors");
        anchors.sort(Comparator.comparing(Anchor::instant));
        try(var out=Files.newBufferedWriter(destination,StandardOpenOption.CREATE_NEW);
                var file=new RecordingFile(recording)) {
            out.write("type\tnanos\tthread\tclass\tmethod\tweight\tdurationNanos\towner\tobjectClass\tclockWidthNanos\tstack\n");
            while(file.hasMoreEvents()) {
                var event=file.readEvent();String type=event.getEventType().getName();
                if(!Set.of("jdk.ObjectAllocationSample","jdk.JavaMonitorEnter","jdk.ExecutionSample","jdk.DataLoss").contains(type))continue;
                Anchor anchor=anchors.stream().min(Comparator.comparingLong(a->
                        Math.abs(Duration.between(a.instant,event.getStartTime()).toMillis()))).orElseThrow();
                long time=anchor.nanos+Duration.between(anchor.instant,event.getStartTime()).toNanos();
                RecordedThread thread=event.hasField("sampledThread")?event.getThread("sampledThread"):event.getThread();
                RecordedThread owner=event.hasField("previousOwner")?event.getThread("previousOwner"):null;
                long weight=event.hasField("weight")?event.getLong("weight"):0L;
                String objectClass=event.hasField("objectClass")?event.getClass("objectClass").getName():
                        event.hasField("monitorClass")?event.getClass("monitorClass").getName():"";
                String className="",method="",stack="";
                if(event.getStackTrace()!=null) {
                    var frames=event.getStackTrace().getFrames();List<String> labels=new ArrayList<>();
                    for(var frame:frames) {
                        String clazz=frame.getMethod().getType().getName(),name=frame.getMethod().getName();
                        labels.add(clazz+"."+name);
                        if(className.isEmpty()&&(clazz.startsWith("com.scarasol.")||clazz.startsWith("an.production.")
                                ||clazz.startsWith("net.minecraft.")||clazz.startsWith("an.trace.")&&!name.equals("run"))) {
                            className=clazz;method=name;
                        }
                    }
                    stack=String.join(";",labels);
                }
                out.write(type+"\t"+time+"\t"+clean(thread==null?"":thread.getJavaName())+"\t"+className+"\t"+method+
                        "\t"+weight+"\t"+event.getDuration().toNanos()+"\t"+clean(owner==null?"":owner.getJavaName())+
                        "\t"+clean(objectClass)+"\t"+anchor.width+"\t"+clean(stack)+"\n");
            }
        }
        System.out.println("JFR_EXTRACT_OK anchors="+anchors.size());
    }
}
