package com.scarasol.acceleratednavigation.topology;

import com.google.gson.stream.JsonWriter;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/** A bounded, versioned export of actual derived base graphs for offline inspection. */
final class BaseGraphCapture {
    static final int MAX_DISTANCE = 384;

    private BaseGraphCapture() {
    }

    static List<SectionPos> sections(Vec3 center, int distance, int minBuildY, int maxBuildY) {
        if (distance < 1 || distance > MAX_DISTANCE) throw new IllegalArgumentException("invalid capture distance");
        int minX = Math.floorDiv((int) Math.floor(center.x - distance), 16);
        int maxX = Math.floorDiv((int) Math.floor(center.x + distance), 16);
        int minY = Math.max(Math.floorDiv(minBuildY, 16),
                Math.floorDiv((int) Math.floor(center.y - distance), 16));
        int maxY = Math.min(Math.floorDiv(maxBuildY - 1, 16),
                Math.floorDiv((int) Math.floor(center.y + distance), 16));
        int minZ = Math.floorDiv((int) Math.floor(center.z - distance), 16);
        int maxZ = Math.floorDiv((int) Math.floor(center.z + distance), 16);
        double radiusSquared = (double) distance * distance;
        List<SectionPos> result = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    double dx = distanceToBox(center.x, x);
                    double dy = distanceToBox(center.y, y);
                    double dz = distanceToBox(center.z, z);
                    if (dx * dx + dy * dy + dz * dz <= radiusSquared) {
                        result.add(SectionPos.of(x, y, z));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private static double distanceToBox(double coordinate, int section) {
        double minimum = (double) section * BaseClusterTopology.SIDE;
        return Math.max(0.0D, Math.max(minimum - coordinate,
                coordinate - (minimum + BaseClusterTopology.SIDE)));
    }

    /** A single export owns one temporary file and does not accumulate exported graphs. */
    static Writer open(Path destination, String dimension, Vec3 center, int distance) throws IOException {
        return new Writer(destination, dimension, center, distance);
    }

    static final class Writer implements AutoCloseable {
        private final Path destination;
        private final Path temporary;
        private final JsonWriter json;
        private int ready;
        private int unloaded;
        private int failed;
        private boolean closed;
        private boolean published;

        private Writer(Path destination, String dimension, Vec3 center, int distance) throws IOException {
            this.destination = destination;
            Files.createDirectories(destination.getParent());
            this.temporary = destination.resolveSibling(destination.getFileName() + ".part");
            this.json = new JsonWriter(new BufferedWriter(new OutputStreamWriter(
                    new GZIPOutputStream(Files.newOutputStream(temporary)), StandardCharsets.UTF_8)));
            try {
                json.beginObject();
                json.name("format").value("accelerated-navigation-base-graph");
                json.name("version").value(1);
                json.name("factsAlgorithmVersion").value(BaseClusterTopology.FACTS_ALGORITHM_VERSION);
                json.name("generatedAtUtc").value(Instant.now().toString());
                json.name("dimension").value(dimension);
                json.name("center").beginArray().value(center.x).value(center.y).value(center.z).endArray();
                json.name("distanceBlocks").value(distance);
                json.name("selection").value("3D sphere intersects section block volume; no chunk loading");
                json.name("completenessScope").value("requested sphere only; outside sections are unknown");
                BaseClusterTopology.TraversalProfile profile =
                        BaseClusterTopology.TraversalProfile.DEFAULT_GROUND;
                json.name("profile").beginObject();
                json.name("channel").value("GROUND");
                json.name("width").value(profile.width());
                json.name("height").value(profile.height());
                json.name("maxStep").value(profile.maxStep());
                json.name("maxJump").value(profile.maxJump());
                json.name("maxDrop").value(profile.maxDrop());
                json.name("acceptsFluid").value(profile.acceptsFluid());
                json.name("canOpenDoors").value(profile.canOpenDoors());
                json.endObject();
                json.name("sections").beginArray();
            } catch (IOException | RuntimeException failure) {
                try {
                    close();
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        void add(Entry entry) throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("graph export interrupted");
            json.beginObject();
            json.name("section").beginArray().value(entry.section().x())
                    .value(entry.section().y()).value(entry.section().z()).endArray();
            json.name("state").value(entry.state());
            if (entry.graph() != null) {
                json.name("graph");
                entry.graph().writeSnapshot(json);
            }
            if ("READY".equals(entry.state())) ready++;
            else if ("UNLOADED".equals(entry.state())) unloaded++;
            else failed++;
            if (entry.reason() != null) json.name("reason").value(entry.reason());
            json.endObject();
        }

        Result finish(boolean stable) throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("graph export interrupted");
            json.endArray();
            boolean complete = stable && unloaded == 0 && failed == 0;
            json.name("stableDuringCapture").value(stable);
            json.name("complete").value(complete);
            json.name("readyCount").value(ready);
            json.name("unloadedCount").value(unloaded);
            json.name("failedCount").value(failed);
            json.endObject();
            json.close();
            closed = true;
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("graph export interrupted");
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination);
            }
            published = true;
            return new Result(destination, ready, unloaded, failed, stable, complete);
        }

        @Override
        public void close() throws IOException {
            try {
                if (!closed) {
                    closed = true;
                    json.close();
                }
            } finally {
                if (!published) Files.deleteIfExists(temporary);
            }
        }
    }

    record Entry(SectionPos section, String state, @Nullable BaseClusterTopology graph,
                 @Nullable String reason) {
    }

    record Result(Path path, int ready, int unloaded, int failed, boolean stable, boolean complete) {
    }
}
