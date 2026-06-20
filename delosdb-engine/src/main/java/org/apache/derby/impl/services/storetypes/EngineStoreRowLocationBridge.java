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

import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.iapi.types.RowLocation;
import org.apache.derby.impl.store.access.heap.HeapRowLocation;

/**
 * Engine-side compatibility bridge for store row locations.
 *
 * <p>B6 keeps {@code HeapRowLocation} compatible with Derby's inherited
 * {@code RowLocation} contract while engine/catalog consumers are moved away
 * from direct casts. The bridge centralizes those casts so the final
 * store-native row-location switch has one engine-owned adaptation point.</p>
 */
public final class EngineStoreRowLocationBridge
{
    private EngineStoreRowLocationBridge()
    {
    }

    public static RowLocation newEngineRowLocation()
    {
        return new HeapRowLocation();
    }

    public static RowLocation requireEngineRowLocation(Object value)
    {
        return (RowLocation) value;
    }

    public static StoreRowLocation requireStoreRowLocation(Object value)
    {
        return (StoreRowLocation) value;
    }
}
