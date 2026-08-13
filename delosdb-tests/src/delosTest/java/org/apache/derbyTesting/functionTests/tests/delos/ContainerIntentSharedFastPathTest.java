/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.derbyTesting.functionTests.tests.delos;

import junit.framework.TestCase;
import org.apache.derby.impl.services.locks.ContainerIntentSharedFastPathTestSupport;

/** Correctness proofs for the concurrent ContainerLock.CIS fast path. */
public final class ContainerIntentSharedFastPathTest extends TestCase {
    public void testMaterializesBeforeIncompatibleContainerLock() throws Exception {
        ContainerIntentSharedFastPathTestSupport.verifyMaterializationContract();
    }

    public void testRepeatedGroupAcquisitionPreservesReferenceCount() throws Exception {
        ContainerIntentSharedFastPathTestSupport.verifyGroupReferenceCounting();
    }

    public void testConcurrentCompatibleReaders() throws Exception {
        ContainerIntentSharedFastPathTestSupport.verifyConcurrentReaders();
    }

    public void testStableHolderReuseAvoidsPerTransactionMapChurn() throws Exception {
        ContainerIntentSharedFastPathTestSupport.verifyStableHolderReuse();
    }

    public void testIdleRetainedHoldersAreReclaimed() throws Exception {
        ContainerIntentSharedFastPathTestSupport.verifyIdleHolderReclamation();
    }
}
