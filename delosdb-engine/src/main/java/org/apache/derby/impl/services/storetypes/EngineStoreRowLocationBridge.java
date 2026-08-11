/*

   Derby - Class org.apache.derby.impl.services.storetypes.EngineStoreRowLocationBridge

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

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.sql.ResultSet;

import org.apache.derby.iapi.services.io.ArrayInputStream;
import org.apache.derby.iapi.services.io.StoredFormatIds;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.iapi.store.types.StoreRowLocationFactory;
import org.apache.derby.iapi.store.types.StoreTypeUtil;
import org.apache.derby.iapi.store.types.StoreValueOperations;
import org.apache.derby.iapi.types.DataType;
import org.apache.derby.iapi.types.DataValueDescriptor;
import org.apache.derby.iapi.types.RefDataValue;
import org.apache.derby.iapi.types.RowLocation;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.sanity.SanityManager;

/**
 * Engine-side compatibility bridge for store row locations.
 *
 * <p>B6m keeps the inherited engine {@code RowLocation}/{@code RefDataValue}
 * surface while {@code HeapRowLocation} becomes a store-native value. Engine
 * callers receive a small adapter; store callers can unwrap it through the
 * store-neutral {@code StoreRowLocation} contract.</p>
 */
public final class EngineStoreRowLocationBridge
{
    private EngineStoreRowLocationBridge()
    {
    }

    public static RowLocation newEngineRowLocation()
    {
        return new EngineRowLocationAdapter(StoreRowLocationFactory.newDefaultRowLocation());
    }

    public static RowLocation requireEngineRowLocation(Object value)
    {
        if (value == null)
        {
            return null;
        }
        if (value instanceof RowLocation rowLocation)
        {
            return rowLocation;
        }
        if (value instanceof RefDataValue refDataValue)
        {
            try
            {
                return requireEngineRowLocation(refDataValue.getObject());
            }
            catch (StandardException se)
            {
                throw new IllegalStateException(se);
            }
        }
        if (value instanceof StoreRowLocation storeRowLocation)
        {
            return new EngineRowLocationAdapter(storeRowLocation);
        }
        return (RowLocation) value;
    }

    public static StoreRowLocation requireStoreRowLocation(Object value)
    {
        if (value instanceof StoreRowLocation storeRowLocation)
        {
            return storeRowLocation.unwrapStoreRowLocation();
        }
        return (StoreRowLocation) value;
    }

