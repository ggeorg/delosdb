/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreDiagnosticsDirectory

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Non-owning diagnostics lookup for already booted RawStore-backed MVCC runtimes. */
final class MvccRawStoreDiagnosticsDirectory {
    private static final Map<Path, WeakReference<MvccRawStoreRuntime>> BY_DIRECTORY =
            new ConcurrentHashMap<>();
    private static final Set<WeakReference<MvccRawStoreRuntime>> ALL =
            ConcurrentHashMap.newKeySet();

    private MvccRawStoreDiagnosticsDirectory() {
    }

    static void register(Path databaseDirectory, MvccRawStoreRuntime runtime) {
        WeakReference<MvccRawStoreRuntime> reference = new WeakReference<>(runtime);
        ALL.add(reference);
        if (databaseDirectory != null) {
            BY_DIRECTORY.put(normalize(databaseDirectory), reference);
        }
    }

    static void unregister(Path databaseDirectory, MvccRawStoreRuntime runtime) {
        if (databaseDirectory != null) {
            Path identity = normalize(databaseDirectory);
            BY_DIRECTORY.computeIfPresent(identity, (ignored, reference) ->
                    reference.get() == runtime ? null : reference);
        }
        ALL.removeIf(reference -> {
            MvccRawStoreRuntime candidate = reference.get();
            return candidate == null || candidate == runtime;
        });
    }

    static MvccRawStoreRuntime require(Path databaseDirectory) {
        Path identity = normalize(databaseDirectory);
        WeakReference<MvccRawStoreRuntime> reference = BY_DIRECTORY.get(identity);
        MvccRawStoreRuntime runtime = reference == null ? null : reference.get();
        if (runtime == null) {
            if (reference != null) {
                BY_DIRECTORY.remove(identity, reference);
                ALL.remove(reference);
            }
            throw new IllegalStateException(
                    "No active RawStore-backed delos_mvcc runtime for database " + identity);
        }
        return runtime;
    }

    static MvccRawStoreRuntime requireSingle() {
        List<MvccRawStoreRuntime> runtimes = activeRuntimes();
        if (runtimes.size() != 1) {
            throw new IllegalStateException(
                    "RawStore MVCC maintenance diagnostics require an explicit database directory when "
                            + runtimes.size() + " runtimes are active");
        }
        return runtimes.get(0);
    }

    static boolean isActive(Path databaseDirectory) {
        try {
            require(databaseDirectory);
            return true;
        } catch (IllegalStateException absent) {
            return false;
        }
    }

    static int runtimeCount() {
        return activeRuntimes().size();
    }

    static void clearForTesting(Path databaseDirectory) {
        WeakReference<MvccRawStoreRuntime> reference = BY_DIRECTORY.remove(normalize(databaseDirectory));
        if (reference != null) {
            ALL.remove(reference);
        }
    }

    static void clearAllForTesting() {
        BY_DIRECTORY.clear();
        ALL.clear();
    }

    private static List<MvccRawStoreRuntime> activeRuntimes() {
        List<MvccRawStoreRuntime> active = new ArrayList<>();
        ALL.removeIf(reference -> {
            MvccRawStoreRuntime runtime = reference.get();
            if (runtime == null) {
                return true;
            }
            active.add(runtime);
            return false;
        });
        return active;
    }

    private static Path normalize(Path databaseDirectory) {
        Path absolute = databaseDirectory.toAbsolutePath().normalize();
        try {
            return absolute.toRealPath();
        } catch (IOException ignored) {
            return absolute;
        }
    }
}
