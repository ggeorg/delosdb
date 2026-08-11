/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.derbyTesting.functionTests.tests.delos;

import junit.framework.TestCase;

import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreOrderable;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.iapi.store.types.StoreTypeUtil;
import org.apache.derby.iapi.store.types.StoreValueOperations;
import org.apache.derby.iapi.types.DataValueDescriptor;
import org.apache.derby.iapi.types.RowLocation;
import org.apache.derby.iapi.types.SQLInteger;
import org.apache.derby.iapi.types.SQLLongint;
import org.apache.derby.iapi.types.SQLVarchar;
import org.apache.derby.impl.services.storetypes.EngineStoreRowLocationBridge;

/** Proves engine SQL values use the direct shared store-value operation path. */
public final class StoreValueOperationsDirectPathTest extends TestCase {
    public void testCommonEngineValuesImplementDirectStoreOperations() throws Exception {
        assertDirect(new SQLInteger());
        assertDirect(new SQLLongint());
        assertDirect(new SQLVarchar());
    }

    public void testHeapRowLocationNewNullUsesDirectStoreValueOperations() {
        RowLocation rowLocation = EngineStoreRowLocationBridge.newEngineRowLocation();
        DataValueDescriptor nullValue = rowLocation.getNewNull();

        assertTrue(nullValue instanceof RowLocation);
        StoreRowLocation storeRowLocation =
                EngineStoreRowLocationBridge.requireStoreRowLocation(nullValue);
        assertTrue(storeRowLocation instanceof StoreValueOperations);
        assertEquals(
                "org.apache.derby.impl.store.access.heap.HeapRowLocation",
                storeRowLocation.getClass().getName());
    }

    public void testStoreTypeUtilOperationsRemainSemanticallyEquivalent() throws Exception {
        SQLInteger integer = new SQLInteger();
        StoreTypeUtil.setIntValue(integer, 42);
        assertEquals(42L, StoreTypeUtil.getLong(integer));

        StoreDataValue integerClone = StoreTypeUtil.cloneValue(integer, true);
        assertTrue(integerClone instanceof DataValueDescriptor);
        assertEquals(0, StoreTypeUtil.compare(integer, integerClone));

        SQLLongint longint = new SQLLongint();
        StoreTypeUtil.setLongValue(longint, 9_876_543_210L);
        assertEquals(9_876_543_210L, StoreTypeUtil.getLong(longint));

        SQLVarchar left = new SQLVarchar("alpha");
        SQLVarchar right = new SQLVarchar("beta");
        assertTrue(StoreTypeUtil.compare(
                StoreOrderable.ORDER_OP_LESSTHAN,
                left,
                right,
                true,
                false));

        StoreTypeUtil.setValue(right, left);
        assertEquals(0, StoreTypeUtil.compare(left, right));

        StoreDataValue nullValue = StoreTypeUtil.getNewNull(left);
        assertTrue(StoreTypeUtil.isNull(nullValue));
        StoreTypeUtil.restoreToNull(right);
        assertTrue(StoreTypeUtil.isNull(right));
    }

    private static void assertDirect(DataValueDescriptor value) {
        assertTrue(value.getClass().getName(), value instanceof StoreValueOperations);
    }
}
