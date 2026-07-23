/*

   Derby - Class org.apache.derby.iapi.store.types.DelosRawStoreIoDiagnosticsDirectory

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derby.iapi.store.types;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Weak active lookup plus bounded terminal snapshots for RawStore I/O counters. */
public final class DelosRawStoreIoDiagnosticsDirectory {
    private static final int MAX_TERMINAL_SNAPSHOTS = 64;
    private static final Map<String, WeakReference<DelosRawStoreIoMetrics>> BY_IDENTITY =
            new ConcurrentHashMap<>();
    private static final Map<String, DelosRawStoreIoSnapshot> TERMINAL_SNAPSHOTS =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<String, DelosRawStoreIoSnapshot> eldest) {
                    return size() > MAX_TERMINAL_SNAPSHOTS;
                }
            };

    private DelosRawStoreIoDiagnosticsDirectory() {
    }

    public static String fileIdentity(Path databaseDirectory) {
        Path absolute = databaseDirectory.toAbsolutePath().normalize();
        try {
            absolute = absolute.toRealPath();
        } catch (IOException ignored) {
            // A newly created or just-shut-down database may not resolve.
        }
        return "file:" + absolute;
    }

    public static String memoryIdentity(String canonicalName) {
        if (canonicalName == null || canonicalName.isBlank()) {
            throw new IllegalArgumentException(
                    "memory database identity must not be blank");
        }
        return canonicalName.startsWith("memory:")
                ? canonicalName
                : "memory:" + canonicalName;
    }

    public static void register(String identity, DelosRawStoreIoMetrics metrics) {
        String normalized = requireIdentity(identity);
        BY_IDENTITY.put(normalized, new WeakReference<>(metrics));
        synchronized (TERMINAL_SNAPSHOTS) {
            TERMINAL_SNAPSHOTS.remove(normalized);
        }
    }

    public static void unregister(String identity, DelosRawStoreIoMetrics metrics) {
        String normalized = requireIdentity(identity);
        WeakReference<DelosRawStoreIoMetrics> reference = BY_IDENTITY.get(normalized);
        if (reference == null || reference.get() != metrics) {
            return;
        }
        DelosRawStoreIoSnapshot terminal = metrics.snapshot();
        synchronized (TERMINAL_SNAPSHOTS) {
            TERMINAL_SNAPSHOTS.put(normalized, terminal);
        }
        if (!BY_IDENTITY.remove(normalized, reference)) {
            synchronized (TERMINAL_SNAPSHOTS) {
                TERMINAL_SNAPSHOTS.remove(normalized, terminal);
            }
        }
    }

    public static DelosRawStoreIoSnapshot snapshot(String identity) {
        String normalized = requireIdentity(identity);
        WeakReference<DelosRawStoreIoMetrics> reference = BY_IDENTITY.get(normalized);
        DelosRawStoreIoMetrics metrics = reference == null ? null : reference.get();
        if (metrics != null) {
            return metrics.snapshot();
        }
        if (reference != null) {
            BY_IDENTITY.remove(normalized, reference);
        }
        synchronized (TERMINAL_SNAPSHOTS) {
            DelosRawStoreIoSnapshot terminal = TERMINAL_SNAPSHOTS.get(normalized);
            if (terminal != null) {
                return terminal;
            }
        }
        throw new IllegalStateException(
                "No RawStore I/O diagnostics for database " + normalized);
    }

    public static DelosRawStoreIoSnapshot snapshot(Path databaseDirectory) {
        return snapshot(fileIdentity(databaseDirectory));
    }

    private static String requireIdentity(String identity) {
        if (identity == null || identity.isBlank()) {
            throw new IllegalArgumentException("database identity must not be blank");
        }
        return identity;
    }
}
