/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageConsistencyTarget

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

import java.nio.file.Path;
import java.util.Objects;

/**
 * Provider-neutral target for a storage consistency check.
 *
 * <p>MVCC targets do not need a database directory because their diagnostics
 * are owned by the MVCC bridge.  Heap targets carry the database directory so
 * the inherited raw container file can be observed without adding a heap
 * implementation dependency to shared test and inspection code.</p>
 */
public record DelosStorageConsistencyTarget(String providerId,
                                            Path databaseDirectory,
                                            int segment,
                                            long containerId) {
    public DelosStorageConsistencyTarget {
        providerId = DelosStorageProviderIds.normalize(providerId);
    }

    public static DelosStorageConsistencyTarget heap(Path databaseDirectory, int segment, long containerId) {
        return new DelosStorageConsistencyTarget(
                DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID,
                Objects.requireNonNull(databaseDirectory, "databaseDirectory"),
                segment,
                containerId);
    }

    public static DelosStorageConsistencyTarget mvcc(int segment, long containerId) {
        return new DelosStorageConsistencyTarget(
                DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID,
                null,
                segment,
                containerId);
    }

    public boolean hasDatabaseDirectory() {
        return databaseDirectory != null;
    }
}
