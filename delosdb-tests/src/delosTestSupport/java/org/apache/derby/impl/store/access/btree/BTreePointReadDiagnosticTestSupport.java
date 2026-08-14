/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.derby.impl.store.access.btree;

/** Test-source-only bridge to the temporary B-tree point-read path counters. */
public final class BTreePointReadDiagnosticTestSupport {
    private BTreePointReadDiagnosticTestSupport() {
    }

    public static void reset() {
        BTreePointReadDiagnostics.resetForTesting();
    }

    public static long[] snapshot() {
        return BTreePointReadDiagnostics.snapshotForTesting();
    }
}
