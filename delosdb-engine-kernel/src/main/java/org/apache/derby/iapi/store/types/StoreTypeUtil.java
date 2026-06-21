/*

   Derby - Class org.apache.derby.iapi.store.types.StoreTypeUtil

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
package org.apache.derby.iapi.store.types;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectOutput;

import org.apache.derby.iapi.services.io.ArrayInputStream;

import org.apache.derby.shared.common.error.StandardException;

/** Store-facing helpers for opaque Derby value operations. */
public final class StoreTypeUtil
{
    private StoreTypeUtil()
    {
    }

    public static StoreDataValue newSQLInteger()
    {
        return StoreTypeSupportRegistry.support().newSQLInteger();
    }

    public static StoreDataValue[] newValueArray(int length)
    {
        return StoreTypeSupportRegistry.support().newValueArray(length);
    }

    public static StoreDataValue newSQLLongint(long value)
    {
        return StoreTypeSupportRegistry.support().newSQLLongint(value);
    }

    public static StoreDataValue newUserType()
    {
        return StoreTypeSupportRegistry.support().newUserType();
    }

    public static StoreDataValue newUserType(Object value)
    {
        return StoreTypeSupportRegistry.support().newUserType(value);
    }

    public static StoreRowLocation newRowLocation(Object storeRowLocation)
    {
        return StoreTypeSupportRegistry.support().newRowLocation(storeRowLocation);
    }

    public static StoreDataValue cloneHolder(Object value)
    {
        if (value instanceof StoreValueOperations operations)
        {
            return operations.cloneHolder();
        }
        return StoreTypeSupportRegistry.support().cloneHolder(value);
    }

    public static StoreDataValue cloneValue(Object value, boolean forceMaterialization)
        throws StandardException
    {
        if (value instanceof StoreValueOperations operations)
        {
            return operations.cloneValue(forceMaterialization);
        }
        return StoreTypeSupportRegistry.support().cloneValue(value, forceMaterialization);
    }

    public static StoreDataValue getNewNull(Object value) throws StandardException
    {
        if (value instanceof StoreValueOperations operations)
        {
            return operations.getNewNull();
        }
        return StoreTypeSupportRegistry.support().getNewNull(value);
    }

    public static StoreLocatedRow newLocatedRow(Object columnValues, Object rowLocation)
    {
        return StoreTypeSupportRegistry.support().newLocatedRow(columnValues, rowLocation);
    }

    public static StoreLocatedRow newLocatedRow(Object columnsAndRowLocation)
    {
        return StoreTypeSupportRegistry.support().newLocatedRow(columnsAndRowLocation);
    }

    public static Object[] locatedRowColumnValues(Object locatedRow)
    {
        return StoreTypeSupportRegistry.support().locatedRowColumnValues(locatedRow);
    }

    public static Object locatedRowLocation(Object locatedRow)
    {
        return StoreTypeSupportRegistry.support().locatedRowLocation(locatedRow);
    }

    public static Object[] flattenLocatedRow(Object columnValues, Object rowLocation)
    {
        return StoreTypeSupportRegistry.support().flattenLocatedRow(columnValues, rowLocation);
    }

    public static int compare(Object left, Object right) throws StandardException
    {
        if (left instanceof StoreValueOperations operations
                && right instanceof StoreDataValue storeRight)
        {
            return operations.compare(storeRight);
        }
        return StoreTypeSupportRegistry.support().compare(left, right);
    }

    public static int compare(Object left, Object right, boolean nullsOrderedLow)
        throws StandardException
    {
        if (left instanceof StoreValueOperations operations
                && right instanceof StoreDataValue storeRight)
        {
            return operations.compare(storeRight, nullsOrderedLow);
        }
        return StoreTypeSupportRegistry.support().compare(left, right, nullsOrderedLow);
    }

