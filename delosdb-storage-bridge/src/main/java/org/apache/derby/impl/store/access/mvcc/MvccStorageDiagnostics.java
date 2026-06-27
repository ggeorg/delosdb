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

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;
import org.apache.derby.iapi.store.types.StoreRowLocation;

/**
 * MVCC diagnostics adapter exposed through the storage-api diagnostics surface.
 *
 * <p>The implementation delegates to the existing bridge-owned counters and
 * state observations while keeping smoke fixtures from importing bridge classes
 * directly. It is not used by production storage execution.</p>
 */
public final class MvccStorageDiagnostics implements DelosStorageDiagnostics {
    @Override
    public String providerId() {
        return DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID;
    }

    @Override
    public void clearRuntimeStateForTesting() {
        MvccConglomerate.clearStatesForTesting();
        clearTransactionsForTesting();
    }

    @Override
    public int runtimeStateCountForTesting() {
        return MvccConglomerate.stateCountForTesting();
    }

    @Override
    public Path pageVolumeStateFileForTesting(int segment, long containerId) {
        return MvccConglomerate.pageVolumeStateFileForTesting(segment, containerId);
    }

    @Override
    public Path rowDirectoryStateFileForTesting(int segment, long containerId) {
        return MvccConglomerate.rowDirectoryStateFileForTesting(segment, containerId);
    }

    @Override
    public Path pageMutationLogFileForTesting(int segment, long containerId) {
        return MvccConglomerate.pageMutationLogFileForTesting(segment, containerId);
    }

    @Override
    public Path writeAheadLogFileForTesting(int segment, long containerId) {
        return MvccConglomerate.writeAheadLogFileForTesting(segment, containerId);
    }

    @Override
    public Path checkpointFileForTesting(int segment, long containerId) {
        return MvccConglomerate.checkpointFileForTesting(segment, containerId);
    }

    @Override
    public Path legacySnapshotFileForTesting(int segment, long containerId) {
        return MvccConglomerate.legacySnapshotFileForTesting(segment, containerId);
    }

    @Override
    public String checkpointStatusForTesting(int segment, long containerId) {
        return MvccConglomerate.checkpointStatusForTesting(segment, containerId);
    }

    @Override
    public int physicalVersionCountForTesting(int segment, long containerId) {
        return MvccConglomerate.physicalVersionCountForTesting(segment, containerId);
    }

    @Override
    public int logicalRowCountForTesting(int segment, long containerId) {
        return MvccConglomerate.logicalRowCountForTesting(segment, containerId);
    }

    @Override
    public boolean lastVacuumSkippedForTesting(int segment, long containerId) {
        return MvccConglomerate.lastVacuumSkippedForTesting(segment, containerId);
    }

    @Override
    public String lastVacuumReasonForTesting(int segment, long containerId) {
        return MvccConglomerate.lastVacuumReasonForTesting(segment, containerId);
    }

    @Override
    public int lastVacuumRemovedVersionsForTesting(int segment, long containerId) {
        return MvccConglomerate.lastVacuumRemovedVersionsForTesting(segment, containerId);
    }

    @Override
    public int lastVacuumRemainingVersionsForTesting(int segment, long containerId) {
        return MvccConglomerate.lastVacuumRemainingVersionsForTesting(segment, containerId);
    }

    @Override
    public void resetMutationCountersForTesting() {
        MvccConglomerateController.resetInsertCountForTesting();
        MvccConglomerateController.resetUpdateCountForTesting();
        MvccConglomerateController.resetDeleteCountForTesting();
    }

    @Override
    public int insertCountForTesting() {
        return MvccConglomerateController.insertCountForTesting();
    }

    @Override
    public int updateCountForTesting() {
        return MvccConglomerateController.updateCountForTesting();
    }

    @Override
    public int deleteCountForTesting() {
        return MvccConglomerateController.deleteCountForTesting();
    }

    @Override
    public void resetScanCountersForTesting() {
        MvccScanController.resetOpenCountForTesting();
        resetQualifierRejectCountForTesting();
        resetCandidateIndexCountersForTesting();
    }

    @Override
    public int scanOpenCountForTesting() {
        return MvccScanController.openCountForTesting();
    }

    @Override
    public void resetQualifierRejectCountForTesting() {
        MvccScanController.resetQualifierRejectCountForTesting();
    }

    @Override
    public int qualifierRejectCountForTesting() {
        return MvccScanController.qualifierRejectCountForTesting();
    }

    @Override
    public void resetCandidateIndexCountersForTesting() {
        MvccScanController.resetCandidateIndexCountsForTesting();
    }

    @Override
    public int candidateIndexLookupCountForTesting() {
        return MvccScanController.candidateIndexLookupCountForTesting();
    }

    @Override
    public int candidateIndexRowIdCountForTesting() {
        return MvccScanController.candidateIndexRowIdCountForTesting();
    }

    @Override
    public int candidateIndexVisibilityRejectCountForTesting() {
        return MvccScanController.candidateIndexVisibilityRejectCountForTesting();
    }

    @Override
    public int candidateIndexQualifierRejectCountForTesting() {
        return MvccScanController.candidateIndexQualifierRejectCountForTesting();
    }

    @Override
    public void clearTransactionsForTesting() {
        MvccStoreAccessTransactionRegistry.clearForTesting();
    }

    @Override
    public boolean isProviderScan(Object scanController) {
        return scanController instanceof MvccScanController;
    }

    @Override
    public boolean hasLocatorHint(StoreRowLocation location) {
        return MvccRowLocation.from(location).hasLocatorHint();
    }
}
