/*

   DelosDB - access method factory registry smoke test

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

package org.apache.derby.impl.store.access;

import java.util.Properties;

import org.apache.derby.catalog.UUID;
import org.apache.derby.iapi.store.access.AccessFactory;
import org.apache.derby.iapi.store.access.ColumnOrdering;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.access.conglomerate.Conglomerate;
import org.apache.derby.iapi.store.access.conglomerate.ConglomerateFactory;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.PageKey;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.types.DataValueDescriptor;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Proves that the Derby access manager can register and resolve a
 * test-only conglomerate factory whose low-four-bit factory id is 2.
 *
 * <p>This smoke intentionally does not create a real access method. It proves
 * the first blocker identified in the internals book: the in-memory factory
 * map must not be permanently limited to heap id 0 and B-tree id 1.</p>
 */
public final class AccessMethodFactoryRegistrySmoke {
    private static final int TEST_FACTORY_ID = 2;

    private AccessMethodFactoryRegistrySmoke() {
    }

    public static void main(String[] args) throws Exception {
        TestAccessManager accessManager = new TestAccessManager();
        ProbeConglomerateFactory probeFactory = new ProbeConglomerateFactory(TEST_FACTORY_ID);

        accessManager.registerAccessMethod(probeFactory);

        long conglomIdWithFactoryTwo = 0x12L;
        ConglomerateFactory resolvedFactory = accessManager.getFactoryFromConglomId(conglomIdWithFactoryTwo);
        assertSame(probeFactory, resolvedFactory, "factory id 2 should resolve to the registered probe factory");

        assertMissingFactory(accessManager, 0x13L);
        assertInvalidFactoryIdRejected(accessManager);

        System.out.println("DelosDB access-method factory id 2 registry smoke test passed.");
    }

    private static void assertMissingFactory(TestAccessManager accessManager, long conglomId) throws Exception {
        try {
            accessManager.getFactoryFromConglomId(conglomId);
            throw new AssertionError("Expected missing factory id to fail for conglomId=" + conglomId);
        } catch (StandardException expected) {
            // Expected: id 3 is in range but no factory is registered for it.
        }
    }

    private static void assertInvalidFactoryIdRejected(TestAccessManager accessManager) {
        try {
            accessManager.registerAccessMethod(new ProbeConglomerateFactory(16));
            throw new AssertionError("Expected out-of-range factory id 16 to be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected: Derby encodes the factory id in the low four bits, so 0..15 is the valid range.
        }
    }

    private static void assertSame(Object expected, Object actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected same instance");
        }
    }

    private static final class TestAccessManager extends RAMAccessManager {
        @Override
        protected int getSystemLockLevel() {
            return TransactionController.MODE_RECORD;
        }

        @Override
        protected void bootLookupSystemLockLevel(TransactionController tc) {
            // Not needed for this isolated registry proof.
        }
    }

    private static final class ProbeConglomerateFactory implements ConglomerateFactory {
        private final int factoryId;

        private ProbeConglomerateFactory(int factoryId) {
            this.factoryId = factoryId;
        }

        @Override
        public int getConglomerateFactoryId() {
            return factoryId;
        }

        @Override
        public Conglomerate createConglomerate(
                TransactionManager xactMgr,
                int segment,
                long inputContainerid,
                DataValueDescriptor[] template,
                ColumnOrdering[] columnOrder,
                int[] collationIds,
                Properties properties,
                int temporaryFlag) {
            throw new UnsupportedOperationException("test-only registry probe");
        }

        @Override
        public Conglomerate readConglomerate(TransactionManager xactMgr, ContainerKey containerKey) {
            throw new UnsupportedOperationException("test-only registry probe");
        }

        @Override
        public void insertUndoNotify(AccessFactory accessFactory, Transaction xact, PageKey pageKey) {
            throw new UnsupportedOperationException("test-only registry probe");
        }

        @Override
        public boolean canSupport(Properties properties) {
            return true;
        }

        @Override
        public Properties defaultProperties() {
            return new Properties();
        }

        @Override
        public boolean supportsImplementation(String implementationId) {
            return primaryImplementationType().equals(implementationId);
        }

        @Override
        public String primaryImplementationType() {
            return "probe-factory-" + factoryId;
        }

        @Override
        public boolean supportsFormat(UUID formatid) {
            return false;
        }

        @Override
        public UUID primaryFormat() {
            return null;
        }
    }
}
