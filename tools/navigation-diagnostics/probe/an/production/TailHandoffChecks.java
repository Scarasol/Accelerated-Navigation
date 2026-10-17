package an.production;

import an.trace.Trace;
import com.google.gson.*;
import com.scarasol.acceleratednavigation.mixin.NavigationAccess;
import com.scarasol.acceleratednavigation.navigation.*;
import com.scarasol.acceleratednavigation.topology.GroundSegmentWindow;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Fault injection at a naturally reached tail, before the queued operation runs. */
public final class TailHandoffChecks {
    private static final JsonArray checks=new JsonArray();
    private static boolean running,started,completed;
    private static String failure="";
    private static Object capturedWork;
    private static long arrivals;
    private static String action="none";
    private static Field field(Object object,String name) throws Exception {
        Field result=object.getClass().getDeclaredField(name);result.setAccessible(true);return result;
    }
    private static void set(Object object,String name,Object value) throws Exception {field(object,name).set(object,value);}
    private static Method method(Object service,String name,Object work) throws Exception {
        Method result=service.getClass().getDeclaredMethod(name,work.getClass());result.setAccessible(true);return result;
    }
    private static boolean ready(Object service,Object work) throws Exception {
        return (boolean)method(service,"tailArrivalReady",work).invoke(service,work);
    }
    private static void invoke(Object service,Object work) throws Exception {
        method(service,"settleTail",work).invoke(service,work);
    }
    private static void check(String name,boolean passed) {
        JsonObject row=new JsonObject();row.addProperty("name",name);row.addProperty("passed",passed);checks.add(row);
        if(!passed)throw new AssertionError(name);
    }
    private static void rejected(Object service,Object work,String name) throws Exception {
        int cursor=(int)Trace.field(work,"cursor");
        Object followup=Trace.field(work,"followup");
        NavigationRequestPath request=(NavigationRequestPath)Trace.field(work,"request");
        BlockPos target=request.getTarget();
        var before=((SegmentedNavigationService)service).metrics();
        long count=(long)Trace.field(service,"arrivals");
        invoke(service,work);
        check(name,(int)Trace.field(work,"cursor")==cursor
                &&Trace.field(work,"followup")==followup&&request.getTarget().equals(target)
                &&((SegmentedNavigationService)service).metrics().nativeCalls()==before.nativeCalls()
                &&((SegmentedNavigationService)service).metrics().recoveries()==before.recoveries()
                &&(long)Trace.field(service,"arrivals")==count);
    }
    private static void rejectField(Object service,Object work,String name,Object value) throws Exception {
        Object old=Trace.field(work,name);
        try {set(work,name,value);check(name+"-guard",!ready(service,work));rejected(service,work,name+"-no-transition");}
        finally {set(work,name,old);}
    }
    private static Object changedEntry(Object entry,String name,Object value) throws Exception {
        var components=entry.getClass().getRecordComponents();
        Object[] values=new Object[components.length];Class<?>[] types=new Class<?>[components.length];
        for(int i=0;i<components.length;i++) {
            Method accessor=components[i].getAccessor();accessor.setAccessible(true);
            values[i]=components[i].getName().equals(name)?value:accessor.invoke(entry);
            types[i]=components[i].getType();
        }
        Constructor<?> constructor=entry.getClass().getDeclaredConstructor(types);constructor.setAccessible(true);
        return constructor.newInstance(values);
    }
    public static void before(Object service,Object work) {
        action=System.getProperty("an.trace.tailAction","none");
        if((!Boolean.getBoolean("an.trace.tailChecks")&&action.equals("none"))||running||started)return;
        running=true;started=true;capturedWork=work;
        try {
            check("natural-eligible-tail",ready(service,work));
            NavigationRequestPath request=(NavigationRequestPath)Trace.field(work,"request");
            if(!action.equals("none")) {
                long nativeCalls=((SegmentedNavigationService)service).metrics().nativeCalls();
                if(action.equals("cancel"))request.navigation.stop();
                else if(action.equals("replace")) {
                    BlockPos target=request.getTarget().offset(-4,0,4);
                    check("replacement-accepted",request.navigation.moveTo(target.getX()+.5,target.getY(),target.getZ()+.5,1));
                } else throw new IllegalArgumentException("Unknown tail action");
                check("real-old-request-cancelled",request.state==NavigationRequestPath.State.CANCELLED);
                check("old-work-no-longer-active",((SegmentedNavigationService)service).active(request.navigation)!=request);
                check("cancelled-tail-guard",!ready(service,work));
                invoke(service,work);
                check("stale-callback-no-native-search",((SegmentedNavigationService)service).metrics().nativeCalls()==nativeCalls);
                completed=true;return;
            }
            GroundSegmentWindow window=(GroundSegmentWindow)Trace.field(work,"window");
            check("real-last-node",!request.isDone()&&request.getNextNodeIndex()==request.getNodeCount()-1);
            check("current-position-in-destination",window.contains(request.mob.blockPosition()));
            for(var entry:Map.<String,Object>of("stable",2,"segmentReachable",false,"nativeStopped",true,
                    "last",true,"cursor",Trace.field(work,"stageEnd"),"stageEnd",
                    ((List<?>)Trace.field(work,"edges")).size()-1).entrySet())
                rejectField(service,work,entry.getKey(),entry.getValue());
            rejectField(service,work,"window",null);
            rejectField(service,work,"segmentEntry",null);
            rejectField(service,work,"nativeProof",null);
            Object entry=Trace.field(work,"segmentEntry");
            for(String name:List.of("request","publication","windowRevision","destinationComponent","nativeProof")) {
                Object value=Trace.field(entry,name);
                Object bad=null;
                if(value instanceof Long n)bad=Long.valueOf(n+1);
                else if(value instanceof Integer n)bad=Integer.valueOf(n+1);
                rejectField(service,work,"segmentEntry",changedEntry(entry,name,bad));
            }
            int index=request.getNextNodeIndex();
            try {request.setNextNodeIndex(index-1);check("not-last-node-guard",!ready(service,work));
                rejected(service,work,"not-last-node-no-transition");}
            finally {request.setNextNodeIndex(index);}
            NavigationAccess access=(NavigationAccess)request.navigation;
            try {access.acceleratedNavigation$path(null);check("foreign-path-guard",!ready(service,work));
                rejected(service,work,"foreign-path-no-transition");}
            finally {access.acceleratedNavigation$path(request);}
            try {request.mob.setOnGround(false);check("off-ground-guard",!ready(service,work));
                rejected(service,work,"off-ground-no-transition");}
            finally {request.mob.setOnGround(true);}
            NavigationRequestPath.State state=request.state;
            try {request.state=NavigationRequestPath.State.CANCELLED;check("terminal-guard",!ready(service,work));
                rejected(service,work,"terminal-no-transition");}
            finally {request.state=state;}
            ServerLevel level=(ServerLevel)request.mob.level();
            BlockPos body=request.mob.blockPosition();BlockState original=level.getBlockState(body);
            try {level.setBlock(body,Blocks.STONE.defaultBlockState(),2);
                rejected(service,work,"body-collision-no-transition");}
            finally {level.setBlock(body,original,2);}
            Map<BlockPos,BlockState> support=new LinkedHashMap<>();
            var box=request.mob.getBoundingBox();
            for(int x=(int)Math.floor(box.minX);x<=(int)Math.floor(box.maxX);x++)
                for(int z=(int)Math.floor(box.minZ);z<=(int)Math.floor(box.maxZ);z++) {
                    BlockPos pos=new BlockPos(x,body.getY()-1,z);support.put(pos,level.getBlockState(pos));
                }
            try {support.keySet().forEach(pos->level.setBlock(pos,Blocks.AIR.defaultBlockState(),2));
                rejected(service,work,"no-support-no-transition");}
            finally {support.forEach((pos,block)->level.setBlock(pos,block,2));}
            check("world-version-does-not-revoke-tail",ready(service,work));
            check("proof-really-stale-after-block-edits",
                    !((NativeQueryProof)Trace.field(work,"nativeProof")).current());
            var target=request.intent.entity();
            if(target!=null) {
                var old=target.position();
                try {
                    target.setPos(old.x-2,old.y,old.z);
                    rejected(service,work,"fresh-target-outside-accuracy-no-transition");
                } finally {target.setPos(old.x,old.y,old.z);}
            }
            arrivals=(long)Trace.field(service,"arrivals");
        } catch(Throwable error) {failure=error.toString();throw new IllegalStateException("Tail controls failed",error);}
        finally {running=false;write();}
    }
    public static void after(Object service,Object work) {
        if(!Boolean.getBoolean("an.trace.tailChecks")||running||completed||work!=capturedWork||!failure.isEmpty())return;
        running=true;
        try {
            check("one-real-tail-transition",(int)Trace.field(work,"cursor")==(int)Trace.field(work,"stageEnd")
                    &&(long)Trace.field(service,"arrivals")==arrivals+1);
            check("consumed-tail-guard",!ready(service,work));
            rejected(service,work,"duplicate-settle-no-transition");
            completed=true;
        } catch(Throwable error) {failure=error.toString();throw new IllegalStateException("Tail controls failed",error);}
        finally {running=false;write();}
    }
    private static void write() {
        try {
            JsonObject root=new JsonObject();root.addProperty("complete",completed);
            root.addProperty("passed",completed&&failure.isEmpty());root.addProperty("failure",failure);
            root.addProperty("action",action);
            root.addProperty("scope",action.equals("none")
                    ?"Fault injection on a naturally eligible tail; terminal-state injection is not a full cancellation lifecycle test."
                    :"Real external cancellation or replacement at the queued tail callback, followed by invoking that stale callback.");
            root.add("checks",checks);
            Files.writeString(Path.of(System.getProperty("an.production.output"),"tail-checks.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch(Exception error) {throw new IllegalStateException(error);}
    }
    private TailHandoffChecks() {}
}