    public static boolean compare(
        int op,
        Object left,
        Object right,
        boolean orderedNulls,
        boolean unknownRV)
        throws StandardException
    {
        if (left instanceof StoreValueOperations operations
                && right instanceof StoreDataValue storeRight)
        {
            return operations.compare(op, storeRight, orderedNulls, unknownRV);
        }
        return StoreTypeSupportRegistry.support().compare(
            op, left, right, orderedNulls, unknownRV);
    }

    public static boolean compare(
        int op,
        Object left,
        Object right,
        boolean orderedNulls,
        boolean nullsOrderedLow,
        boolean unknownRV)
        throws StandardException
    {
        if (left instanceof StoreValueOperations operations
                && right instanceof StoreDataValue storeRight)
        {
            return operations.compare(
                op, storeRight, orderedNulls, nullsOrderedLow, unknownRV);
        }
        return StoreTypeSupportRegistry.support().compare(
            op, left, right, orderedNulls, nullsOrderedLow, unknownRV);
    }

    public static int getLength(Object value) throws StandardException
    {
        if (value instanceof StoreValueOperations operations)
        {
            return operations.getLength();
        }
        return StoreTypeSupportRegistry.support().getLength(value);
    }

    public static long getLong(Object value) throws StandardException
    {
        if (value instanceof StoreValueOperations operations)
        {
            return operations.getLong();
        }
        return StoreTypeSupportRegistry.support().getLong(value);
    }

    public static boolean isNull(Object value) throws StandardException
    {
        if (value instanceof StoreValueOperations operations)
        {
            return operations.isNull();
        }
        return StoreTypeSupportRegistry.support().isNull(value);
    }

    public static Object getObject(Object value) throws StandardException
    {
        if (value instanceof StoreValueOperations operations)
        {
            return operations.getObject();
        }
        return StoreTypeSupportRegistry.support().getObject(value);
    }

    public static InputStream getStream(Object value) throws StandardException
    {
        if (value instanceof StoreValueOperations operations)
        {
            return operations.getStream();
        }
        return StoreTypeSupportRegistry.support().getStream(value);
    }

    public static int estimateMemoryUsage(Object value)
    {
        if (value instanceof StoreValueOperations operations)
        {
            return operations.estimateMemoryUsage();
        }
        return StoreTypeSupportRegistry.support().estimateMemoryUsage(value);
    }

    public static void setValue(Object target, Object source)
        throws StandardException
    {
        if (target instanceof StoreValueOperations operations
                && source instanceof StoreDataValue storeSource)
        {
            operations.setValue(storeSource);
            return;
        }
        StoreTypeSupportRegistry.support().setValue(target, source);
    }

    public static void setIntValue(Object target, int value)
    {
        if (target instanceof StoreValueOperations operations)
        {
            operations.setIntValue(value);
            return;
        }
        StoreTypeSupportRegistry.support().setIntValue(target, value);
    }

    public static void setLongValue(Object target, long value)
    {
        if (target instanceof StoreValueOperations operations)
        {
            operations.setLongValue(value);
            return;
        }
        StoreTypeSupportRegistry.support().setLongValue(target, value);
    }

    public static void restoreToNull(Object value)
    {
        if (value instanceof StoreValueOperations operations)
        {
            operations.restoreToNull();
            return;
        }
        StoreTypeSupportRegistry.support().restoreToNull(value);
    }

    public static void readExternal(Object value, ObjectInput input)
        throws IOException, ClassNotFoundException
    {
        if (value instanceof StoreValueOperations operations)
        {
            operations.readExternal(input);
            return;
        }
        StoreTypeSupportRegistry.support().readExternal(value, input);
    }

    public static void readExternalFromArray(Object value, ArrayInputStream input)
        throws IOException, ClassNotFoundException
    {
        if (value instanceof StoreValueOperations operations)
        {
            operations.readExternalFromArray(input);
            return;
        }
        StoreTypeSupportRegistry.support().readExternalFromArray(value, input);
    }

    public static void writeExternal(Object value, ObjectOutput output)
        throws IOException
    {
        if (value instanceof StoreValueOperations operations)
        {
            operations.writeExternal(output);
            return;
        }
        StoreTypeSupportRegistry.support().writeExternal(value, output);
    }
}
