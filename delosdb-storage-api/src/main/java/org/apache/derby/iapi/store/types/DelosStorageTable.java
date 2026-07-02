/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageTable

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

import java.util.Optional;

import org.apache.derby.shared.common.error.StandardException;

/**
 * Provider-owned storage table operations exposed through the active storage-api boundary.
 *
 * <p>This interface is intentionally limited to the runtime row and transaction operations
 * required by the Derby bridge.  Provider-specific maintenance, locator, candidate-index,
 * and testing/diagnostic surfaces are exposed through separate optional capability
 * interfaces in this package.</p>
 */
public interface DelosStorageTable extends AutoCloseable {
    DelosStorageTransaction beginTransaction();

    DelosStorageSnapshot snapshot(DelosStorageTransaction transaction);

    /**
     * Create a provider snapshot for {@code transaction} using the visibility
     * horizon captured by {@code visibilitySnapshot}. Providers that do not
     * need transaction-local overlays may use the normal current snapshot.
     */
    default DelosStorageSnapshot snapshot(
            DelosStorageTransaction transaction,
            DelosStorageSnapshot visibilitySnapshot) {
        return snapshot(transaction);
    }

    DelosStorageScan openScan(DelosStorageSnapshot snapshot) throws StandardException;

    Optional<StoreDataValue[]> read(long rowId, DelosStorageSnapshot snapshot);

    void insert(long rowId, StoreDataValue[] row, DelosStorageTransaction transaction);

    void update(long rowId,
                StoreDataValue[] replacement,
                DelosStorageTransaction transaction,
                DelosStorageSnapshot snapshot);

    void delete(long rowId,
                DelosStorageTransaction transaction,
                DelosStorageSnapshot snapshot);

    void commit(DelosStorageTransaction transaction);

    void abort(DelosStorageTransaction transaction);

    long nextRowId();

    @Override
    void close();
}
