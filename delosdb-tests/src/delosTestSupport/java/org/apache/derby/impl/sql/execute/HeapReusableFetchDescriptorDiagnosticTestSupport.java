/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.derby.impl.sql.execute;

/** Test-source-only bridge to reusable Heap fetch-descriptor diagnostics. */
public final class HeapReusableFetchDescriptorDiagnosticTestSupport {
    private HeapReusableFetchDescriptorDiagnosticTestSupport() {
    }

    public static void reset() {
        HeapReusableFetchDescriptorAccess.resetDiagnosticsForTesting();
    }

    public static long fetches() {
        return HeapReusableFetchDescriptorAccess.diagnosticsForTesting();
    }
}
