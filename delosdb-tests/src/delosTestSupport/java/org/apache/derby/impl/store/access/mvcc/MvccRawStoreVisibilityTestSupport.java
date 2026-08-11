/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreVisibilityTestSupport

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

/** Test-only package bridge for the MVCC commit-sequence visibility truth table. */
public final class MvccRawStoreVisibilityTestSupport {
    private MvccRawStoreVisibilityTestSupport() {
    }

    public static boolean visible(
            long creatorTransactionId,
            long beginSequence,
            long endSequence,
            long readerTransactionId,
            long snapshotSequence) {
        return MvccRawStoreVersionReader.visible(
                new MvccRawStoreTable.VersionRecord(
                        1L,
                        1L,
                        creatorTransactionId,
                        beginSequence,
                        endSequence,
                        MvccRawStoreFormat.NO_PREVIOUS_VERSION,
                        null,
                        0,
                        null,
                        null),
                readerTransactionId,
                snapshotSequence);
    }

    public static long uncommittedSequence() {
        return MvccRawStoreFormat.UNCOMMITTED_SEQUENCE;
    }
}
