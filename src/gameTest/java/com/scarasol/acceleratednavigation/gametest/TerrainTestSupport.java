package com.scarasol.acceleratednavigation.gametest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared test-only report and failure formatting with no production dependency. */
final class TerrainTestSupport {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private TerrainTestSupport() {
    }

    static void writeReport(Path reportPath, Map<String, Object> report) {
        Path parent = reportPath.toAbsolutePath().getParent();
        Path temporary = reportPath.resolveSibling(reportPath.getFileName() + ".tmp");
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(temporary, GSON.toJson(report), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, reportPath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("atomic move is required for terrain test reports", unsupported);
            }
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw new IllegalStateException("could not write terrain test report", failure);
        }
    }

    static Map<String, Object> failureSummary(Throwable failure) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", failure.getClass().getName());
        result.put("message", failure.getMessage());
        StackTraceElement[] trace = failure.getStackTrace();
        List<String> frames = new ArrayList<>();
        for (int index = 0; index < Math.min(12, trace.length); index++) {
            frames.add(trace[index].toString());
        }
        result.put("topFrames", frames);
        return result;
    }
}
