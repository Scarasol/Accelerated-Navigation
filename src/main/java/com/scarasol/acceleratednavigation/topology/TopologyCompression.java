package com.scarasol.acceleratednavigation.topology;

import net.minecraft.world.level.chunk.storage.RegionFileVersion;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.util.zip.DeflaterOutputStream;

/** Only topology files use this descriptor; Minecraft's registry stays unchanged. */
final class TopologyCompression {
    static final RegionFileVersion VERSION = new RegionFileVersion(
            2, RegionFileVersion.VERSION_DEFLATE::wrap,
            output -> new BufferedOutputStream(new FactsOutputStream(output)));

    private TopologyCompression() {
    }

    private static final class FactsOutputStream extends DeflaterOutputStream {
        private FactsOutputStream(OutputStream output) {
            // The default constructor retains ownership and exception-safe native cleanup.
            super(output);
            def.setLevel(5);
        }
    }
}
