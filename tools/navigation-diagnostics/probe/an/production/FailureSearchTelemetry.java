package an.production;

import com.google.gson.*;
import com.scarasol.acceleratednavigation.mixin.NavigationAccess;
import com.scarasol.acceleratednavigation.navigation.*;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.*;

/** Counterfactual searches are isolated from movement and public search counters. */
public final class FailureSearchTelemetry {
    private static final JsonArray ROWS=new JsonArray();
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private static final boolean ENABLED=System.getProperty("an.trace.suite","").equals("failures");
    private static final boolean REPLAY=Boolean.getBoolean("an.trace.replay");
    private static final ThreadLocal<JsonObject> COUNTERS=new ThreadLocal<>();
    public static void beginSearch(Node start,float range) {
        if(!ENABLED)return;
        JsonObject counters=new JsonObject();counters.add("start",JSON.toJsonTree(point(start.asBlockPos())));
        counters.addProperty("range",range);counters.addProperty("popped",0);
        counters.addProperty("walkedChecks",0);counters.addProperty("walkedRejected",0);
        COUNTERS.set(counters);
    }
    public static void popped() {
        JsonObject counters=COUNTERS.get();
        if(ENABLED&&counters!=null)counters.addProperty("popped",counters.get("popped").getAsInt()+1);
    }
    public static void walked(float value) {
        JsonObject counters=COUNTERS.get();
        if(!ENABLED||counters==null)return;
        counters.addProperty("walkedChecks",counters.get("walkedChecks").getAsInt()+1);
        if(value>=counters.get("range").getAsFloat())
            counters.addProperty("walkedRejected",counters.get("walkedRejected").getAsInt()+1);
    }
    private static JsonObject counters() {
        JsonObject counters=COUNTERS.get();
        return counters==null?new JsonObject():counters.deepCopy();
    }

