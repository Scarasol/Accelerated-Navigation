package an.trace;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import jdk.jfr.consumer.*;

/** Offline reader for user recordings without navigation clock anchors. */
public final class ProfileExtract {
    private static String clean(String value) {
        return value.replace('\t',' ').replace('\r',' ').replace('\n',' ');
    }

    public static void main(String[] args) throws Exception {
        Path source=Path.of(args[0]), destination=Path.of(args[1]);
        Files.createDirectories(destination);
        Map<String,Long> counts=new TreeMap<>();
        Instant first=null,last=null;
        long selected=0;
        try (var file=new RecordingFile(source);
             var out=Files.newBufferedWriter(destination.resolve("profile-samples.tsv"),StandardOpenOption.CREATE_NEW)) {
            out.write("type\tepochMillis\tthread\tstate\tweight\tdurationNanos\ttruncated\tobjectClass\tstack\n");
            while(file.hasMoreEvents()) {
                var event=file.readEvent();
                String type=event.getEventType().getName();
                counts.merge(type,1L,Long::sum);
                Instant time=event.getStartTime();
                if(first==null || time.isBefore(first)) first=time;
                if(last==null || time.isAfter(last)) last=time;
                if(!Set.of("jdk.ExecutionSample","jdk.NativeMethodSample","profiler.WallClockSleeping",
                        "jdk.ObjectAllocationSample","jdk.JavaMonitorEnter").contains(type)) continue;
                RecordedThread thread=event.hasField("sampledThread") ? event.getThread("sampledThread") : event.getThread();
                String name=thread==null ? "" : Objects.toString(thread.getJavaName(),"");
                if(!name.equals("Server thread") && !name.startsWith("accelerated-navigation-topology-")) continue;
                List<String> frames=new ArrayList<>();
                var stack=event.getStackTrace();
                if(stack!=null) for(var frame:stack.getFrames())
                    frames.add(frame.getMethod().getType().getName()+"."+frame.getMethod().getName());
                String state=event.hasField("state") ? String.valueOf((Object)event.getValue("state")) : "";
                String objectClass=event.hasField("objectClass") ? event.getClass("objectClass").getName() : "";
                long weight=event.hasField("weight") ? event.getLong("weight") : 0;
                out.write(type+"\t"+time.toEpochMilli()+"\t"+clean(name)+"\t"+clean(state)+"\t"+weight
                        +"\t"+event.getDuration().toNanos()+"\t"+(stack!=null && stack.isTruncated())
                        +"\t"+clean(objectClass)+"\t"+clean(String.join(";",frames))+"\n");
                selected++;
            }
        }
        try(var out=Files.newBufferedWriter(destination.resolve("profile-metadata.tsv"),StandardOpenOption.CREATE_NEW)) {
            out.write("key\tvalue\nfirst\t"+first+"\nlast\t"+last+"\nselected\t"+selected+"\n");
            for(var entry:counts.entrySet()) out.write(entry.getKey()+"\t"+entry.getValue()+"\n");
        }
        System.out.println("PROFILE_EXTRACT_OK selected="+selected+" first="+first+" last="+last);
    }
}
