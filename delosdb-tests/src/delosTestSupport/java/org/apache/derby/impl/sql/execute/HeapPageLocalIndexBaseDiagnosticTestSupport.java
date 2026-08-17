/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.derby.impl.sql.execute;

/** Test-source-only bridge to page-local index-to-base diagnostics. */
public final class HeapPageLocalIndexBaseDiagnosticTestSupport {
    private HeapPageLocalIndexBaseDiagnosticTestSupport() {
    }

    public static void reset() {
        HeapPageLocalIndexBaseAccess.resetDiagnosticsForTesting();
    }

    public static long[] snapshot() {
        return HeapPageLocalIndexBaseAccess.diagnosticsForTesting();
    }
}
