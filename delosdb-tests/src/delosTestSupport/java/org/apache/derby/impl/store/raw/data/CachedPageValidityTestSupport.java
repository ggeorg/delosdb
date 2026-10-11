/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.derby.impl.store.raw.data;

import java.sql.Connection;

import org.apache.derby.iapi.services.cache.Cacheable;
import org.apache.derby.iapi.services.cache.CacheManager;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.PageKey;
import org.apache.derby.iapi.store.raw.xact.RawTransaction;
import org.apache.derby.impl.jdbc.EmbedConnection;

/** Test-only bridge: page fetches for deallocated pages that are still cached. */
public final class CachedPageValidityTestSupport {
    private CachedPageValidityTestSupport() {
    }

    /**
     * In the connection's transaction: create a container, deallocate a page
     * while it stays in the page cache, and fetch it again. Returns null on
     * success, otherwise a description of the failure. The caller rolls back.
     */
    public static String verifyDeallocatedCachedPage(Connection connection) throws Exception {
        EmbedConnection embedded = (EmbedConnection) connection;
        TransactionManager manager =
                (TransactionManager) embedded.getLanguageConnection().getTransactionExecute();
        RawTransaction raw = (RawTransaction) manager.getRawStoreXact();
        CacheManager pageCache = ((BaseDataFileFactory) raw.getDataFactory()).getPageCache();

        long containerId = raw.addContainer(0, ContainerHandle.DEFAULT_ASSIGN_ID,
                ContainerHandle.MODE_DEFAULT, null, 0);
        ContainerHandle container = raw.openContainer(
                new ContainerKey(0, containerId), ContainerHandle.MODE_FORUPDATE);
        try {
            Page kept = container.addPage();
            long keptNumber = kept.getPageNumber();
            kept.unlatch();
            Page removed = container.addPage();
            long removedNumber = removed.getPageNumber();
            container.removePage(removed);

            Cacheable cached = pageCache.findCached(new PageKey(container.getId(), removedNumber));
            if (cached == null) {
                return "deallocated page " + removedNumber + " is not in the page cache";
            }
            pageCache.release(cached);

            Page page = container.getPage(removedNumber);
            if (page != null) {
                page.unlatch();
                return "getPage returned deallocated cached page " + removedNumber;
            }
            page = container.getPageNoWait(removedNumber);
            if (page != null) {
                page.unlatch();
                return "getPageNoWait returned deallocated cached page " + removedNumber;
            }
            page = container.getPage(removedNumber + 1000);
            if (page != null) {
                page.unlatch();
                return "getPage returned a page past the end of the container";
            }
            page = container.getPage(keptNumber);
            if (page == null) {
                return "getPage did not return allocated cached page " + keptNumber;
            }
            page.unlatch();
            return null;
        } finally {
            container.close();
        }
    }
}
