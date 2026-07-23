/*

   Derby - Class org.apache.derby.impl.store.raw.data.DelosRawStoreIoFaultInjectionDirectory

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.raw.data;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Same-package, test-only access to active database-scoped I/O fault seams. */
final class DelosRawStoreIoFaultInjectionDirectory {
    private static final int TERMINAL_SNAPSHOT_LIMIT = 64;
    private static final Map<String, WeakReference<DelosRawStoreIoFaultInjector>> ACTIVE =
            new ConcurrentHashMap<>();
    private static final Map<String, DelosRawStoreIoFaultInjector.Snapshot> TERMINAL =
            new LinkedHashMap<>(TERMINAL_SNAPSHOT_LIMIT, 0.75f, true);

    private DelosRawStoreIoFaultInjectionDirectory() {
    }

    static void register(
            String databaseIdentity,
            DelosRawStoreIoFaultInjector injector) {
        String identity = requireIdentity(databaseIdentity);
        DelosRawStoreIoFaultInjector required = Objects.requireNonNull(
                injector, "injector");
        WeakReference<DelosRawStoreIoFaultInjector> replacement =
                new WeakReference<>(required);
        WeakReference<DelosRawStoreIoFaultInjector> previous =
                ACTIVE.putIfAbsent(identity, replacement);
        DelosRawStoreIoFaultInjector existing =
                previous == null ? null : previous.get();
        if (existing != null && existing != required) {
            throw new IllegalStateException(
                    "RawStore I/O fault injector already registered for " + identity);
        }
        if (previous != null && existing == null) {
            ACTIVE.replace(identity, previous, replacement);
        }
        synchronized (TERMINAL) {
            TERMINAL.remove(identity);
        }
    }

    static void unregister(
            String databaseIdentity,
            DelosRawStoreIoFaultInjector injector) {
        String identity = requireIdentity(databaseIdentity);
        DelosRawStoreIoFaultInjector required = Objects.requireNonNull(
                injector, "injector");
        DelosRawStoreIoFaultInjector.Snapshot terminal = required.snapshot();
        synchronized (TERMINAL) {
            TERMINAL.put(identity, terminal);
            while (TERMINAL.size() > TERMINAL_SNAPSHOT_LIMIT) {
                String eldest = TERMINAL.keySet().iterator().next();
                TERMINAL.remove(eldest);
            }
        }
        ACTIVE.computeIfPresent(identity, (ignored, reference) ->
                reference.get() == required ? null : reference);
    }

    static void installThrowForTesting(
            String databaseIdentity,
            String scheduleId,
            String pointName,
            long occurrence) {
        active(databaseIdentity).installForTesting(
                DelosRawStoreIoFaultInjector.Schedule.of(
                        scheduleId,
                        DelosRawStoreIoFaultInjector.Step.fail(
                                DelosRawStoreIoFaultInjector.Point.valueOf(pointName),
                                occurrence)));
    }

    static void installHaltForTesting(
            String databaseIdentity,
            String scheduleId,
            String pointName,
            long occurrence,
            int haltStatus) {
        active(databaseIdentity).installForTesting(
                DelosRawStoreIoFaultInjector.Schedule.of(
                        scheduleId,
                        DelosRawStoreIoFaultInjector.Step.halt(
                                DelosRawStoreIoFaultInjector.Point.valueOf(pointName),
                                occurrence,
                                haltStatus)));
    }

    static void clearForTesting(String databaseIdentity) {
        active(databaseIdentity).clearForTesting();
    }

    static DelosRawStoreIoFaultInjector.Snapshot snapshotForTesting(
            String databaseIdentity) {
        String identity = requireIdentity(databaseIdentity);
        WeakReference<DelosRawStoreIoFaultInjector> reference = ACTIVE.get(identity);
        DelosRawStoreIoFaultInjector active = reference == null ? null : reference.get();
        if (active != null) {
            return active.snapshot();
        }
        if (reference != null) {
            ACTIVE.remove(identity, reference);
        }
        synchronized (TERMINAL) {
            DelosRawStoreIoFaultInjector.Snapshot terminal = TERMINAL.get(identity);
            if (terminal != null) {
                return terminal;
            }
        }
        throw new IllegalStateException(
                "No RawStore I/O fault injector is registered for " + identity);
    }

    private static DelosRawStoreIoFaultInjector active(String databaseIdentity) {
        String identity = requireIdentity(databaseIdentity);
        WeakReference<DelosRawStoreIoFaultInjector> reference = ACTIVE.get(identity);
        DelosRawStoreIoFaultInjector injector = reference == null ? null : reference.get();
        if (injector == null) {
            if (reference != null) {
                ACTIVE.remove(identity, reference);
            }
            throw new IllegalStateException(
                    "No active RawStore I/O fault injector is registered for "
                            + identity);
        }
        return injector;
    }

    private static String requireIdentity(String value) {
        String identity = Objects.requireNonNull(value, "databaseIdentity").trim();
        if (identity.isEmpty()) {
            throw new IllegalArgumentException(
                    "databaseIdentity must not be blank");
        }
        return identity;
    }
}
