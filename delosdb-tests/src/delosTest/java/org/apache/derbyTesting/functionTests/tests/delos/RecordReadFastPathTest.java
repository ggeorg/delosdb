/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.derbyTesting.functionTests.tests.delos;

import junit.framework.TestCase;
import org.apache.derby.impl.services.locks.RecordReadFastPathTestSupport;

/** Correctness proofs for the adaptive compatible RecordId RS2 fast path. */
public final class RecordReadFastPathTest extends TestCase {
    public void testMaterializesBeforeWriter() throws Exception {
        RecordReadFastPathTestSupport.verifyMaterializationBeforeWriter();
    }

    public void testRepeatedReaderMaterializes() throws Exception {
        RecordReadFastPathTestSupport.verifyRepeatedReaderMaterializes();
    }

    public void testQueuedWriterPreventsReaderBypass() throws Exception {
        RecordReadFastPathTestSupport.verifyWriterWaiterOrdering();
    }

    public void testConcurrentReadersRetireCleanly() throws Exception {
        RecordReadFastPathTestSupport.verifyConcurrentReadersRetireCleanly();
    }
}
