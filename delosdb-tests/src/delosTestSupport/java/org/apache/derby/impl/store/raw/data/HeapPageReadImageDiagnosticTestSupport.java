/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.derby.impl.store.raw.data;

/** Test-source-only bridge to the experimental immutable heap-page diagnostics. */
public final class HeapPageReadImageDiagnosticTestSupport {
    private HeapPageReadImageDiagnosticTestSupport() {
    }

    public static void reset() {
        HeapPageReadImageAccess.resetDiagnosticsForTesting();
    }

    public static long[] snapshot() {
        return HeapPageReadImageAccess.diagnosticsForTesting();
    }
}
