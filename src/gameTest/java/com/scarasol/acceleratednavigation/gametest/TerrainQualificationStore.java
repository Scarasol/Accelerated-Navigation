package com.scarasol.acceleratednavigation.gametest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Test-only atomic handoff of one frozen world and its qualification directory. */
public final class TerrainQualificationStore {
    private static final String SLOT_A = "a";
    private static final String SLOT_B = "b";
    private static final String CANDIDATE_SUFFIX = ".candidate";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private TerrainQualificationStore() {
    }

    private static void promoteLocked(Path base) throws IOException {
        Path root = slotRoot(base);
        Path pointer = currentPointer(base);
        Path candidate = candidateRoot(base);
        if (!Files.isDirectory(candidate)
                || !Files.isRegularFile(candidate.resolve("manifest.json"))
                || !Files.isRegularFile(candidate.resolve("world").resolve("level.dat"))) {
            throw new IllegalStateException("staged terrain qualification batch is incomplete");
        }
        TerrainRouteManifest.loadGenerated(candidate.resolve("manifest.json"));
        String current = Files.isRegularFile(pointer) ? readCurrentPointer(pointer) : null;
        if (current != null && !SLOT_A.equals(current) && !SLOT_B.equals(current)) {
            throw new IllegalStateException("current terrain qualification pointer is invalid");
        }
        if (current != null && !completeSlot(root.resolve("slot-" + current))) {
            throw new IllegalStateException(
                    "current terrain qualification slot is incomplete: " + current);
        }
        String target = SLOT_A.equals(current) ? SLOT_B : SLOT_A;
        Path targetDirectory = root.resolve("slot-" + target);
        deleteTree(targetDirectory);
        Files.createDirectories(root);
        moveAtomically(candidate, targetDirectory);
        Path pointerTemporary = pointer.resolveSibling(pointer.getFileName() + ".tmp");
        try {
            Files.writeString(pointerTemporary, target + "\n", StandardCharsets.UTF_8);
            if (!target.equals(readCurrentPointer(pointerTemporary))) {
                throw new IllegalStateException(
                        "terrain qualification temporary pointer verification failed");
            }
            moveAtomically(pointerTemporary, pointer);
        } catch (IOException | RuntimeException failure) {
            cleanupTemporary(pointerTemporary, failure);
            throw failure;
        }
    }

