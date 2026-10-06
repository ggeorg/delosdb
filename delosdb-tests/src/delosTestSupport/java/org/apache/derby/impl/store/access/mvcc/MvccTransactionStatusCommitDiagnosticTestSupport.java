/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.derby.impl.store.access.mvcc;

import java.nio.file.Path;

/** Test-source-only bridge for transaction-status commit qualification counters. */
public final class MvccTransactionStatusCommitDiagnosticTestSupport {
    private MvccTransactionStatusCommitDiagnosticTestSupport() {
    }

    public static boolean enabled() {
        return MvccTransactionStatusCommitDiagnostics.enabledForTesting();
    }

    public static void reset() {
        MvccTransactionStatusCommitDiagnostics.resetForTesting();
    }

    public static long[] snapshot() {
        return MvccTransactionStatusCommitDiagnostics.snapshotForTesting();
    }

    public static int cachedStatusCount(Path databaseDirectory) {
        return MvccRawStoreDiagnosticsDirectory.require(databaseDirectory)
                .transactionStatuses
                .cachedStatusCount();
    }
}
