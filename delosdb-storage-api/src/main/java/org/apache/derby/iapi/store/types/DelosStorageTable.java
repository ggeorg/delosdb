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

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.apache.derby.shared.common.error.StandardException;

/** Provider-owned storage table operations exposed through the storage-api boundary. */
public interface DelosStorageTable extends AutoCloseable {
    DelosStorageTransaction beginTransaction();

    DelosStorageSnapshot snapshot(DelosStorageTransaction transaction);

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

    void persistCommittedState();

    void dropDurableState();

    DelosStorageRowHead rowHeadFor(long rowId);

    Optional<List<Long>> candidateRowIdsFor(int column, String value);

    int candidateIndexKeyCountForTesting();

    Path pageVolumeStateFileForTesting();

    Path rowDirectoryStateFileForTesting();

    Path pageMutationLogFileForTesting();

    Path writeAheadLogFileForTesting();

    Path checkpointFileForTesting();

    String checkpointStatusForTesting();

    int physicalVersionCountForTesting();

    int logicalRowCountForTesting();

    DelosVacuumOutcome vacuumSafely();

    DelosVacuumOutcome lastVacuumOutcomeForTesting();

    Path legacySnapshotFileForTesting();

    @Override
    void close();
}
