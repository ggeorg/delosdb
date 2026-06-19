/*

   Derby - Class org.apache.derby.impl.services.storetypes.EngineStoreTypeSupport

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
package org.apache.derby.impl.services.storetypes;

import java.io.InputStream;

import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreTypeSupport;
import org.apache.derby.iapi.types.DataValueDescriptor;
import org.apache.derby.shared.common.error.StandardException;

/** Engine-side bridge for inherited Derby SQL value operations. */
public final class EngineStoreTypeSupport implements StoreTypeSupport
{
    @Override
    public StoreDataValue cloneHolder(Object value)
    {
        return dataValue(value).cloneHolder();
    }

    @Override
    public StoreDataValue cloneValue(Object value, boolean forceMaterialization)
        throws StandardException
    {
        return dataValue(value).cloneValue(forceMaterialization);
    }

    @Override
    public StoreDataValue getNewNull(Object value) throws StandardException
    {
        return dataValue(value).getNewNull();
    }

    @Override
    public int compare(Object left, Object right) throws StandardException
    {
        return dataValue(left).compare(dataValue(right));
    }

    @Override
    public int compare(Object left, Object right, boolean nullsOrderedLow)
        throws StandardException
    {
        return dataValue(left).compare(dataValue(right), nullsOrderedLow);
    }

    @Override
    public boolean compare(
        int op,
        Object left,
        Object right,
        boolean orderedNulls,
        boolean unknownRV)
        throws StandardException
    {
        return dataValue(left).compare(
            op, dataValue(right), orderedNulls, unknownRV);
    }

    @Override
    public boolean compare(
        int op,
        Object left,
        Object right,
        boolean orderedNulls,
        boolean nullsOrderedLow,
        boolean unknownRV)
        throws StandardException
    {
        return dataValue(left).compare(
            op, dataValue(right), orderedNulls, nullsOrderedLow, unknownRV);
    }

    @Override
    public int getLength(Object value) throws StandardException
    {
        return dataValue(value).getLength();
    }

    @Override
    public long getLong(Object value) throws StandardException
    {
        return dataValue(value).getLong();
    }

    @Override
    public Object getObject(Object value) throws StandardException
    {
        return dataValue(value).getObject();
    }

    @Override
    public InputStream getStream(Object value) throws StandardException
    {
        return dataValue(value).getStream();
    }

    @Override
    public int estimateMemoryUsage(Object value)
    {
        return dataValue(value).estimateMemoryUsage();
    }

    @Override
    public void setValue(Object target, Object source) throws StandardException
    {
        dataValue(target).setValue(dataValue(source));
    }

    private static DataValueDescriptor dataValue(Object value)
    {
        return (DataValueDescriptor) value;
    }
}
