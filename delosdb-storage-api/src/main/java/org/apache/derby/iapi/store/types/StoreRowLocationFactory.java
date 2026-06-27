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

import java.util.ServiceLoader;

/** Factory for provider-owned row-location values. */
public interface StoreRowLocationFactory {
    String DEFAULT_PROVIDER = "derby";

    String providerName();

    StoreRowLocation newRowLocation();

    static StoreRowLocation newDefaultRowLocation() {
        return newRowLocation(DEFAULT_PROVIDER);
    }

    static StoreRowLocation newRowLocation(String providerName) {
        for (StoreRowLocationFactory factory : ServiceLoader.load(StoreRowLocationFactory.class)) {
            if (factory.providerName().equals(providerName)) {
                return factory.newRowLocation();
            }
        }
        throw new IllegalStateException("No row-location factory registered for provider " + providerName);
    }
}
