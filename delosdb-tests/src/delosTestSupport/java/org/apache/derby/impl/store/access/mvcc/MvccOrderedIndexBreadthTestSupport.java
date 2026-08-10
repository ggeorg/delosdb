/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.derby.impl.store.access.mvcc;

import java.sql.Connection;
import java.sql.SQLException;

import org.apache.derby.iapi.sql.conn.LanguageConnectionContext;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.impl.jdbc.EmbedConnection;
import org.apache.derby.shared.common.error.StandardException;

/** Test-only control for measuring MVCC internal ordered-index breadth. */
public final class MvccOrderedIndexBreadthTestSupport {
    private MvccOrderedIndexBreadthTestSupport() {
    }

    public static int retainUniqueProbeIndexes(
            Connection connection,
            long metadataContainerId) throws SQLException {
        if (!(connection instanceof EmbedConnection embedded)) {
            throw new SQLException(
                    "Embedded connection required for MVCC ordered-index breadth experiment");
        }
        LanguageConnectionContext lcc = embedded.getLanguageConnection();
        TransactionController controller = lcc.getTransactionExecute();
        if (!(controller instanceof TransactionManager manager)) {
            throw new SQLException(
                    "Transaction manager required for MVCC ordered-index breadth experiment");
        }

        try {
            Transaction raw = manager.getRawStoreXact();
            MvccRawStoreTable.Descriptor table = MvccRawStoreTableMetadata.read(
                    raw, new ContainerKey(0L, metadataContainerId));
            if (table == null || table.orderedIndexContainer() == null) {
                throw new SQLException("MVCC ordered-index generation is absent");
            }

            boolean[] retained = new boolean[table.columnCount()];
            for (MvccRawStoreTable.UniqueConstraint constraint : table.uniqueConstraints()) {
                int[] columns = constraint.columns();
                if (columns.length > 0) {
                    retained[columns[0]] = true;
                }
            }

            long[] btrees = MvccRawStoreOrderedIndexGeneration.requireBtreeConglomerates(
                    manager, table, table.orderedIndexContainer());
            int disabled = 0;
            for (int column = 0; column < btrees.length; column++) {
                if (!retained[column]
                        && MvccRawStoreOrderedIndexGeneration.isUsable(btrees[column])) {
                    MvccRawStoreOrderedIndexGeneration.disableBtree(
                            manager,
                            table,
                            table.orderedIndexContainer(),
                            btrees,
                            column);
                    disabled++;
                }
            }
            return disabled;
        } catch (StandardException failure) {
            throw new SQLException(
                    "MVCC ordered-index breadth experiment setup failed", failure);
        }
    }
}
