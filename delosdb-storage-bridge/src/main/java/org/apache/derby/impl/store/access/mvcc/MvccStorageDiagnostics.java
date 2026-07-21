/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccStorageDiagnostics

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

package org.apache.derby.impl.store.access.mvcc;

import java.nio.file.Path;

import org.apache.derby.iapi.store.types.DelosDatabaseStorageSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsContext;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;
import org.apache.derby.iapi.store.types.DelosStorageMaintenanceSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageTransactionRegistry;
import org.apache.derby.iapi.store.types.StoreRowLocation;

/** Non-owning diagnostics adapter for the RawStore-backed MVCC runtime. */
public final class MvccStorageDiagnostics implements DelosStorageDiagnostics {
    private final Path databaseDirectory;

    public MvccStorageDiagnostics() {
        this(null);
    }

    private MvccStorageDiagnostics(Path databaseDirectory) {
        this.databaseDirectory = databaseDirectory == null
                ? null
                : databaseDirectory.toAbsolutePath().normalize();
    }

    @Override
    public DelosStorageDiagnostics withContext(DelosStorageDiagnosticsContext context) {
        if (context == null || !context.hasDatabaseDirectory()) {
            return this;
        }
        return new MvccStorageDiagnostics(context.databaseDirectory());
    }

    @Override
    public String providerId() {
        return DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID;
    }

    @Override
    public void clearRuntimeStateForTesting() {
        if (databaseDirectory == null) {
            MvccRawStoreDiagnosticsDirectory.clearAllForTesting();
        } else {
            MvccRawStoreDiagnosticsDirectory.clearForTesting(databaseDirectory);
        }
        clearTransactionsForTesting();
    }

    @Override
    public int runtimeStateCountForTesting() {
        if (!runtimeActiveForTesting()) {
            return 0;
        }
        return databaseMaintenanceSnapshot().registeredTableCount();
    }

    @Override
    public boolean runtimeActiveForTesting() {
        return databaseDirectory == null
                ? MvccRawStoreDiagnosticsDirectory.runtimeCount() > 0
                : MvccRawStoreDiagnosticsDirectory.isActive(databaseDirectory);
    }

    @Override
    public DelosStorageMaintenanceSnapshot databaseMaintenanceSnapshot() {
        return rawStoreRuntime().maintenanceSnapshot();
    }

    @Override
    public DelosDatabaseStorageSnapshot databaseStorageSnapshot() {
        throw new IllegalStateException(
                "Retained delos_mvcc database-storage diagnostics were retired with the external persistence runtime");
    }

    @Override
    public Path pageVolumeStateFileForTesting(int segment, long containerId) {
        return null;
    }

    @Override
    public Path rowDirectoryStateFileForTesting(int segment, long containerId) {
        return null;
    }

    @Override
    public Path reusablePageIndexFileForTesting(int segment, long containerId) {
        return null;
    }

    @Override
    public Path pageMutationLogFileForTesting(int segment, long containerId) {
        return null;
    }

    @Override
    public Path writeAheadLogFileForTesting(int segment, long containerId) {
        return null;
    }

    @Override
    public Path checkpointFileForTesting(int segment, long containerId) {
        return null;
    }

    @Override
    public Path legacySnapshotFileForTesting(int segment, long containerId) {
        return null;
    }

    @Override
    public String checkpointStatusForTesting(int segment, long containerId) {
        return "RAWSTORE_OWNED";
    }

    @Override
    public int physicalVersionCountForTesting(int segment, long containerId) {
        return 0;
    }

    @Override
    public int logicalRowCountForTesting(int segment, long containerId) {
        return 0;
    }

    @Override
    public long pageCountForTesting(int segment, long containerId) {
        return 0L;
    }

    @Override
    public long overflowPageCountForTesting(int segment, long containerId) {
        return 0L;
    }

    @Override
    public long reusablePageCountForTesting(int segment, long containerId) {
        return 0L;
    }

    @Override
    public long pageCacheMaxPageCountForTesting(int segment, long containerId) {
        return 0L;
    }

    @Override
    public long pageCacheSizeForTesting(int segment, long containerId) {
        return 0L;
    }

    @Override
    public long pageCacheHitCountForTesting(int segment, long containerId) {
        return 0L;
    }

    @Override
    public long pageCacheMissCountForTesting(int segment, long containerId) {
        return 0L;
    }

    @Override
    public long pageCacheWriteCountForTesting(int segment, long containerId) {
        return 0L;
    }

    @Override
    public long pageCacheEvictionCountForTesting(int segment, long containerId) {
        return 0L;
    }

    @Override
    public long pageCacheInvalidationCountForTesting(int segment, long containerId) {
        return 0L;
    }

    @Override
    public int consistencyErrorCountForTesting(int segment, long containerId) {
        return 0;
    }

    @Override
    public String consistencySummaryForTesting(int segment, long containerId) {
        return "RawStore owns consistency and recovery";
    }

    @Override
    public void assertConsistentForTesting(int segment, long containerId) {
    }

    @Override
    public boolean lastVacuumSkippedForTesting(int segment, long containerId) {
        return false;
    }

    @Override
    public String lastVacuumReasonForTesting(int segment, long containerId) {
        return "RawStore maintenance diagnostics are database-scoped";
    }

    @Override
    public int lastVacuumRemovedVersionsForTesting(int segment, long containerId) {
        return 0;
    }

    @Override
    public int lastVacuumRemainingVersionsForTesting(int segment, long containerId) {
        return 0;
    }

    @Override
    public void resetMutationCountersForTesting() {
    }

    @Override
    public int insertCountForTesting() {
        return 0;
    }

    @Override
    public int updateCountForTesting() {
        return 0;
    }

    @Override
    public int deleteCountForTesting() {
        return 0;
    }

    @Override
    public void resetScanCountersForTesting() {
    }

    @Override
    public int scanOpenCountForTesting() {
        return 0;
    }

    @Override
    public void resetQualifierRejectCountForTesting() {
    }

    @Override
    public int qualifierRejectCountForTesting() {
        return 0;
    }

    @Override
    public void resetCandidateIndexCountersForTesting() {
    }

    @Override
    public int candidateIndexLookupCountForTesting() {
        return 0;
    }

    @Override
    public int candidateIndexRowIdCountForTesting() {
        return 0;
    }

    @Override
    public int candidateIndexVisibilityRejectCountForTesting() {
        return 0;
    }

    @Override
    public int candidateIndexQualifierRejectCountForTesting() {
        return 0;
    }

    @Override
    public void clearTransactionsForTesting() {
        DelosStorageTransactionRegistry.clearForTesting();
    }

    @Override
    public boolean isProviderScan(Object scanController) {
        return scanController instanceof MvccRawStoreScanController;
    }

    @Override
    public boolean hasLocatorHint(StoreRowLocation location) {
        return MvccRowLocation.from(location).hasLocatorHint();
    }

    private MvccRawStoreRuntime rawStoreRuntime() {
        return databaseDirectory == null
                ? MvccRawStoreDiagnosticsDirectory.requireSingle()
                : MvccRawStoreDiagnosticsDirectory.require(databaseDirectory);
    }
}
