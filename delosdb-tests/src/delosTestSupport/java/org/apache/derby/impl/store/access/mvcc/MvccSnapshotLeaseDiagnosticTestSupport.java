/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.derby.impl.store.access.mvcc;

/** Test-source-only bridge to temporary MVCC snapshot-lease path counters. */
public final class MvccSnapshotLeaseDiagnosticTestSupport {
    private MvccSnapshotLeaseDiagnosticTestSupport() {
    }

    public static void reset() {
        MvccSnapshotLeaseDiagnostics.resetForTesting();
    }

    public static long[] snapshot() {
        return MvccSnapshotLeaseDiagnostics.snapshotForTesting();
    }
}
