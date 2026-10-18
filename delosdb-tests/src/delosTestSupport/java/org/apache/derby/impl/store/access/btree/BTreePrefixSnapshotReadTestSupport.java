/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.derby.impl.store.access.btree;

/** Test-source-only bridge to the B-tree prefix snapshot read counter. */
public final class BTreePrefixSnapshotReadTestSupport {
    private BTreePrefixSnapshotReadTestSupport() {
    }

    /** Partial-key reads answered from a cached leaf snapshot so far. */
    public static long hits() {
        return BTreeScan.snapshotPrefixHitsForTesting();
    }
}
