/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccStatementReadBoundaryPolicyTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Properties;

import junit.framework.TestCase;

import org.apache.derby.iapi.services.context.ContextManager;
import org.apache.derby.iapi.services.context.ContextService;
import org.apache.derby.iapi.services.monitor.Monitor;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.raw.LockingPolicy;
import org.apache.derby.iapi.store.raw.RawStoreFactory;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.shared.common.reference.Property;

/** Contract for MVCC statement-lifetime read-boundary locking capabilities. */
public final class MvccStatementReadBoundaryPolicyTest extends TestCase {
    public void testRawStorePoliciesExposeStatementBoundaryCapability() throws Exception {
        Monitor.startMonitor(new Properties(), new PrintWriter(System.out, true));

        ContextService contexts = ContextService.getFactory();
        ContextManager context = contexts.newContextManager();
        contexts.setCurrentContextManager(context);

        Transaction transaction = null;
        try {
            Properties parameters = new Properties();
            parameters.setProperty(Property.NO_AUTO_BOOT, "true");
            parameters.setProperty(Property.DELETE_ON_CREATE, "true");

            String database = Path.of("mvcc-statement-boundary-policy-db")
                    .toAbsolutePath()
                    .toString();
            RawStoreFactory store = (RawStoreFactory) Monitor.createPersistentService(
                    RawStoreFactory.MODULE, database, parameters);
            assertNotNull(store);

            transaction = store.startTransaction(context, "mvcc-statement-boundary-policy-test");

            LockingPolicy cursorStability = transaction.newLockingPolicy(
                    LockingPolicy.MODE_RECORD,
                    TransactionController.ISOLATION_READ_COMMITTED,
                    false);
            assertNotNull(cursorStability);
            assertTrue(cursorStability.supportsImmutablePageRead());
            assertTrue(cursorStability.supportsStatementReadBoundary());

            LockingPolicy noHold = transaction.newLockingPolicy(
                    LockingPolicy.MODE_RECORD,
                    TransactionController.ISOLATION_READ_COMMITTED_NOHOLDLOCK,
                    false);
            assertNotNull(noHold);
            assertFalse(noHold.supportsImmutablePageRead());
            assertTrue(noHold.supportsStatementReadBoundary());
        } finally {
            if (transaction != null) {
                transaction.close();
            }
            contexts.resetCurrentContextManager(context);
        }
    }
}
