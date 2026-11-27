package com.scarasol.acceleratednavigation.topology;

import java.util.concurrent.TimeUnit;

/** Runs with every JNA jar removed from the child JVM's classpath. */
public final class CpuIndependentStartupContracts {
    public static void main(String[] args) throws Exception {
        try {
            Class.forName("com.sun.jna.Native", false, CpuIndependentStartupContracts.class.getClassLoader());
            throw new AssertionError("JNA was present in the CPU independence control");
        } catch (ClassNotFoundException expected) {}
        var runtime = new TopologyWorkerRuntime((key, identity, delta) -> {}, ignored -> {});
        if (!runtime.awaitStopped(10, TimeUnit.SECONDS)) throw new AssertionError("CPU runtime did not stop");
        CoreContracts.main(new String[]{"reverse-runtime"});
        System.out.println("CPU independent startup and navigation passed without JNA");
    }
}
