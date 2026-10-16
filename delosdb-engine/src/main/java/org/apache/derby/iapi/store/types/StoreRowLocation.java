/*

   Derby - Class org.apache.derby.iapi.store.types.StoreRowLocation

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

/** SQL-neutral row-location marker for store-facing APIs. */
public interface StoreRowLocation extends StoreDataValue {
    /**
     * Return the concrete store-owned row location behind an engine adapter.
     * Store-native implementations return themselves.
     */
    default StoreRowLocation unwrapStoreRowLocation()
    {
        return this;
    }

    /**
     * Optional transient version observed when this row location was exported
     * for a later write. Zero means that no write observation is attached.
     * The value is not row identity and must not change the durable row-location
     * format.
     */
    default long getWriteVersion()
    {
        return 0L;
    }

    /** Whether this row-location implementation carries transient write versions. */
    default boolean supportsWriteVersion()
    {
        return false;
    }

    /** Attach or clear the transient version used to validate a later write. */
    default void setWriteVersion(long version)
    {
    }
}
