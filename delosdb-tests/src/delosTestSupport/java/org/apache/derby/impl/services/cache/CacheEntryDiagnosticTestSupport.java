/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.derby.impl.services.cache;

/** Test-only bridge for package-private cache-entry diagnostics. */
public final class CacheEntryDiagnosticTestSupport {
    private CacheEntryDiagnosticTestSupport() {
    }

    public static void reset() {
        CacheEntry.resetCacheEntryDiagnosticsForTesting();
    }

    public static String[] snapshot() {
        return CacheEntry.snapshotCacheEntryDiagnosticsForTesting();
    }

    public static void resetHotState() {
        CacheEntry.resetHotStateDiagnosticsForTesting();
    }

    public static String[] snapshotHotState() {
        return CacheEntry.snapshotHotStateDiagnosticsForTesting();
    }
}
