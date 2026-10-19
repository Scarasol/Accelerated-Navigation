package an.trace;

import java.lang.instrument.*;
import java.nio.file.*;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.jar.JarFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class Agent {
    static final String BASE = "com/scarasol/acceleratednavigation/";
    static final Map<String, Set<String>> TARGETS = new LinkedHashMap<>();
    static final Queue<String> TRANSFORMS = new ConcurrentLinkedQueue<>();
    static final Map<String,Integer> PHASE_IDS = new LinkedHashMap<>();
    static Instrumentation instrumentation;
    static {
        target("scheduler/NavigationScheduler", "beginTick", "endTick", "drain", "drainReady", "requestReadyDispatch", "finishTickEnvelope");
        target("navigation/SegmentedNavigationService", "prepare", "move", "adopt", "capacity", "queue", "initialize",
                "macroCandidate", "existingMacroCandidate", "prepareWindow", "queueReady", "nextSegment", "findSegment",
                "nativeDecision", "searchNative", "awaitNative", "publish", "observe", "settle", "physical",
                "recordDestinationEntry", "settleTail", "recover", "refresh", "replan", "finish", "nativeStopped", "lambda$");
        target("navigation/NativeNavigationSearch", "find", "normalize", "legacy");
        target("navigation/AutomaticNavigationBridge", "prepare", "move", "movePath");
        target("navigation/NavigationPoiAdapter", "beforeRetry", "beforeQuery", "validSelection", "afterBusiness");
        target("navigation/NavigationCapabilities", "capture", "apply");
        target("navigation/NativeQueryProof", "capture");
        target("navigation/NavigationRequestPath", "publish");
        target("topology/TopologyService", "requestMacroQuery", "prepareGroundWindow", "scanSection",
                "runOneRecovery", "drainFactDemandChanges", "finishTick", "drainPersistence", "persistDecision", "queueServer");
        target("topology/TopologyService$MacroRequest", "submit", "completeWorker");
        target("topology/TopologyWorkerRuntime", "requestMacroQuery", "applyRequestEvent", "joinResolve", "joinCanonical",
                "processSectionEvents", "applySectionEvent", "runtimeTransition", "prepareDemand", "beginBuild", "build",
                "publish", "buildSuperCluster", "publishSuperCluster", "buildBaseBoundaryLinks", "buildSuperBoundaryLinks");
        target("topology/TopologyWorkerRuntime$ResolveFlight", "start", "resolveOnWorker", "resolved");
        target("topology/TopologyWorkerRuntime$MacroFlight", "start", "resume", "runWorkerTask", "complete", "completeWorker");
        target("topology/TopologyWorkerRuntime$MacroQuery", "prepareForWorker", "runWorkerSlice", "prepareWorkerResult",
                "requestPendingSections", "dependencyCompleted", "captureComponents");
        target("topology/BaseClusterTopology", "build", "deriveLegalAnchors", "labelComponents", "buildGroundEdges");
        target("topology/SuperClusterTopology", "build", "boundaryLinks");
        target("topology/GroundSegmentWindow", "capture", "enumerate", "lambda$");
        target("topology/MacroSearch", "step");
        target("topology/SharedSectionCandidate", "supports");
        target("topology/MacroSearch$ComponentSnapshot", "<init>");
        TARGETS.put("net/minecraft/world/level/pathfinder/PathFinder", Set.of("m_77427_"));
        TARGETS.put(BASE+"topology/TopologyTaskExecutor", Set.of());
        TARGETS.put("an/production/TimelineScenarioProbe", Set.of());
        TARGETS.put("an/production/CoverageScenarioProbe", Set.of("observe","track","action"));
        TARGETS.put("an/trace/SelfCheck", Set.of("root","nested","failure"));
        if (Boolean.getBoolean("an.trace.closure")) {
            extend("topology/TopologyWorkerRuntime", "markLatestDerivedStale", "invalidateHaloDependents",
                    "changeFactReferences", "changeTopologyFactReferences", "holdFacts", "releaseHeldFacts",
                    "pinBase", "releaseBasePin", "pinSuper", "releaseSuperPin", "buildInput",
                    "requestClusterDependency", "requestSuperCluster", "beginSuperRequest", "completeSuperChildren",
                    "beginSuperBuild", "beginSuperBoundaryChildren", "requestBaseBoundaryLinks",
                    "requestSuperBoundaryLinks", "captureBaseStamps", "finishCorridors", "replayCompletedCorridor");
            extend("topology/TopologyWorkerRuntime$MacroQuery", "prepareEndpoints", "requestDependency",
                    "completeDependencyRequest", "captureDependency", "attachResolvedDependencies",
                    "prepareRecoveryWitness", "captureComponents", "drainDeferredGraphs");
            target("topology/TopologyWorkerRuntime$TopologyGraph", "expandInto", "captureInitialTopology",
                    "attachTopology", "attachBoundary", "binding", "close");
            target("topology/TopologyWorkerRuntime$SuperTopologyGraph", "expandInto", "captureInitialTopology",
                    "attachTopology", "attachBoundary", "binding", "close");
            target("topology/BoundaryPortBits", "mayLink");
        }
    }
    static void target(String owner, String... names) { TARGETS.put(BASE+owner, Set.of(names)); }
    static void extend(String owner, String... names) {
        Set<String> merged = new LinkedHashSet<>(TARGETS.getOrDefault(BASE+owner, Set.of()));
        merged.addAll(Arrays.asList(names));
        TARGETS.put(BASE+owner, Set.copyOf(merged));
    }
    public static void premain(String directory, Instrumentation inst) throws Exception {
        instrumentation = inst;
        Path root = Path.of(directory);
        inst.appendToBootstrapClassLoaderSearch(new JarFile(root.resolve("artifacts/trace-helper.jar").toFile()));
        Class.forName("an.trace.Trace",true,null);
        inst.addTransformer(new Transformer());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Path out = Path.of(System.getProperty("an.production.output"));
                Trace.dump(out);
                Files.write(out.resolve("transforms.txt"), TRANSFORMS, StandardOpenOption.CREATE_NEW);
            } catch (Throwable error) { error.printStackTrace(); }
        }, "timeline-output"));
    }
    static synchronized int phase(String owner, MethodNode method) {
        String name = owner.substring(owner.lastIndexOf('/')+1)+"."+method.name+method.desc;
        return PHASE_IDS.computeIfAbsent(name, key -> { int id=Trace.PHASES.size();Trace.PHASES.add(key);return id; });
    }
    static final class Transformer implements ClassFileTransformer {
        @Override public byte[] transform(Module module, ClassLoader loader, String name, Class<?> type,
                ProtectionDomain domain, byte[] bytes) {
            if (name == null || !TARGETS.containsKey(name)) return null;
            if (module != null && module.isNamed()) {
                String pkg = name.substring(0,name.lastIndexOf('/')).replace('/','.');
                instrumentation.redefineModule(module, Set.of(Trace.class.getModule()), Map.of(),
                        Map.of(pkg,Set.of(Trace.class.getModule())), Set.of(), Map.of());
            }
            try {
                ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node,ClassReader.EXPAND_FRAMES);
                for (MethodNode method : node.methods) {
                    if (name.endsWith("TopologyTaskExecutor")) {
                        if (method.name.equals("insertQueued")) {
                            InsnList hook = new InsnList();hook.add(new VarInsnNode(Opcodes.ALOAD,1));
                            hook.add(call("enqueued","(Ljava/lang/Object;)V"));method.instructions.insert(hook);
                        }
                        if (method.name.equals("takeNext"))
                            for (AbstractInsnNode ins : method.instructions.toArray()) if (ins.getOpcode()==Opcodes.ARETURN) {
                                InsnList hook=new InsnList();hook.add(new InsnNode(Opcodes.DUP));
                                hook.add(call("selected","(Ljava/lang/Object;)V"));method.instructions.insertBefore(ins,hook);
                            }
                        if (method.name.equals("runTasks"))
                            for (AbstractInsnNode ins : method.instructions.toArray())
                                if (ins instanceof MethodInsnNode invoke && invoke.owner.equals("java/lang/Runnable")
                                        && invoke.name.equals("run")) {
                                    AbstractInsnNode field=ins.getPrevious(), load=field.getPrevious();
                                    if (!(field instanceof FieldInsnNode f) || !f.name.equals("command")
                                            || !(load instanceof VarInsnNode local)) throw new IllegalStateException("worker callsite changed");
                                    method.instructions.insertBefore(ins,new VarInsnNode(Opcodes.ALOAD,local.var));
                                    method.instructions.set(ins,call("run","(Ljava/lang/Runnable;Ljava/lang/Object;)V"));
                                }
                        continue;
                    }
                    Set<String> names=TARGETS.get(name);
                    boolean selected=names.contains(method.name) || names.contains("lambda$")&&method.name.startsWith("lambda$");
                    if (method.name.startsWith("lambda$")) {
                        int result=Type.getReturnType(method.desc).getSort();
                        if(result!=Type.OBJECT&&result!=Type.VOID)selected=false;
                    }
                    if (!selected) continue;
                    int phase=phase(name,method); instrument(method,phase);
                    TRANSFORMS.add(name+" "+method.name+method.desc+" "+phase);
                }
                ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);return writer.toByteArray();
            } catch (Throwable error) { TRANSFORMS.add("ERROR "+name+" "+error);throw new IllegalStateException(error); }
        }
    }
    static MethodInsnNode call(String name,String desc) { return new MethodInsnNode(Opcodes.INVOKESTATIC,"an/trace/Trace",name,desc,false); }
    static InsnList exit(int phase, boolean returnedObject, boolean completed) {
        InsnList list=new InsnList();
        if(returnedObject)list.add(new InsnNode(Opcodes.DUP));
        list.add(new LdcInsnNode(phase));
        if(returnedObject)list.add(new InsnNode(Opcodes.SWAP));else list.add(new InsnNode(Opcodes.ACONST_NULL));
        list.add(new InsnNode(completed?Opcodes.ICONST_1:Opcodes.ICONST_0));
        list.add(call("exit","(ILjava/lang/Object;Z)V"));return list;
    }
    static void instrument(MethodNode method,int phase) {
        AbstractInsnNode[] original=method.instructions.toArray();
        LabelNode start=new LabelNode(),end=new LabelNode(),handler=new LabelNode();
        InsnList prefix=new InsnList();prefix.add(new LdcInsnNode(phase));
        prefix.add((method.access&Opcodes.ACC_STATIC)==0?new VarInsnNode(Opcodes.ALOAD,0):new InsnNode(Opcodes.ACONST_NULL));
        int local=(method.access&Opcodes.ACC_STATIC)==0?1:0,refs=0;
        for(Type arg:Type.getArgumentTypes(method.desc)) {
            if(refs<2&&(arg.getSort()==Type.OBJECT||arg.getSort()==Type.ARRAY)) {
                prefix.add(new VarInsnNode(Opcodes.ALOAD,local));refs++;
            }
            local+=arg.getSize();
        }
        while(refs++<2)prefix.add(new InsnNode(Opcodes.ACONST_NULL));
        prefix.add(call("enter","(ILjava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"));
        if(method.name.equals("find")&&method.desc.startsWith("(Lcom/scarasol/acceleratednavigation/navigation/NavigationRequestPath;")) {
            prefix.add(new VarInsnNode(Opcodes.ALOAD,0));prefix.add(new VarInsnNode(Opcodes.ALOAD,1));
            prefix.add(new VarInsnNode(Opcodes.ILOAD,2));prefix.add(new VarInsnNode(Opcodes.FLOAD,3));
            prefix.add(call("search","(Ljava/lang/Object;Ljava/lang/Object;IF)V"));
        }
        if(method.name.equals("supports")&&method.desc.endsWith("Lnet/minecraft/core/BlockPos;I)Z")) {
            for(int slot=0;slot<3;slot++)prefix.add(new VarInsnNode(Opcodes.ALOAD,slot));
            prefix.add(new VarInsnNode(Opcodes.ILOAD,3));
            prefix.add(call("qualification","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;I)V"));
        }
        prefix.add(start);
        if(method.name.equals("<init>")) {
            AbstractInsnNode initialized=Arrays.stream(original).filter(ins -> ins instanceof MethodInsnNode invoke
                    &&invoke.getOpcode()==Opcodes.INVOKESPECIAL&&invoke.name.equals("<init>")).findFirst().orElseThrow();
            method.instructions.insert(initialized,prefix);
        } else method.instructions.insert(prefix);
        for(AbstractInsnNode ins:original)if(ins.getOpcode()>=Opcodes.IRETURN&&ins.getOpcode()<=Opcodes.RETURN) {
            if(ins.getOpcode()==Opcodes.IRETURN&&Type.getReturnType(method.desc).getSort()==Type.BOOLEAN) {
                InsnList hook=new InsnList();hook.add(new InsnNode(Opcodes.DUP));
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"java/lang/Boolean","valueOf","(Z)Ljava/lang/Boolean;",false));
                hook.add(new LdcInsnNode(phase));hook.add(new InsnNode(Opcodes.SWAP));hook.add(new InsnNode(Opcodes.ICONST_1));
                hook.add(call("exit","(ILjava/lang/Object;Z)V"));method.instructions.insertBefore(ins,hook);
            } else method.instructions.insertBefore(ins,exit(phase,ins.getOpcode()==Opcodes.ARETURN,true));
        }
        method.instructions.add(end);method.instructions.add(handler);
        method.instructions.add(new FrameNode(Opcodes.F_NEW,0,new Object[]{},1,new Object[]{"java/lang/Throwable"}));
        method.instructions.add(exit(phase,false,false));method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start,end,handler,"java/lang/Throwable"));
    }
}
