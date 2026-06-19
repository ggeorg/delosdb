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

import java.io.InputStream;

import org.apache.derby.shared.common.error.StandardException;

/** Store-facing helpers for opaque Derby value operations. */
public final class StoreTypeUtil
{
    private StoreTypeUtil()
    {
    }

    public static StoreDataValue cloneHolder(Object value)
    {
        return StoreTypeSupportRegistry.support().cloneHolder(value);
    }

    public static StoreDataValue cloneValue(Object value, boolean forceMaterialization)
        throws StandardException
    {
        return StoreTypeSupportRegistry.support().cloneValue(value, forceMaterialization);
    }

    public static StoreDataValue getNewNull(Object value) throws StandardException
    {
        return StoreTypeSupportRegistry.support().getNewNull(value);
    }

    public static int compare(Object left, Object right) throws StandardException
    {
        return StoreTypeSupportRegistry.support().compare(left, right);
    }

    public static int compare(Object left, Object right, boolean nullsOrderedLow)
        throws StandardException
    {
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
        return StoreTypeSupportRegistry.support().compare(
            op, left, right, orderedNulls, nullsOrderedLow, unknownRV);
    }

    public static int getLength(Object value) throws StandardException
    {
        return StoreTypeSupportRegistry.support().getLength(value);
    }

    public static long getLong(Object value) throws StandardException
    {
        return StoreTypeSupportRegistry.support().getLong(value);
    }

    public static Object getObject(Object value) throws StandardException
    {
        return StoreTypeSupportRegistry.support().getObject(value);
    }

    public static InputStream getStream(Object value) throws StandardException
    {
        return StoreTypeSupportRegistry.support().getStream(value);
    }

    public static int estimateMemoryUsage(Object value)
    {
        return StoreTypeSupportRegistry.support().estimateMemoryUsage(value);
    }

    public static void setValue(Object target, Object source)
        throws StandardException
    {
        StoreTypeSupportRegistry.support().setValue(target, source);
    }
}
