/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreDiagnosticsDirectory

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import org.apache.derby.iapi.store.types.DelosRawStoreIoDiagnosticsDirectory;

import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Non-owning diagnostics lookup for already booted RawStore-backed MVCC runtimes. */
final class MvccRawStoreDiagnosticsDirectory {
    private static final Map<String, WeakReference<MvccRawStoreRuntime>> BY_IDENTITY =
            new ConcurrentHashMap<>();
    private static final Set<WeakReference<MvccRawStoreRuntime>> ALL =
            ConcurrentHashMap.newKeySet();

    private MvccRawStoreDiagnosticsDirectory() {
    }

    static String fileIdentity(Path databaseDirectory) {
        return DelosRawStoreIoDiagnosticsDirectory.fileIdentity(databaseDirectory);
    }

    static String memoryIdentity(String canonicalName) {
        return DelosRawStoreIoDiagnosticsDirectory.memoryIdentity(canonicalName);
    }

    static void register(String databaseIdentity, MvccRawStoreRuntime runtime) {
        String identity = requireIdentity(databaseIdentity);
        WeakReference<MvccRawStoreRuntime> reference = new WeakReference<>(runtime);
        ALL.add(reference);
        BY_IDENTITY.put(identity, reference);
    }

    static void unregister(String databaseIdentity, MvccRawStoreRuntime runtime) {
        String identity = requireIdentity(databaseIdentity);
        BY_IDENTITY.computeIfPresent(identity, (ignored, reference) ->
                reference.get() == runtime ? null : reference);
        ALL.removeIf(reference -> {
            MvccRawStoreRuntime candidate = reference.get();
            return candidate == null || candidate == runtime;
        });
    }

    static MvccRawStoreRuntime require(String databaseIdentity) {
        String identity = requireIdentity(databaseIdentity);
        WeakReference<MvccRawStoreRuntime> reference = BY_IDENTITY.get(identity);
        MvccRawStoreRuntime runtime = reference == null ? null : reference.get();
        if (runtime == null) {
            if (reference != null) {
                BY_IDENTITY.remove(identity, reference);
                ALL.remove(reference);
            }
            throw new IllegalStateException(
                    "No active RawStore-backed delos_mvcc runtime for database " + identity);
        }
        return runtime;
    }

    static MvccRawStoreRuntime require(Path databaseDirectory) {
        return require(fileIdentity(databaseDirectory));
    }

    static MvccRawStoreRuntime requireSingle() {
        List<MvccRawStoreRuntime> runtimes = activeRuntimes();
        if (runtimes.size() != 1) {
            throw new IllegalStateException(
                    "RawStore MVCC diagnostics require an explicit database identity when "
                            + runtimes.size() + " runtimes are active");
        }
        return runtimes.get(0);
    }

    static boolean isActive(String databaseIdentity) {
        try {
            require(databaseIdentity);
            return true;
        } catch (IllegalStateException absent) {
            return false;
        }
    }

    static boolean isActive(Path databaseDirectory) {
        return isActive(fileIdentity(databaseDirectory));
    }

    static int runtimeCount() {
        return activeRuntimes().size();
    }

    static void clearForTesting(String databaseIdentity) {
        WeakReference<MvccRawStoreRuntime> reference =
                BY_IDENTITY.remove(requireIdentity(databaseIdentity));
        if (reference != null) {
            ALL.remove(reference);
        }
    }

    static void clearForTesting(Path databaseDirectory) {
        clearForTesting(fileIdentity(databaseDirectory));
    }

    static void clearAllForTesting() {
        BY_IDENTITY.clear();
        ALL.clear();
    }

    private static List<MvccRawStoreRuntime> activeRuntimes() {
        List<MvccRawStoreRuntime> active = new ArrayList<>();
        ALL.removeIf(reference -> {
            MvccRawStoreRuntime runtime = reference.get();
            if (runtime == null) {
                BY_IDENTITY.values().removeIf(candidate -> candidate == reference);
                return true;
            }
            active.add(runtime);
            return false;
        });
        return active;
    }

    private static String requireIdentity(String databaseIdentity) {
        if (databaseIdentity == null || databaseIdentity.isBlank()) {
            throw new IllegalArgumentException("database identity must not be blank");
        }
        return databaseIdentity;
    }
}
