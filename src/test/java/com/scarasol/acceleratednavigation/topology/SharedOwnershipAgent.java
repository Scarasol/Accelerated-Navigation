package com.scarasol.acceleratednavigation.topology;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Test-only stack-root observations; no production fields or object layouts change. */
public final class SharedOwnershipAgent {
    private static final String PREFIX = "com/scarasol/acceleratednavigation/topology/";
    private static final String AGENT = PREFIX + "SharedOwnershipAgent";
    private static final Map<Long, Owner> OWNERS = new ConcurrentHashMap<>();
    private static final Set<String> TRANSFORMED = ConcurrentHashMap.newKeySet();
    private static Instrumentation instrumentation;
    private static final class Owner {
        Object scratch;
        final Set<Object> temporary = Collections.newSetFromMap(new IdentityHashMap<>());
    }
    public static void premain(String args, Instrumentation value) {
        instrumentation = value;
        value.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
                    ProtectionDomain domain, byte[] bytes) {
                if (name == null || !name.startsWith(PREFIX)) return null;
                boolean constructor = name.equals(PREFIX + "LocalKernel")
                        || name.equals(PREFIX + "BaseClusterTopology$BuildScratch")
                        || name.equals(PREFIX + "LocalKernel$Row")
                        || name.equals(PREFIX + "SharedRows$Evaluation")
                        || name.equals(PREFIX + "QueryScratch");
                boolean executor = name.equals(PREFIX + "TopologyTaskExecutor");
                boolean query = name.equals(PREFIX + "SharedRows");
                if (!constructor && !executor && !query) return null;
                ClassReader reader = new ClassReader(bytes);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override public MethodVisitor visitMethod(int access, String method, String descriptor,
                            String signature, String[] exceptions) {
                        MethodVisitor original = super.visitMethod(access, method, descriptor, signature, exceptions);
                        return new MethodVisitor(Opcodes.ASM9, original) {
                            @Override public void visitInsn(int opcode) {
                                if (constructor && method.equals("<init>") && opcode == Opcodes.RETURN) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitInsn(name.equals(PREFIX + "QueryScratch")
                                            ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "capture",
                                            "(Ljava/lang/Object;Z)V", false);
                                }
                                super.visitInsn(opcode);
                            }
                            @Override public void visitMethodInsn(int opcode, String owner, String called,
                                    String target, boolean isInterface) {
                                if (executor && method.equals("runTasks") && owner.equals("java/lang/ThreadLocal")
                                        && called.equals("set")) {
                                    super.visitInsn(Opcodes.DUP);
                                    super.visitInsn(Opcodes.ICONST_0);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "capture",
                                            "(Ljava/lang/Object;Z)V", false);
                                }
                                super.visitMethodInsn(opcode, owner, called, target, isInterface);
                                if (query && method.equals("queryImpl") && owner.equals("java/util/TreeMap")
                                        && called.equals("<init>") && target.equals("()V")) {
                                    super.visitInsn(Opcodes.DUP);
                                    super.visitInsn(Opcodes.ICONST_0);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "capture",
                                            "(Ljava/lang/Object;Z)V", false);
                                }
                                if (executor && owner.equals("java/lang/ThreadLocal") && called.equals("remove")) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT,
                                            method.equals("run") ? "clearThread" : "clearTemporary", "()V", false);
                                }
                            }
                        };
                    }
                }, 0);
                TRANSFORMED.add(name);
                return writer.toByteArray();
            }
        });
    }
    public static void capture(Object value, boolean scratch) {
        Thread thread = Thread.currentThread();
        if (!thread.getName().startsWith("accelerated-navigation-topology-")) return;
        Owner owner = OWNERS.computeIfAbsent(thread.getId(), ignored -> new Owner());
        synchronized (owner) {
            if (scratch && owner.scratch == null) owner.scratch = value;
            else owner.temporary.add(value);
        }
    }
    public static void clearTemporary() {
        Owner owner = OWNERS.get(Thread.currentThread().getId());
        if (owner != null) synchronized (owner) { owner.temporary.clear(); }
    }
    public static void clearThread() { OWNERS.remove(Thread.currentThread().getId()); }
    static List<Object> roots() {
        List<Object> result = new ArrayList<>();
        for (Owner owner : OWNERS.values()) synchronized (owner) {
            if (owner.scratch != null) result.add(owner.scratch);
            result.addAll(owner.temporary);
        }
        return result;
    }
    static long temporaryCount() {
        long result = 0;
        for (Owner owner : OWNERS.values()) synchronized (owner) { result += owner.temporary.size(); }
        return result;
    }
    static long shallow(Object value) {
        if (instrumentation == null) throw new IllegalStateException("ownership agent required");
        return instrumentation.getObjectSize(value);
    }
    static List<String> transformed() { return TRANSFORMED.stream().sorted().toList(); }
}
