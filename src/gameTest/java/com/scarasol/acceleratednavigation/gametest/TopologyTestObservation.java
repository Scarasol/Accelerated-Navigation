package com.scarasol.acceleratednavigation.gametest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared test-side interpretation of production activity and diagnostic inputs. */
final class TopologyTestObservation {

    private TopologyTestObservation() {
    }

    static boolean factsAndWorkersIdle(Map<String, Long> metrics) {
        return factsAndWorkersIdle(metrics, 0L);
    }

    static boolean factsAndWorkersIdle(Map<String, Long> metrics,
                                       long expectedActiveFlushes) {
        if (metric(metrics, "service.activeMacroRequests") != 0L
                || metric(metrics, "service.foregroundRecoveries") != 0L
                || metric(metrics, "service.ordinaryRecoveries") != 0L
                || metric(metrics, "service.pendingPersistenceCompletions") != 0L
                || metric(metrics, "service.pendingServerCallbacks") != 0L
                || metric(metrics, "worker.logicalRequests") != 0L
                || metric(metrics, "worker.endpointResolutions") != 0L
                || metric(metrics, "worker.physicalSearches") != 0L
                || metric(metrics, "worker.buildDemands") != 0L
                || metric(metrics, "worker.dependencyConsumers") != 0L
                || metric(metrics, "worker.liveSearchDependencies") != 0L
                || metric(metrics, "worker.admittedPrewarms") != 0L
                || metric(metrics, "worker.activeReferences") != 0L
                || metric(metrics, "worker.events.pendingKeys") != 0L
                || metric(metrics, "worker.events.activeBatchKeys") != 0L
                || metric(metrics, "worker.events.taskOutstanding") != 0L
                || metric(metrics, "worker.events.batchActive") != 0L
                || metric(metrics, "persistence.pendingChunks") != 0L
                || metric(metrics, "persistence.readsInFlight") != 0L
                || metric(metrics, "persistence.queuedReadTasks") != 0L
                || metric(metrics, "persistence.queuedWriteOrFlushTasks") != 0L
                || metric(metrics, "persistence.requestedFlushes") != 0L
                || metric(metrics, "persistence.queuedFlushes") != 0L
                || metric(metrics, "persistence.activeFlushes") != expectedActiveFlushes) {
            return false;
        }
        for (String kind : List.of("control", "builds", "quickSearches", "longSearches", "prewarms")) {
            if (metric(metrics, "worker.tasks.queued." + kind) != 0L
                    || metric(metrics, "worker.tasks.running." + kind) != 0L) {
                return false;
            }
        }
        return true;
    }

    static Map<String, Object> sourceIdentity() {
        Path projectRoot = configuredPath("acceleratedNavigation.projectRoot",
                "project root is not configured");
        return identityFromInputs(projectRoot, "production inputs", List.of(
                configuredPath("acceleratedNavigation.sourceRoot",
                        "production source root is not configured"),
                configuredPath("acceleratedNavigation.productionResourcesRoot",
                        "production resource root is not configured"),
                configuredPath("acceleratedNavigation.buildScript",
                        "build script is not configured"),
                configuredPath("acceleratedNavigation.settingsScript",
                        "settings script is not configured"),
                configuredPath("acceleratedNavigation.gradleProperties",
                        "Gradle properties are not configured")));
    }

    static Map<String, Object> fixtureIdentity() {
        Path projectRoot = configuredPath("acceleratedNavigation.projectRoot",
                "project root is not configured");
        return identityFromInputs(projectRoot, "GameTest and run inputs", List.of(
                configuredPath("acceleratedNavigation.gameTestRoot",
                        "GameTest source root is not configured"),
                configuredPath("acceleratedNavigation.fixtureResourcesRoot",
                        "GameTest resource root is not configured"),
                configuredPath("acceleratedNavigation.buildScript",
                        "build script is not configured"),
                configuredPath("acceleratedNavigation.settingsScript",
                        "settings script is not configured"),
                configuredPath("acceleratedNavigation.gradleProperties",
                        "Gradle properties are not configured")));
    }

    private static Path configuredPath(String property, String missingMessage) {
        String configured = System.getProperty(property);
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(missingMessage);
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private static Map<String, Object> identityFromInputs(Path projectRoot,
                                                           String rootLabel,
                                                           List<Path> inputs) {
        Path root = projectRoot.toAbsolutePath().normalize();
        Map<String, Path> filesByRelative = new LinkedHashMap<>();
        for (Path input : inputs) {
            if (Files.isDirectory(input)) {
                try (var paths = Files.walk(input)) {
                    paths.filter(Files::isRegularFile).forEach(path ->
                            addIdentityFile(root, filesByRelative, path));
                } catch (IOException failure) {
                    throw new IllegalStateException(
                            "could not enumerate " + rootLabel + " " + input, failure);
                }
            } else if (Files.isRegularFile(input)) {
                addIdentityFile(root, filesByRelative, input);
            } else {
                throw new IllegalStateException(
                        "identity input does not exist: " + input);
            }
        }
        List<Map.Entry<String, Path>> files = filesByRelative.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        MessageDigest aggregate = digest();
        List<Map<String, Object>> identities = new ArrayList<>();
        for (Map.Entry<String, Path> entry : files) {
            Path file = entry.getValue();
            try {
                byte[] bytes = Files.readAllBytes(file);
                String relative = entry.getKey();
                String hash = HexFormat.of().formatHex(digest().digest(bytes));
                aggregate.update(relative.getBytes(StandardCharsets.UTF_8));
                aggregate.update((byte) 0);
                aggregate.update(hash.getBytes(StandardCharsets.US_ASCII));
                identities.add(Map.of(
                        "path", relative,
                        "physicalLines", Files.readAllLines(file, StandardCharsets.UTF_8).size(),
                        "sha256", hash));
            } catch (IOException failure) {
                throw new IllegalStateException("could not identify " + rootLabel + " " + file,
                        failure);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("method", "filesystem SHA-256; no Git metadata");
        result.put("rootKind", rootLabel);
        result.put("projectRoot", root.toString());
        result.put("fileCount", identities.size());
        result.put("aggregateSha256", HexFormat.of().formatHex(aggregate.digest()));
        result.put("files", identities);
        return result;
    }

    private static void addIdentityFile(Path projectRoot,
                                        Map<String, Path> filesByRelative,
                                        Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        Path relative = projectRoot.relativize(normalized);
        if (relative.startsWith("..") || relative.toString().equals("..")) {
            throw new IllegalStateException(
                    "identity input is outside project root: " + normalized);
        }
        filesByRelative.put(normalize(relative), normalized);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static long metric(Map<String, Long> metrics, String key) {
        return metrics.getOrDefault(key, 0L);
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }
}
