/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.derbyTesting.functionTests.tests.delos;

import junit.framework.TestCase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.apache.derby.impl.services.cache.ContainerCacheFastPinTestSupport;
import org.apache.derby.impl.store.raw.data.CachedPageValidityTestSupport;

/** Correctness proofs for the stable ContainerCache fast-pin path. */
public final class ContainerCacheFastPinTest extends TestCase {
    public void testStoreAsksForLockFreeKeepsOnItsContainerCache() throws Exception {
        String url = "jdbc:derby:memory:container-cache-kind";
        try (Connection connection = DriverManager.getConnection(url + ";create=true")) {
            ContainerCacheFastPinTestSupport.verifyStoreCachesKeepAsDeclared(
                    CachedPageValidityTestSupport.containerCache(connection),
                    CachedPageValidityTestSupport.pageCache(connection));
        } finally {
            try {
                DriverManager.getConnection(url + ";drop=true");
            } catch (SQLException expected) {
                // Dropping an in-memory database reports its shutdown.
            }
        }
    }

    public void testEvictionFreezeCannotCrossActiveFastPin() {
        ContainerCacheFastPinTestSupport.verifyFastPinEvictionHandoff();
    }

    public void testRemoveDrainsOutstandingFastPin() throws Exception {
        ContainerCacheFastPinTestSupport.verifyRemoveWaitsForFastPin();
    }

    public void testConcurrentFastPinsDoNotLeakKeepReferences() throws Exception {
        ContainerCacheFastPinTestSupport.verifyConcurrentFastPins();
    }

    public void testFastPinCanBeReleasedByAnotherThread() throws Exception {
        ContainerCacheFastPinTestSupport.verifyCrossThreadFastRelease();
    }

    public void testConcurrentCacheLifecycle() throws Exception {
        ContainerCacheFastPinTestSupport.verifyConcurrentCacheLifecycle();
    }
}
