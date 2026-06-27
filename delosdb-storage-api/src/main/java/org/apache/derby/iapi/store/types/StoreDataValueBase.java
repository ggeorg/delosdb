/*

   Derby - Class org.apache.derby.iapi.store.types.StoreDataValueBase

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
import org.apache.derby.iapi.services.io.Storable;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/**
 * Store-native value base for legacy store metadata values.
 *
 * <p>This is intentionally much smaller than Derby's SQL-facing
 * {@code DataType}. It supplies the value operations used by raw/access code
 * without making concrete store metadata classes extend {@code iapi.types}.</p>
 */
public abstract class StoreDataValueBase
        implements StoreDataType, Storable, StoreValueOperations, Comparable<Object>
{
    @Override
    public StoreDataValue cloneHolder()
    {
        try
        {
            return cloneValue(false);
        }
        catch (StandardException se)
        {
            throw new IllegalStateException(se);
        }
    }

    @Override
    public StoreDataValue cloneValue(boolean forceMaterialization)
        throws StandardException
    {
        throw notImplemented();
    }

    @Override
    public StoreDataValue getNewNull() throws StandardException
    {
        throw notImplemented();
    }

    @Override
    public StoreDataValue recycle()
    {
        return this;
    }

    @Override
    public int getLength() throws StandardException
    {
        throw notImplemented();
    }

    @Override
    public long getLong() throws StandardException
    {
        throw notImplemented();
    }

    @Override
    public String getString() throws StandardException
    {
        Object value = getObject();
        return value == null ? null : value.toString();
    }

    @Override
    public Object getObject() throws StandardException
    {
        return this;
    }

    @Override
    public InputStream getStream() throws StandardException
    {
        throw notImplemented();
    }

    @Override
    public int estimateMemoryUsage()
    {
        return 0;
    }

    @Override
    public void setValue(StoreDataValue source) throws StandardException
    {
        if (source == null || StoreTypeUtil.isNull(source))
        {
            restoreToNull();
            return;
        }
        setFrom(source);
    }

    @Override
    public void setIntValue(int value)
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    @Override
    public void setLongValue(long value)
    {
        throw new UnsupportedOperationException(getClass().getName());
    }

    protected void setFrom(StoreDataValue source) throws StandardException
    {
        throw notImplemented();
    }

    @Override
    public int compare(StoreDataValue other) throws StandardException
    {
        throw notImplemented();
    }

    @Override
    public int compare(StoreDataValue other, boolean nullsOrderedLow)
        throws StandardException
    {
        return compare(other);
    }

    @Override
    public boolean compare(
        int op,
        StoreDataValue other,
        boolean orderedNulls,
        boolean unknownRV)
        throws StandardException
    {
        if (!orderedNulls && (isNull() || StoreTypeUtil.isNull(other)))
        {
            return unknownRV;
        }
        int result = compare(other);
        return switch (op)
        {
            case StoreOrderable.ORDER_OP_EQUALS -> result == 0;
            case StoreOrderable.ORDER_OP_LESSTHAN -> result < 0;
            case StoreOrderable.ORDER_OP_LESSOREQUALS -> result <= 0;
            case StoreOrderable.ORDER_OP_GREATERTHAN -> result > 0;
            case StoreOrderable.ORDER_OP_GREATEROREQUALS -> result >= 0;
            default -> throw notImplemented();
        };
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
        if (!orderedNulls && (isNull() || StoreTypeUtil.isNull(other)))
        {
            return unknownRV;
        }
        int result = compare(other, nullsOrderedLow);
        return switch (op)
        {
            case StoreOrderable.ORDER_OP_EQUALS -> result == 0;
            case StoreOrderable.ORDER_OP_LESSTHAN -> result < 0;
            case StoreOrderable.ORDER_OP_LESSOREQUALS -> result <= 0;
            case StoreOrderable.ORDER_OP_GREATERTHAN -> result > 0;
            case StoreOrderable.ORDER_OP_GREATEROREQUALS -> result >= 0;
            default -> throw notImplemented();
        };
    }

    @Override
    public int compareTo(Object other)
    {
        try
        {
            return compare((StoreDataValue) other);
        }
        catch (StandardException se)
        {
            throw new IllegalStateException(se);
        }
    }

    @Override
    public void readExternalFromArray(ArrayInputStream input)
        throws IOException, ClassNotFoundException
    {
        readExternal(input);
    }

    protected final StandardException notImplemented()
    {
        return StandardException.newException(SQLState.NOT_IMPLEMENTED);
    }
}
