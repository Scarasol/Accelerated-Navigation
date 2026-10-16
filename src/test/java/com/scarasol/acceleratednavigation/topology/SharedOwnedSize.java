package com.scarasol.acceleratednavigation.topology;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Identity-deduplicated reachable heap, with explicit external metadata exclusions. */
final class SharedOwnedSize {
    static Map<String,Object> measure(Object root, Set<Object> excluded) throws Exception {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object> pending = new ArrayDeque<>();
        Map<String,Long> types = new TreeMap<>(), counts = new TreeMap<>();
        pending.add(root);
        long bytes = 0;
        while (!pending.isEmpty()) {
            Object value = pending.remove();
            if (excluded.contains(value) || !seen.add(value) || value instanceof Thread
                    || value instanceof ClassLoader || value instanceof ThreadGroup
                    || value instanceof Enum<?> || value instanceof Class<?>
                    || value instanceof java.lang.reflect.Member
                    || value instanceof net.minecraft.resources.ResourceKey<?>) continue;
            if (seen.size() > 2_000_000) throw new IllegalStateException("unexpected ownership graph");
            long size = SharedOwnershipAgent.shallow(value);
            bytes += size;
            Class<?> type = value.getClass();
            types.merge(type.getName(),size,Long::sum);
            counts.merge(type.getName(),1L,Long::sum);
            if (type.isArray()) {
                if (!type.getComponentType().isPrimitive()) for (int index=0;index<Array.getLength(value);index++) {
                    Object child=Array.get(value,index);
                    if (child!=null) pending.add(child);
                }
            } else for (Class<?> current=type;current!=null&&current!=Object.class;current=current.getSuperclass())
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                    field.setAccessible(true);
                    Object child=field.get(value);
                    if (child!=null) pending.add(child);
                }
        }
        return Map.of("bytes",bytes,"objects",counts.values().stream().mapToLong(Long::longValue).sum(),
                "bytesByClass",types,"objectsByClass",counts);
    }
}
