/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.derby.impl.services.locks;

/** Test-only bridge for logical lock-wait diagnostics. */
public final class LockWaitDiagnosticTestSupport {
    private LockWaitDiagnosticTestSupport() {
    }

    public static void reset() {
        ConcurrentLockSet.resetLogicalWaitDiagnosticsForTesting();
    }

    public static String[] snapshot() {
        return ConcurrentLockSet.snapshotLogicalWaitDiagnosticsForTesting();
    }
}