    private static Object field(Object object,String name) throws ReflectiveOperationException {
        Field field=object.getClass().getDeclaredField(name);
        field.setAccessible(true);return field.get(object);
    }
    private static List<Integer> point(BlockPos point) {
        return List.of(point.getX(),point.getY(),point.getZ());
    }
    private static JsonObject path(NativeNavigationSearch.Result result) {
        JsonObject row=new JsonObject();row.addProperty("unavailable",result.unavailable());
        Path path=result.path();row.addProperty("nullPath",path==null);
        if(path==null)return row;
        row.addProperty("canReach",path.canReach());row.add("target",JSON.toJsonTree(point(path.getTarget())));
        JsonArray nodes=new JsonArray();double length=0;
        for(int i=0;i<path.getNodeCount();i++) {
            Node node=path.getNode(i);nodes.add(JSON.toJsonTree(point(node.asBlockPos())));
            if(i>0)length+=node.distanceTo(path.getNode(i-1));
        }
        row.add("nodes",nodes);row.addProperty("nodeCount",path.getNodeCount());
        row.addProperty("length",length);
        if(path.getEndNode()!=null) {
            row.add("end",JSON.toJsonTree(point(path.getEndNode().asBlockPos())));
            row.addProperty("endWalkedDistance",path.getEndNode().walkedDistance);
        }
        return row;
    }
    private static List<Object> state(NavigationAccess access) {
        return Arrays.asList(access.acceleratedNavigation$path(),access.acceleratedNavigation$target(),
                access.acceleratedNavigation$accuracy(),access.acceleratedNavigation$multiplier(),
                access.acceleratedNavigation$timeoutNode(),access.acceleratedNavigation$timeoutTimer(),
                access.acceleratedNavigation$timeoutLimit(),access.acceleratedNavigation$stuck());
    }
    public static void search(NavigationRequestPath request,Set<BlockPos> targets,int accuracy,float range,
            Set<BlockPos> avoided,NativeNavigationSearch.Result actual) {
        if(!ENABLED)return;
        try {
            var service=SegmentedNavigationService.forServer(((ServerLevel)request.mob.level()).getServer());
            Object work=((Map<?,?>)field(service,"records")).get(request);
            JsonObject row=new JsonObject();
            row.addProperty("queryId",request.queryId.toString());
            row.addProperty("tick",request.mob.level().getGameTime());
            row.addProperty("nanoTime",System.nanoTime());
            row.addProperty("last",(boolean)field(work,"last"));
            row.addProperty("cursor",(int)field(work,"cursor"));
            row.addProperty("stageEnd",(int)field(work,"stageEnd"));
            row.addProperty("attempt",(int)field(work,"attempt"));
            row.addProperty("stride",(int)field(work,"stride"));
            row.addProperty("refreshCount",(int)field(work,"refreshes"));
            row.add("start",JSON.toJsonTree(point(request.mob.blockPosition())));
            row.add("position",JSON.toJsonTree(List.of(request.mob.getX(),request.mob.getY(),request.mob.getZ())));
            row.add("targets",JSON.toJsonTree(targets.stream().map(FailureSearchTelemetry::point).sorted(
                    Comparator.comparing(Object::toString)).toList()));
            row.add("avoided",JSON.toJsonTree(avoided.stream().map(FailureSearchTelemetry::point).toList()));
            row.addProperty("accuracy",accuracy);row.addProperty("actualRange",range);
            row.addProperty("requestedRange",request.intent.range());
            row.addProperty("visitedMultiplier",request.intent.visitedMultiplier());
            row.addProperty("regionOffset",request.intent.regionOffset());row.addProperty("upward",request.intent.upward());
            row.addProperty("queryRegionRadius",(int)(range+request.intent.regionOffset()));
            row.add("plannedStart",JSON.toJsonTree(point((BlockPos)field(work,"plannedStart"))));
            JsonObject original=path(actual);row.add("actual",original);
            row.add("actualCounters",counters());
            NavigationAccess access=(NavigationAccess)request.navigation;
            List<Object> before=state(access);
            NavigationCapabilities capabilities=NavigationCapabilities.capture(request.mob);
            Object finder=null;
            for(Field f:PathNavigation.class.getDeclaredFields())if(f.getType()==PathFinder.class) {
                f.setAccessible(true);finder=f.get(request.navigation);
            }
            if(finder==null)throw new AssertionError("No actual PathFinder");
            for(Field f:PathFinder.class.getDeclaredFields())if(f.getType()==int.class) {
                f.setAccessible(true);row.addProperty("maxVisitedNodes",f.getInt(finder));
                row.addProperty("effectiveVisitedLimit",(int)(f.getInt(finder)*request.intent.visitedMultiplier()));
            }
            JsonArray replays=new JsonArray();
            if(REPLAY) {
                var stats=PerformanceSearchTelemetry.ACTORS.remove(request.mob);
                try {
                    float other=range==request.intent.range()?16:request.intent.range();
                    float[] ranges=(ROWS.size()&1)==0?new float[]{range,other,other,range}:new float[]{other,range,range,other};
                    for(float candidate:ranges) {
                        JsonObject replay=new JsonObject();replay.addProperty("range",candidate);
                        replay.addProperty("regionOffset",request.intent.regionOffset());
                        replay.add("path",path(NativeNavigationSearch.find(request,targets,accuracy,candidate,avoided)));
                        replay.add("counters",counters());
                        replays.add(replay);
                    }
                    int wideRadius=(int)(Math.max(range,other)+request.intent.regionOffset());
                    for(float candidate:new float[]{Math.min(range,other),Math.max(range,other)}) {
                        int offset=wideRadius-(int)candidate;
                        NavigationIntent intent=request.intent;
                        NavigationIntent adjusted=new NavigationIntent(intent.targets(),intent.entity(),intent.normalizeGround(),
                                offset,intent.upward(),intent.accuracy(),intent.range(),intent.visitedMultiplier(),intent.capabilities());
                        Constructor<NavigationRequestPath> constructor=NavigationRequestPath.class.getDeclaredConstructor(
                                PathNavigation.class,net.minecraft.world.entity.Mob.class,NavigationIntent.class,Object.class,long.class,long.class);
                        constructor.setAccessible(true);
                        NavigationRequestPath isolated=constructor.newInstance(request.navigation,request.mob,adjusted,
                                request.controller,request.controlGeneration,request.createdTick);
                        JsonObject replay=new JsonObject();replay.addProperty("range",candidate);
                        replay.addProperty("regionOffset",offset);replay.addProperty("equalRegionRadius",wideRadius);
                        replay.add("path",path(NativeNavigationSearch.find(isolated,targets,accuracy,candidate,avoided)));
                        replay.add("counters",counters());
                        replays.add(replay);
                    }
                } finally { if(stats!=null)PerformanceSearchTelemetry.ACTORS.put(request.mob,stats); }
            }
            row.add("replays",replays);
            boolean restored=before.equals(state(access))&&capabilities.equals(NavigationCapabilities.capture(request.mob))
                    &&original.equals(path(actual))&&!NativeNavigationSearch.raw();
            row.addProperty("stateRestored",restored);ROWS.add(row);
            if(!restored)throw new AssertionError("Counterfactual search changed execution state");
        } catch(ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    public static void write(java.nio.file.Path output) throws java.io.IOException {
        if(!ENABLED)return;
        JsonObject root=new JsonObject();root.addProperty("diagnostic",true);root.addProperty("replayEnabled",REPLAY);
        root.add("searches",ROWS);Files.writeString(output.resolve("failure-searches.json"),JSON.toJson(root));
    }
    private FailureSearchTelemetry() {}
}
