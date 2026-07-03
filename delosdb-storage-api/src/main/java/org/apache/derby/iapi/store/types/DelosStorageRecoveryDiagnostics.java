/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageRecoveryDiagnostics

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
import java.util.Objects;

/** Read-only diagnostics for MVCC subsystem recovery metadata records. */
public record DelosStorageRecoveryDiagnostics(
        Path recordFile,
        long recordCount,
        long lastSequence,
        long rowPageRedoRecordCount,
        long indexPageRedoRecordCount,
        long overflowPageRedoRecordCount,
        long freeSpaceMapRedoRecordCount,
        long transactionOutcomeRedoRecordCount,
        long checkpointRecordCount,
        List<String> recordSummaries) {
    public DelosStorageRecoveryDiagnostics {
        if (recordCount < 0L || lastSequence < 0L
                || rowPageRedoRecordCount < 0L
                || indexPageRedoRecordCount < 0L
                || overflowPageRedoRecordCount < 0L
                || freeSpaceMapRedoRecordCount < 0L
                || transactionOutcomeRedoRecordCount < 0L
                || checkpointRecordCount < 0L) {
            throw new IllegalArgumentException("recovery diagnostic counts must not be negative");
        }
        recordSummaries = List.copyOf(Objects.requireNonNull(recordSummaries, "recordSummaries"));
    }

    public boolean hasRowPageRedoMetadata() {
        return rowPageRedoRecordCount > 0L;
    }

    public boolean hasIndexPageRedoMetadata() {
        return indexPageRedoRecordCount > 0L;
    }

    public boolean hasOverflowPageRedoMetadata() {
        return overflowPageRedoRecordCount > 0L;
    }

    public boolean hasFreeSpaceMapRedoMetadata() {
        return freeSpaceMapRedoRecordCount > 0L;
    }

    public boolean hasTransactionOutcomeRedoMetadata() {
        return transactionOutcomeRedoRecordCount > 0L;
    }

    public boolean hasCheckpointMetadata() {
        return checkpointRecordCount > 0L;
    }

    public boolean completeCheckpointBoundary() {
        return hasRowPageRedoMetadata()
                && hasIndexPageRedoMetadata()
                && hasOverflowPageRedoMetadata()
                && hasFreeSpaceMapRedoMetadata()
                && hasTransactionOutcomeRedoMetadata()
                && hasCheckpointMetadata();
    }
}
