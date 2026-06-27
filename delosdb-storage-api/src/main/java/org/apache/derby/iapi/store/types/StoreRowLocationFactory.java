/*

   Derby - Class org.apache.derby.iapi.store.types.StoreRowLocationFactory

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

import java.util.Iterator;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/** Factory for provider-owned row-location values. */
public interface StoreRowLocationFactory {
    String DEFAULT_PROVIDER = "derby";
    String DERBY_HEAP_ROW_LOCATION_CLASS =
            "org.apache.derby.impl.store.access.heap.HeapRowLocation";

    String providerName();

    StoreRowLocation newRowLocation();

    static StoreRowLocation newDefaultRowLocation() {
        return newRowLocation(DEFAULT_PROVIDER);
    }

    static StoreRowLocation newRowLocation(String providerName) {
        if (DEFAULT_PROVIDER.equals(providerName)) {
            return newDefaultDerbyRowLocation();
        }

        Iterator<StoreRowLocationFactory> factories = ServiceLoader.load(
                StoreRowLocationFactory.class,
                StoreRowLocationFactory.class.getClassLoader()).iterator();
        while (true) {
            try {
                if (!factories.hasNext()) {
                    break;
                }
                StoreRowLocationFactory factory = factories.next();
                if (factory.providerName().equals(providerName)) {
                    return factory.newRowLocation();
                }
            } catch (ServiceConfigurationError error) {
                throw new IllegalStateException(
                        "Invalid row-location factory registration", error);
            }
        }
        throw new IllegalStateException("No row-location factory registered for provider " + providerName);
    }

    private static StoreRowLocation newDefaultDerbyRowLocation() {
        try {
            Class<?> rowLocationClass = Class.forName(
                    DERBY_HEAP_ROW_LOCATION_CLASS,
                    true,
                    StoreRowLocationFactory.class.getClassLoader());
            Object rowLocation = rowLocationClass.getDeclaredConstructor().newInstance();
            if (rowLocation instanceof StoreRowLocation storeRowLocation) {
                return storeRowLocation;
            }
            throw new IllegalStateException(
                    DERBY_HEAP_ROW_LOCATION_CLASS + " is not a StoreRowLocation");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Unable to create default Derby row location", exception);
        }
    }
}
