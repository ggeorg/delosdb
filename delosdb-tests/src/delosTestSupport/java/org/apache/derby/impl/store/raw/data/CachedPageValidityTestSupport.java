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

    /** The container cache of the connection's database. */
    public static CacheManager containerCache(Connection connection) throws Exception {
        return dataFactory(rawTransaction(connection)).getContainerCache();
    }

    /** The page cache of the connection's database. */
    public static CacheManager pageCache(Connection connection) throws Exception {
        return dataFactory(rawTransaction(connection)).getPageCache();
    }

    private static RawTransaction rawTransaction(Connection connection) throws Exception {
        return (RawTransaction) ((TransactionManager) ((EmbedConnection) connection)
                .getLanguageConnection().getTransactionExecute()).getRawStoreXact();
    }

    private static BaseDataFileFactory dataFactory(RawTransaction raw) {
        return (BaseDataFileFactory) raw.getDataFactory();
    }

    /** Exercise a non-first allocation page with both warm and cold cache. */
    public static String verifyAllocationPageExclusion(Connection connection) throws Exception {
        RawTransaction raw = rawTransaction(connection);
        CacheManager cache = dataFactory(raw).getPageCache();
        java.util.Properties properties = new java.util.Properties();
        properties.setProperty("derby.storage.pageSize", "4096");
        long id = raw.addContainer(0, ContainerHandle.DEFAULT_ASSIGN_ID,
                ContainerHandle.MODE_DEFAULT, properties, 0);
        BaseContainerHandle container = (BaseContainerHandle) raw.openContainer(
                new ContainerKey(0, id), ContainerHandle.MODE_FORUPDATE);
        try {
            long lastUserPage = -1;
            // A 4K allocation extent manages fewer than 12,000 user pages.
            for (int i = 0; i < 12_000; i++) {
                Page page = container.addPage();
                lastUserPage = page.getPageNumber();
                page.unlatch();
            }
            AllocPage first = (AllocPage) container.getAllocPage(0);
            long allocationNumber;
            try { allocationNumber = first.getNextAllocPageNumber(); }
            finally { first.unlatch(); }
            if (allocationNumber <= 0) return "second allocation extent was not created";
            Page allocation = container.getAllocPage(allocationNumber);
            allocation.unlatch();
            for (int attempt = 0; attempt < 2; attempt++) {
                String failure = rejectUserFetch(container, allocationNumber);
                if (failure != null) return failure;
            }
            // Remove the warmed allocation page to exercise the allocation-map path too.
            PageKey key = new PageKey(container.getId(), allocationNumber);
            Cacheable cached = cache.findCached(key);
            if (!(cached instanceof AllocPage)) {
                if (cached != null) cache.release(cached);
                return "allocation page was not cached";
            }
            cache.remove(cached);
            String failure = rejectUserFetch(container, allocationNumber);
            if (failure != null) return failure;
            allocation = container.getAllocPage(allocationNumber);
            if (!(allocation instanceof AllocPage)) return "allocation accessor lost its page";
            allocation.unlatch();
            Page user = container.getPage(lastUserPage);
            if (user == null) return "allocated user page was rejected";
            user.unlatch();
            return null;
        } finally {
            container.close();
        }
    }

    private static String rejectUserFetch(BaseContainerHandle container, long number) throws Exception {
        Page page = container.getPage(number);
        if (page != null) {
            page.unlatch();
            return "getPage returned allocation page " + number;
        }
        page = container.getPageNoWait(number);
        if (page != null) {
            page.unlatch();
            return "getPageNoWait returned allocation page " + number;
        }
        return null;
    }

    /**
     * In the connection's transaction: create a container, deallocate a page
     * while it stays in the page cache, and fetch it again. Returns null on
     * success, otherwise a description of the failure. The caller rolls back.
     */
    public static String verifyDeallocatedCachedPage(Connection connection) throws Exception {
        RawTransaction raw = rawTransaction(connection);
        CacheManager pageCache = dataFactory(raw).getPageCache();

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
