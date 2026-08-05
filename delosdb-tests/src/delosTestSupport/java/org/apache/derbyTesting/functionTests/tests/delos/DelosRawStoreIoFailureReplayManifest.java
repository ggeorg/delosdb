/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.DelosRawStoreIoFailureReplayManifest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Test-only immutable evidence for one deterministic I/O failure replay. */
public record DelosRawStoreIoFailureReplayManifest(
        int schemaVersion,
        int faultRegistryVersion,
        String sourceRevision,
        String environment,
        long seed,
        String databaseIdentity,
        String topology,
        String schedule,
        String invariant,
        String expectedDigest,
        String observedDigest,
        int replayCount,
        long reachedFaultPoints) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public DelosRawStoreIoFailureReplayManifest {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported RawStore I/O replay manifest schema "
                            + schemaVersion);
        }
        if (faultRegistryVersion <= 0) {
            throw new IllegalArgumentException(
                    "faultRegistryVersion must be positive");
        }
        sourceRevision = requireText(sourceRevision, "sourceRevision");
        environment = requireText(environment, "environment");
        databaseIdentity = requireText(databaseIdentity, "databaseIdentity");
        topology = requireText(topology, "topology");
        schedule = requireText(schedule, "schedule");
        invariant = requireText(invariant, "invariant");
        expectedDigest = requireDigest(expectedDigest, "expectedDigest");
        observedDigest = requireDigest(observedDigest, "observedDigest");
        if (replayCount <= 0) {
            throw new IllegalArgumentException("replayCount must be positive");
        }
        if (reachedFaultPoints <= 0L) {
            throw new IllegalArgumentException(
                    "reachedFaultPoints must be positive");
        }
    }

    public boolean matchesExpectedState() {
        return expectedDigest.equals(observedDigest);
    }

    public String toText() {
        StringBuilder text = new StringBuilder();
        append(text, "schemaVersion", Integer.toString(schemaVersion));
        append(text, "faultRegistryVersion", Integer.toString(faultRegistryVersion));
        append(text, "sourceRevision", encode(sourceRevision));
        append(text, "environment", encode(environment));
        append(text, "seed", Long.toString(seed));
        append(text, "databaseIdentity", encode(databaseIdentity));
        append(text, "topology", encode(topology));
        append(text, "schedule", encode(schedule));
        append(text, "invariant", encode(invariant));
        append(text, "expectedDigest", expectedDigest);
        append(text, "observedDigest", observedDigest);
        append(text, "replayCount", Integer.toString(replayCount));
        append(text, "reachedFaultPoints", Long.toString(reachedFaultPoints));
        return text.toString();
    }

    public static DelosRawStoreIoFailureReplayManifest parse(String text) {
        Objects.requireNonNull(text, "text");
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0 || separator == line.length() - 1) {
                throw new IllegalArgumentException(
                        "invalid RawStore I/O replay manifest line: " + line);
            }
            String previous = values.put(
                    line.substring(0, separator), line.substring(separator + 1));
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate RawStore I/O replay manifest key: "
                                + line.substring(0, separator));
            }
        }
        String[] required = {
                "schemaVersion",
                "faultRegistryVersion",
                "sourceRevision",
                "environment",
                "seed",
                "databaseIdentity",
                "topology",
                "schedule",
                "invariant",
                "expectedDigest",
                "observedDigest",
                "replayCount",
                "reachedFaultPoints"
        };
        for (String key : required) {
            if (!values.containsKey(key)) {
                throw new IllegalArgumentException(
                        "missing RawStore I/O replay manifest key: " + key);
            }
        }
        if (values.size() != required.length) {
            throw new IllegalArgumentException(
                    "unknown RawStore I/O replay manifest key");
        }
        return new DelosRawStoreIoFailureReplayManifest(
                Integer.parseInt(values.get("schemaVersion")),
                Integer.parseInt(values.get("faultRegistryVersion")),
                decode(values.get("sourceRevision")),
                decode(values.get("environment")),
                Long.parseLong(values.get("seed")),
                decode(values.get("databaseIdentity")),
                decode(values.get("topology")),
                decode(values.get("schedule")),
                decode(values.get("invariant")),
                values.get("expectedDigest"),
                values.get("observedDigest"),
                Integer.parseInt(values.get("replayCount")),
                Long.parseLong(values.get("reachedFaultPoints")));
    }

    private static void append(StringBuilder target, String key, String value) {
        target.append(key).append('=').append(value).append('\n');
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(
                Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String requireDigest(String value, String name) {
        String normalized = requireText(value, name);
        if (!SHA_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    name + " must be a lowercase SHA-256 digest");
        }
        return normalized;
    }
}
