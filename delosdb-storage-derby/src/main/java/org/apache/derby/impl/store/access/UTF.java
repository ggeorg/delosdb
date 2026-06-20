/*

   Derby - Class org.apache.derby.impl.store.access.UTF

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

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

import org.apache.derby.iapi.services.cache.ClassSize;
import org.apache.derby.iapi.services.io.StoredFormatIds;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreDataValueBase;
import org.apache.derby.shared.common.sanity.SanityManager;

/**
  A class that is used to store java.lang.Strings and provide
  ordering capability.

  @see org.apache.derby.iapi.services.io.FormatIdOutputStream
 **/

public class UTF extends StoreDataValueBase
{
    private String value;

    private static final int BASE_MEMORY_USAGE =
            ClassSize.estimateBaseFromCatalog(UTF.class);

    public UTF()
    {
    }

    public UTF(String value)
    {
        this.value = value;
    }

    @Override
    public int estimateMemoryUsage()
    {
        return BASE_MEMORY_USAGE + (value == null ? 0 : value.length() * 2);
    }

    @Override
    public String getString()
    {
        return value;
    }

    @Override
    public Object getObject()
    {
        return value;
    }

    @Override
    public int getLength()
    {
        return value == null ? 0 : value.length();
    }

    @Override
    public int getTypeFormatId()
    {
        return StoredFormatIds.SQL_USERTYPE_ID_V3;
    }

    @Override
    public boolean isNull()
    {
        return value == null;
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException
    {
        if (SanityManager.DEBUG)
            SanityManager.ASSERT(!isNull(),
                    "writeExternal() is not supposed to be called for null values.");

        out.writeObject(value);
    }

    @Override
    public void readExternal(ObjectInput in)
        throws IOException, ClassNotFoundException
    {
        value = (String) in.readObject();
    }

    @Override
    public StoreDataValue cloneValue(boolean forceMaterialization)
    {
        return new UTF(value);
    }

    @Override
    public StoreDataValue getNewNull()
    {
        return new UTF();
    }

    @Override
    public void restoreToNull()
    {
        value = null;
    }

    @Override
    protected void setFrom(StoreDataValue source)
    {
        if (SanityManager.DEBUG)
            SanityManager.ASSERT(source instanceof UTF);

        value = ((UTF) source).value;
    }

    /*
     * The following methods implement the Orderable protocol.
     */

    @Override
    public int compare(StoreDataValue other)
    {
        if (SanityManager.DEBUG)
            SanityManager.ASSERT(other instanceof UTF);

        UTF arg = (UTF) other;

        if (value == null)
            return arg.value == null ? 0 : 1;
        if (arg.value == null)
            return -1;

        return value.compareTo(arg.value);
    }
}
