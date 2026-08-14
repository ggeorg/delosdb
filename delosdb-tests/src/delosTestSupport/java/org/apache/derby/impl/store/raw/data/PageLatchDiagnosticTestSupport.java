/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.derby.impl.store.raw.data;

/** Test-source-only bridge to the temporary RawStore page-latch diagnostic counters. */
public final class PageLatchDiagnosticTestSupport {
    private PageLatchDiagnosticTestSupport() {
    }

    public static void reset() {
        BasePage.resetPageLatchDiagnosticsForTesting();
    }

    public static long[] snapshot() {
        return BasePage.pageLatchDiagnosticsForTesting();
    }

    public static String[] contentionByPage() {
        return BasePage.pageLatchContentionByPageForTesting();
    }

    public static String[] detailedContentionByPage() {
        return BasePage.pageLatchContentionByPageDetailedForTesting();
    }
}
