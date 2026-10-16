/*

   Derby - Class org.apache.derby.impl.store.raw.data.RawStorePreparedInsertAccess

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements. See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.raw.data;

import java.util.concurrent.atomic.LongAdder;

/** Module-internal control and qualification counters for RCM prepared inserts. */
final class RawStorePreparedInsertAccess {
    static final String ENABLE_PROPERTY =
            "delosdb.experimental.rawStorePreparedInsert.enabled";
    static final String DIAGNOSTIC_PROPERTY =
            "delosdb.diagnostic.rawStorePreparedInsert";

    private static final LongAdder ROWS_PREPARED = new LongAdder();
    private static final LongAdder ROW_FALLBACKS = new LongAdder();
    private static final LongAdder PREPARED = new LongAdder();
    private static final LongAdder DIRECT_APPLIES = new LongAdder();
    private static final LongAdder LEGACY_APPLIES = new LongAdder();
    private static final LongAdder PAGE_BYTES = new LongAdder();

    private RawStorePreparedInsertAccess() {
    }

    static boolean enabled() {
        return Boolean.getBoolean(ENABLE_PROPERTY);
    }

    static void rowPrepared() {
        if (diagnostics()) {
            ROWS_PREPARED.increment();
        }
    }

    static void rowFallback() {
        if (diagnostics()) {
            ROW_FALLBACKS.increment();
        }
    }

    static void prepared(int pageBytes) {
        if (!diagnostics()) {
            return;
        }
        PREPARED.increment();
        PAGE_BYTES.add(pageBytes);
    }

    static void directApply() {
        if (diagnostics()) {
            DIRECT_APPLIES.increment();
        }
    }

    static void legacyApply() {
        if (diagnostics()) {
            LEGACY_APPLIES.increment();
        }
    }

    static void resetForTesting() {
        ROWS_PREPARED.reset();
        ROW_FALLBACKS.reset();
        PREPARED.reset();
        DIRECT_APPLIES.reset();
        LEGACY_APPLIES.reset();
        PAGE_BYTES.reset();
    }

    static long[] diagnosticsForTesting() {
        return new long[] {
                ROWS_PREPARED.sum(),
                ROW_FALLBACKS.sum(),
                PREPARED.sum(),
                DIRECT_APPLIES.sum(),
                LEGACY_APPLIES.sum(),
                PAGE_BYTES.sum()
        };
    }

    private static boolean diagnostics() {
        return Boolean.getBoolean(DIAGNOSTIC_PROPERTY);
    }
}
