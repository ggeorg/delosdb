/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.derby.impl.store.access.btree;

/** Test-source-only bridge to the B-tree insert root-routing counters. */
public final class InsertRootRoutingTestSupport {
    private InsertRootRoutingTestSupport() {
    }

    /** Inserts routed through the root snapshot so far. */
    public static long hits() {
        return BTreeController.insertRootSnapshotCountsForTesting()[0];
    }

    /** Inserts that fell back to the latched-root descent so far. */
    public static long fallbacks() {
        return BTreeController.insertRootSnapshotCountsForTesting()[1];
    }
}
