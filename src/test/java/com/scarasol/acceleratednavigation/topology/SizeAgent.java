package com.scarasol.acceleratednavigation.topology;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.util.*;

public final class SizeAgent {
    private static Instrumentation instrumentation;
    public static void premain(String args, Instrumentation agent) { instrumentation = agent; }
    static long deep(Object root, Set<Object> excluded) throws Exception {
        if (instrumentation == null) throw new IllegalStateException("size agent required");
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object> queue = new ArrayDeque<>(); queue.add(root); long bytes = 0;
        while (!queue.isEmpty()) {
            Object object = queue.remove();
            if (excluded.contains(object) || !seen.add(object) || object instanceof Enum<?>
                    || object instanceof Class<?> || object instanceof Method || object instanceof Field) continue;
            bytes += instrumentation.getObjectSize(object);
            Class<?> type = object.getClass();
            if (type.isArray()) {
                if (!type.getComponentType().isPrimitive()) for (int i = 0; i < Array.getLength(object); i++) {
                    Object child = Array.get(object, i); if (child != null) queue.add(child);
                }
                continue;
            }
            if (type.getName().startsWith("java.lang.")) continue;
            for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass())
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                    field.setAccessible(true); Object child = field.get(object); if (child != null) queue.add(child);
                }
        }
        return bytes;
    }
}
