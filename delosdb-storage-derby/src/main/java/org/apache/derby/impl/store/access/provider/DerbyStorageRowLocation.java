/*

   Derby - Class org.apache.derby.impl.store.access.provider.DerbyStorageRowLocation

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
package org.apache.derby.impl.store.access.provider;

import java.util.Objects;

import org.apache.derby.iapi.store.types.DelosRowIdentity;
import org.apache.derby.iapi.store.types.StoreRowLocation;

/** Opaque storage-api row identity backed by Derby's inherited row location. */
public record DerbyStorageRowLocation(StoreRowLocation nativeIdentity) implements DelosRowIdentity {
    public DerbyStorageRowLocation {
        nativeIdentity = Objects.requireNonNull(nativeIdentity, "nativeIdentity");
    }

    @Override
    public String providerName() {
        return DerbyStorageProvider.PROVIDER_NAME;
    }

    public StoreRowLocation rowLocation() {
        return nativeIdentity;
    }

    static StoreRowLocation requireStoreRowLocation(DelosRowIdentity rowIdentity) {
        Objects.requireNonNull(rowIdentity, "rowIdentity");
        if (!DerbyStorageProvider.PROVIDER_NAME.equals(rowIdentity.providerName())) {
            throw new IllegalArgumentException("Row identity belongs to provider " + rowIdentity.providerName());
        }
        Object nativeIdentity = rowIdentity.nativeIdentity();
        if (nativeIdentity instanceof StoreRowLocation location) {
            return location;
        }
        throw new IllegalArgumentException("Derby row identity must wrap a StoreRowLocation");
    }
}
