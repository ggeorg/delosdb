/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.derby.impl.store.raw.data;

import java.sql.Connection;

import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.xact.RawTransaction;
import org.apache.derby.impl.jdbc.EmbedConnection;
import org.apache.derby.shared.common.error.StandardException;

/** Test-only bridge: break one physical page invariant in memory and check it through {@link Page}. */
public final class PageConsistencyTestSupport {
    private PageConsistencyTestSupport() {
    }

    /**
     * Overstate the free space of the conglomerate's first page, run
     * {@link Page#checkPhysicalConsistency} and restore the page, all under
     * one latch. The caller checkpoints first, so the page is clean and the
     * temporary value can never be written to disk.
     *
     * @return the consistency failure message, or null if the check passed
     */
    public static String checkWithOverstatedFreeSpace(Connection connection, long conglomerateId)
            throws Exception {
        TransactionController tc = ((EmbedConnection) connection)
                .getLanguageConnection().getTransactionExecute();
        RawTransaction raw = (RawTransaction) ((TransactionManager) tc).getRawStoreXact();
        ContainerHandle container = raw.openContainer(
                new ContainerKey(0, tc.findContainerid(conglomerateId)), ContainerHandle.MODE_READONLY);
        try {
            StoredPage page = (StoredPage) container.getFirstPage();
            int freeSpace = page.freeSpace;
            try {
                page.freeSpace = page.getMaxFreeSpace() + 1;
                ((Page) page).checkPhysicalConsistency("test page");
                return null;
            } catch (StandardException expected) {
                return expected.getMessage();
            } finally {
                page.freeSpace = freeSpace;
                page.unlatch();
            }
        } finally {
            container.close();
        }
    }
}
