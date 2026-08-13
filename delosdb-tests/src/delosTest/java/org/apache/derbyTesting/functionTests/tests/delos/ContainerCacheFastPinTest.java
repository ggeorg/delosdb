/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.derbyTesting.functionTests.tests.delos;

import junit.framework.TestCase;
import org.apache.derby.impl.services.cache.ContainerCacheFastPinTestSupport;

/** Correctness proofs for the stable ContainerCache fast-pin path. */
public final class ContainerCacheFastPinTest extends TestCase {
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