    /** Gradle invokes this after the server process has exited successfully. */
    public static void main(String[] args) {
        if (args.length != 5 || !"finish".equals(args[0])) {
            throw new IllegalArgumentException(
                    "usage: finish <store-path> <qualification-report-path> <failure-report-path> <world-root>");
        }
        finish(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]));
    }

    private static void finish(Path storePath,
                               Path qualificationReportPath,
                               Path failureReportPath,
                               Path worldRoot) {
        if (Files.isRegularFile(failureReportPath)) {
            throw new IllegalStateException(
                    "terrain qualification already published a failure report: "
                            + failureReportPath.toAbsolutePath());
        }
        try {
            TerrainRouteManifest.Manifest manifest =
                    TerrainRouteManifest.loadGenerated(qualificationReportPath);
            if (manifest.selectionCoverage.isEmpty()) {
                throw new IllegalStateException("terrain qualification result has no selection coverage");
            }
        } catch (RuntimeException failure) {
            writeFailureReport(failureReportPath, "qualification_report", failure);
            throw failure;
        }

        Path base = storePath.toAbsolutePath().normalize();
        try {
            stageLocked(base, qualificationReportPath, worldRoot);
            promoteLocked(base);
        } catch (IOException failure) {
            RuntimeException wrapped = new IllegalStateException(
                    "could not hand off frozen terrain qualification batch", failure);
            writeFailureReport(failureReportPath, "input_handoff", wrapped);
            throw wrapped;
        } catch (RuntimeException failure) {
            writeFailureReport(failureReportPath, "input_handoff", failure);
            throw failure;
        }
    }

    private static void stageLocked(Path base,
                                    Path qualificationReportPath,
                                    Path worldRoot) throws IOException {
        Objects.requireNonNull(worldRoot, "worldRoot");
        Path candidate = candidateRoot(base);
        Path temporary = candidate.resolveSibling(candidate.getFileName() + ".tmp");
        try {
            deleteTree(temporary);
            Files.createDirectories(temporary.resolve("world"));
            copyTree(worldRoot, temporary.resolve("world"));
            Path stagedManifest = temporary.resolve("manifest.json");
            Files.copy(qualificationReportPath, stagedManifest,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            TerrainRouteManifest.loadGenerated(stagedManifest);
            if (Files.mismatch(qualificationReportPath, stagedManifest) != -1L) {
                throw new IllegalStateException(
                        "staged terrain qualification report differs from its candidate input");
            }
            deleteTree(candidate);
            moveAtomically(temporary, candidate);
        } catch (IOException | RuntimeException failure) {
            cleanupTemporary(temporary, failure);
            throw failure;
        }
    }

    static void writeFailureReport(Path reportPath,
                                   String failureStage,
                                   RuntimeException failure) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("state", "HARNESS_FAILED");
        report.put("failureStage", failureStage);
        report.put("fatalFailure", Map.of(
                "type", failure.getClass().getName(),
                "message", Objects.toString(failure.getMessage(), "promotion failed")));
        Path absolute = reportPath.toAbsolutePath().normalize();
        Path temporary = absolute.resolveSibling(absolute.getFileName() + ".tmp");
        try {
            Files.createDirectories(absolute.getParent());
            Files.writeString(temporary, GSON.toJson(report), StandardCharsets.UTF_8);
            moveAtomically(temporary, absolute);
        } catch (IOException | RuntimeException writeFailure) {
            cleanupTemporary(temporary, writeFailure);
            throw new IllegalStateException("could not write terrain qualification promotion failure report",
                    writeFailure);
        }
    }

    static TerrainRouteManifest.Manifest readPreparedManifest(Path manifestPath) {
        if (!Files.isRegularFile(manifestPath)) {
            throw new IllegalStateException("prepared terrain qualification manifest is missing: "
                    + manifestPath.toAbsolutePath());
        }
        return TerrainRouteManifest.load(manifestPath);
    }

    static Path slotRoot(Path reportPath) {
        return reportPath.toAbsolutePath().normalize()
                .resolveSibling(reportPath.getFileName() + ".slots");
    }

    static Path currentPointer(Path reportPath) {
        return reportPath.toAbsolutePath().normalize()
                .resolveSibling(reportPath.getFileName() + ".current");
    }

    static Path candidateRoot(Path reportPath) {
        return reportPath.toAbsolutePath().normalize()
                .resolveSibling(reportPath.getFileName() + CANDIDATE_SUFFIX);
    }

    private static String readCurrentPointer(Path pointer) throws IOException {
        return Files.readString(pointer, StandardCharsets.UTF_8).trim();
    }

    private static boolean completeSlot(Path slot) {
        Path manifest = slot.resolve("manifest.json");
        Path world = slot.resolve("world");
        if (!Files.isDirectory(slot)
                || !Files.isRegularFile(manifest)
                || !Files.isRegularFile(world.resolve("level.dat"))) {
            return false;
        }
        try {
            TerrainRouteManifest.loadGenerated(manifest);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            throw new IOException("atomic move is required for terrain qualification input handoff", ignored);
        }
    }

    private static void cleanupTemporary(Path temporary, Throwable failure) {
        try {
            deleteTree(temporary);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) {
            throw new IOException("frozen world root is not a directory: " + source);
        }
        if (!Files.isRegularFile(source.resolve("level.dat"))) {
            throw new IOException("frozen world root has no level.dat: " + source);
        }
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted().toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

}
