package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Versioned, deterministic manifest for one internal failure/replay experiment. */
final class MvccFailureExperimentManifest {
    static final int MANIFEST_SCHEMA_VERSION = 1;

    private final String experimentId;
    private final String databaseIdentity;
    private final MvccFailurePointRegistry.Schedule schedule;
    private final String expectedInvariant;
    private final String expectedFinalStateDigest;
    private final String observedFinalStateDigest;

    MvccFailureExperimentManifest(
            String experimentId,
            String databaseIdentity,
            MvccFailurePointRegistry.Schedule schedule,
            String expectedInvariant,
            String expectedFinalStateDigest,
            String observedFinalStateDigest) {
        this.experimentId = requireText(experimentId, "experimentId");
        this.databaseIdentity = requireText(databaseIdentity, "databaseIdentity");
        this.schedule = Objects.requireNonNull(schedule, "schedule");
        this.expectedInvariant = requireText(expectedInvariant, "expectedInvariant");
        this.expectedFinalStateDigest = requireDigest(
                expectedFinalStateDigest, "expectedFinalStateDigest");
        this.observedFinalStateDigest = requireDigest(
                observedFinalStateDigest, "observedFinalStateDigest");
    }

    String experimentId() {
        return experimentId;
    }

    String databaseIdentity() {
        return databaseIdentity;
    }

    MvccFailurePointRegistry.Schedule schedule() {
        return schedule;
    }

    String expectedInvariant() {
        return expectedInvariant;
    }

    String expectedFinalStateDigest() {
        return expectedFinalStateDigest;
    }

    String observedFinalStateDigest() {
        return observedFinalStateDigest;
    }

    void write(Path path) {
        Objects.requireNonNull(path, "path");
        List<String> lines = new ArrayList<>();
        lines.add("manifest.schema=" + MANIFEST_SCHEMA_VERSION);
        lines.add("registry.version=" + schedule.registryVersion());
        lines.add("experiment.id=" + encode(experimentId));
        lines.add("database.identity=" + encode(databaseIdentity));
        lines.add("schedule.id=" + encode(schedule.id()));
        lines.add("schedule.seed=" + schedule.seed());
        lines.add("schedule.count=" + schedule.steps().size());
        for (int index = 0; index < schedule.steps().size(); index++) {
            MvccFailurePointRegistry.Step step = schedule.steps().get(index);
            String prefix = "schedule." + index + ".";
            lines.add(prefix + "point=" + step.point().name());
            lines.add(prefix + "occurrence=" + step.occurrence());
            lines.add(prefix + "action=" + step.action().name());
            lines.add(prefix + "haltStatus=" + step.haltStatus());
        }
        lines.add("expected.invariant=" + encode(expectedInvariant));
        lines.add("expected.digest=" + expectedFinalStateDigest);
        lines.add("observed.digest=" + observedFinalStateDigest);
        try {
            Path parent = path.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write MVCC failure manifest " + path, e);
        }
    }

    static MvccFailureExperimentManifest read(Path path) {
        Objects.requireNonNull(path, "path");
        Map<String, String> values = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                int separator = line.indexOf('=');
                if (separator <= 0) {
                    throw new IllegalStateException("Malformed MVCC failure manifest line: " + line);
                }
                values.put(line.substring(0, separator), line.substring(separator + 1));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read MVCC failure manifest " + path, e);
        }

        int manifestSchema = parseInt(values, "manifest.schema");
        if (manifestSchema != MANIFEST_SCHEMA_VERSION) {
            throw new IllegalStateException(
                    "Unsupported MVCC failure manifest schema " + manifestSchema);
        }
        int registryVersion = parseInt(values, "registry.version");
        String scheduleId = decode(required(values, "schedule.id"));
        long seed = parseLong(values, "schedule.seed");
        int count = parseInt(values, "schedule.count");
        List<MvccFailurePointRegistry.Step> steps = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String prefix = "schedule." + index + ".";
            MvccFailurePointRegistry.Point point = MvccFailurePointRegistry.Point.valueOf(
                    required(values, prefix + "point"));
            long occurrence = parseLong(values, prefix + "occurrence");
            MvccFailurePointRegistry.Action action = MvccFailurePointRegistry.Action.valueOf(
                    required(values, prefix + "action"));
            int haltStatus = parseInt(values, prefix + "haltStatus");
            steps.add(new MvccFailurePointRegistry.Step(
                    point,
                    occurrence,
                    action,
                    haltStatus,
                    MvccFailurePointRegistry.Barrier.none()));
        }
        MvccFailurePointRegistry.Schedule schedule = new MvccFailurePointRegistry.Schedule(
                registryVersion, scheduleId, seed, steps);
        return new MvccFailureExperimentManifest(
                decode(required(values, "experiment.id")),
                decode(required(values, "database.identity")),
                schedule,
                decode(required(values, "expected.invariant")),
                required(values, "expected.digest"),
                required(values, "observed.digest"));
    }

    static String digest(List<String> canonicalState) {
        Objects.requireNonNull(canonicalState, "canonicalState");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : canonicalState) {
                byte[] encoded = Objects.requireNonNull(value, "canonical state value")
                        .getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(encoded.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(encoded);
                digest.update((byte) '\n');
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalStateException("Missing MVCC failure manifest key " + key);
        }
        return value;
    }

    private static int parseInt(Map<String, String> values, String key) {
        return Integer.parseInt(required(values, key));
    }

    private static long parseLong(Map<String, String> values, String key) {
        return Long.parseLong(required(values, key));
    }

    private static String requireText(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return normalized;
    }

    private static String requireDigest(String value, String label) {
        String digest = requireText(value, label).toLowerCase(java.util.Locale.ROOT);
        if (!digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " must be a SHA-256 hex digest");
        }
        return digest;
    }
}