    private static final class EngineRowLocationAdapter extends DataType
            implements RowLocation, RefDataValue
    {
        private StoreRowLocation storeRowLocation;

        private EngineRowLocationAdapter(StoreRowLocation storeRowLocation)
        {
            this.storeRowLocation = storeRowLocation.unwrapStoreRowLocation();
        }

        @Override
        public StoreRowLocation unwrapStoreRowLocation()
        {
            return storeRowLocation;
        }

        @Override
        public int estimateMemoryUsage()
        {
            return StoreTypeUtil.estimateMemoryUsage(storeRowLocation);
        }

        @Override
        public void setValue(StoreDataValue source) throws StandardException
        {
            StoreTypeUtil.setValue(
                storeRowLocation,
                requireStoreRowLocation(source));
        }

        @Override
        public int compare(StoreDataValue other) throws StandardException
        {
            return StoreTypeUtil.compare(
                storeRowLocation,
                requireStoreRowLocation(other));
        }

        @Override
        public int compare(StoreDataValue other, boolean nullsOrderedLow)
            throws StandardException
        {
            return StoreTypeUtil.compare(
                storeRowLocation,
                requireStoreRowLocation(other),
                nullsOrderedLow);
        }

        @Override
        public boolean compare(
            int op,
            StoreDataValue other,
            boolean orderedNulls,
            boolean unknownRV)
            throws StandardException
        {
            return StoreTypeUtil.compare(
                op,
                storeRowLocation,
                requireStoreRowLocation(other),
                orderedNulls,
                unknownRV);
        }

        @Override
        public boolean compare(
            int op,
            StoreDataValue other,
            boolean orderedNulls,
            boolean nullsOrderedLow,
            boolean unknownRV)
            throws StandardException
        {
            return StoreTypeUtil.compare(
                op,
                storeRowLocation,
                requireStoreRowLocation(other),
                orderedNulls,
                nullsOrderedLow,
                unknownRV);
        }

        @Override
        public String getTypeName()
        {
            return "RowLocation";
        }

        @Override
        public void setValueFromResultSet(
            ResultSet resultSet,
            int colNumber,
            boolean isNullable)
        {
        }

        @Override
        public DataValueDescriptor getNewNull()
        {
            return new EngineRowLocationAdapter(newNullStoreRowLocation());
        }

        private StoreRowLocation newNullStoreRowLocation()
        {
            try
            {
                StoreDataValue nullValue = StoreTypeUtil.getNewNull(storeRowLocation);
                if (nullValue instanceof StoreRowLocation rowLocation)
                {
                    return rowLocation;
                }
                throw new IllegalStateException(
                    "Row-location null value is not a StoreRowLocation: "
                    + nullValue.getClass().getName());
            }
            catch (StandardException se)
            {
                throw new IllegalStateException(
                    "Could not create null store row location", se);
            }
        }

        @Override
        public Object getObject()
        {
            return this;
        }

        @Override
        public DataValueDescriptor cloneValue(boolean forceMaterialization)
        {
            try
            {
                StoreDataValue cloned = StoreTypeUtil.cloneValue(
                    storeRowLocation,
                    forceMaterialization);
                return new EngineRowLocationAdapter((StoreRowLocation) cloned);
            }
            catch (StandardException se)
            {
                throw new IllegalStateException(se);
            }
        }

        @Override
        public DataValueDescriptor recycle()
        {
            if (storeRowLocation instanceof StoreValueOperations operations)
            {
                storeRowLocation = (StoreRowLocation) operations.recycle();
            }
            else
            {
                storeRowLocation = StoreRowLocationFactory.newDefaultRowLocation();
            }
            return this;
        }

        @Override
        public int getLength() throws StandardException
        {
            return StoreTypeUtil.getLength(storeRowLocation);
        }

        @Override
        public String getString() throws StandardException
        {
            return storeRowLocation.toString();
        }

        @Override
        public boolean compare(
            int op,
            DataValueDescriptor other,
            boolean orderedNulls,
            boolean unknownRV)
            throws StandardException
        {
            return StoreTypeUtil.compare(
                op,
                storeRowLocation,
                requireStoreRowLocation(other.getObject()),
                orderedNulls,
                unknownRV);
        }

        @Override
        public boolean compare(
            int op,
            DataValueDescriptor other,
            boolean orderedNulls,
            boolean nullsOrderedLow,
            boolean unknownRV)
            throws StandardException
        {
            return StoreTypeUtil.compare(
                op,
                storeRowLocation,
                requireStoreRowLocation(other.getObject()),
                orderedNulls,
                nullsOrderedLow,
                unknownRV);
        }

        @Override
        public int compare(DataValueDescriptor other) throws StandardException
        {
            return StoreTypeUtil.compare(
                storeRowLocation,
                requireStoreRowLocation(other.getObject()));
        }

        @Override
        public int compare(DataValueDescriptor other, boolean nullsOrderedLow)
            throws StandardException
        {
            return StoreTypeUtil.compare(
                storeRowLocation,
                requireStoreRowLocation(other.getObject()),
                nullsOrderedLow);
        }

        @Override
        public void setValue(RowLocation rowLocation)
        {
            try
            {
                StoreTypeUtil.setValue(storeRowLocation, requireStoreRowLocation(rowLocation));
            }
            catch (StandardException se)
            {
                throw new IllegalStateException(se);
            }
        }

        @Override
        protected void setFrom(DataValueDescriptor theValue)
            throws StandardException
        {
            StoreTypeUtil.setValue(
                storeRowLocation,
                requireStoreRowLocation(theValue.getObject()));
        }

        @Override
        public int getTypeFormatId()
        {
            if (isMvccRowLocation(storeRowLocation))
            {
                return StoredFormatIds.ACCESS_MVCC_ROW_LOCATION_V1_ID;
            }
            return StoredFormatIds.ACCESS_HEAP_ROW_LOCATION_V1_ID;
        }

        private static boolean isMvccRowLocation(StoreRowLocation rowLocation)
        {
            return rowLocation != null
                && "org.apache.derby.impl.store.access.mvcc.MvccRowLocation".equals(
                    rowLocation.getClass().getName());
        }

        @Override
        public boolean isNull()
        {
            return false;
        }

        @Override
        public void writeExternal(ObjectOutput out)
            throws IOException
        {
            StoreTypeUtil.writeExternal(storeRowLocation, out);
        }

        @Override
        public void readExternal(ObjectInput in)
            throws IOException, ClassNotFoundException
        {
            StoreTypeUtil.readExternal(storeRowLocation, in);
        }

        @Override
        public void readExternalFromArray(ArrayInputStream in)
            throws IOException, ClassNotFoundException
        {
            StoreTypeUtil.readExternalFromArray(storeRowLocation, in);
        }

        @Override
        public void restoreToNull()
        {
            if (SanityManager.DEBUG)
            {
                SanityManager.THROWASSERT("Heap row locations are never null");
            }
        }

        @Override
        public boolean equals(Object ref)
        {
            if (ref instanceof StoreRowLocation rowLocation)
            {
                return storeRowLocation.equals(rowLocation.unwrapStoreRowLocation());
            }
            return false;
        }

        @Override
        public int hashCode()
        {
            return storeRowLocation.hashCode();
        }

        @Override
        public String toString()
        {
            return storeRowLocation.toString();
        }
    }
}
