/*

   Derby - Class org.apache.derby.impl.sql.execute.HeapPageLocalIndexBaseAccess

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

 */
package org.apache.derby.impl.sql.execute;

import java.util.concurrent.atomic.LongAdder;

/** Internal switch and diagnostics for the page-local index-to-base experiment. */
final class HeapPageLocalIndexBaseAccess {
    static final String ENABLE_PROPERTY =
            "delosdb.experimental.heapPageLocalIndexBaseFetch";
    private static final String DIAGNOSTIC_PROPERTY =
            "delosdb.diagnostic.heapPageLocalIndexBaseFetch";

    private static final LongAdder BATCHES = new LongAdder();
    private static final LongAdder ROWS = new LongAdder();
    private static final LongAdder PAGE_ACQUISITIONS = new LongAdder();

    private HeapPageLocalIndexBaseAccess() {
    }

    static boolean enabled() {
        return Boolean.getBoolean(ENABLE_PROPERTY);
    }

    static void recordBatch(int rows, int pageAcquisitions) {
        if (!Boolean.getBoolean(DIAGNOSTIC_PROPERTY)) {
            return;
        }
        BATCHES.increment();
        ROWS.add(rows);
        PAGE_ACQUISITIONS.add(pageAcquisitions);
    }

    static void resetDiagnosticsForTesting() {
        BATCHES.reset();
        ROWS.reset();
        PAGE_ACQUISITIONS.reset();
    }

    static long[] diagnosticsForTesting() {
        return new long[] {
                BATCHES.sum(), ROWS.sum(), PAGE_ACQUISITIONS.sum()
        };
    }
}
