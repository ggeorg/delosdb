/*

   Derby - Class org.apache.derbyTesting.unitTests.store.T_StoreNativeTestSupport

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

package org.apache.derbyTesting.unitTests.store;

import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.iapi.types.DataValueDescriptor;
import org.apache.derby.iapi.types.RowLocation;
import org.apache.derby.iapi.types.SQLLongint;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Test-only adapters for old Derby store unit tests after the B6 store-native cutover. */
final class T_StoreNativeTestSupport
{
    private T_StoreNativeTestSupport()
    {
    }

    static DataValueDescriptor[] newU8Row(int nkeys)
    {
        DataValueDescriptor[] columns = new DataValueDescriptor[nkeys];
        for (int i = 0; i < nkeys; i++)
        {
            columns[i] = new SQLLongint(Long.MIN_VALUE);
        }
        return columns;
    }

    static DataValueDescriptor[] newRow(DataValueDescriptor[] template)
        throws org.apache.derby.shared.common.error.StandardException
    {
        DataValueDescriptor[] columns = new DataValueDescriptor[template.length];
        for (int i = template.length; i-- > 0;)
        {
            columns[i] = template[i].getNewNull();
        }
        return columns;
    }

    static RowLocation rowLocation(StoreRowLocation rowLocation)
    {
        try
        {
            Class<?> bridgeClass = Class.forName(
                "org.apache.derby.impl.services.storetypes.EngineStoreRowLocationBridge");
            Method method = bridgeClass.getMethod("requireEngineRowLocation", Object.class);
            return (RowLocation) method.invoke(null, rowLocation);
        }
        catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e)
        {
            throw new IllegalStateException("Unable to adapt store row location for Derby tests", e);
        }
        catch (InvocationTargetException e)
        {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException)
            {
                throw runtimeException;
            }
            throw new IllegalStateException("Unable to adapt store row location for Derby tests", cause);
        }
    }
}
