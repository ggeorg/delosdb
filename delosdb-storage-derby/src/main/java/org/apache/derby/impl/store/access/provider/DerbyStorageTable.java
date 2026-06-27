/*

   Derby - Class org.apache.derby.impl.store.access.provider.DerbyStorageTable

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

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.derby.iapi.store.access.ConglomerateController;
import org.apache.derby.iapi.store.access.Qualifier;
import org.apache.derby.iapi.store.access.ScanController;
import org.apache.derby.iapi.store.access.SpaceInfo;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.types.DelosAccessContext;
import org.apache.derby.iapi.store.types.DelosCostableTableAccess;
import org.apache.derby.iapi.store.types.DelosFilterableTableAccess;
import org.apache.derby.iapi.store.types.DelosMutableTableAccess;
import org.apache.derby.iapi.store.types.DelosMutationPreparation;
import org.apache.derby.iapi.store.types.DelosMutationResult;
import org.apache.derby.iapi.store.types.DelosPredicate;
import org.apache.derby.iapi.store.types.DelosProjection;
import org.apache.derby.iapi.store.types.DelosRow;
import org.apache.derby.iapi.store.types.DelosRowIdentity;
import org.apache.derby.iapi.store.types.DelosScan;
import org.apache.derby.iapi.store.types.DelosTableCapabilities;
import org.apache.derby.iapi.store.types.DelosTableCapability;
import org.apache.derby.iapi.store.types.DelosTableCostEstimate;
import org.apache.derby.iapi.store.types.DelosTableGuarantee;
import org.apache.derby.iapi.store.types.DelosTableIdentity;
import org.apache.derby.iapi.store.types.DelosTableShape;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.shared.common.error.StandardException;

/** Table-access facade over inherited Derby heap/raw/btree conglomerates. */
public final class DerbyStorageTable
        implements DelosFilterableTableAccess,
        DelosMutableTableAccess,
        DelosCostableTableAccess {
    private final DelosTableIdentity identity;
    private final DelosTableShape rowShape;
    private final long conglomerateId;

    DerbyStorageTable(
            DelosTableIdentity identity,
            DelosTableShape rowShape,
            long conglomerateId) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.rowShape = Objects.requireNonNull(rowShape, "rowShape");
        this.conglomerateId = conglomerateId;
    }

    public long conglomerateId() {
        return conglomerateId;
    }

    @Override
    public DelosTableIdentity identity() {
        return identity;
    }

    @Override
    public DelosTableShape rowShape() {
        return rowShape;
    }

    @Override
    public DelosTableCapabilities capabilities() {
        return DelosTableCapabilities.of(
                DelosTableCapability.FILTERABLE,
                DelosTableCapability.PROJECTABLE,
                DelosTableCapability.MUTABLE,
                DelosTableCapability.COSTABLE);
    }

    @Override
    public Set<DelosTableGuarantee> guarantees() {
        return Set.of(
                DelosTableGuarantee.ROW_LOCKING,
                DelosTableGuarantee.DURABLE_RECOVERY_LOG);
    }

    @Override
    public DelosScan scan(
            DelosAccessContext context,
            List<DelosPredicate> mutableFilters,
            DelosProjection projection) {
        requirePhysicalAccess(context);
        Objects.requireNonNull(mutableFilters, "mutableFilters");
        Objects.requireNonNull(projection, "projection");
        try {
            ScanController scan = transaction(context).openScan(
                    conglomerateId,
                    false,
                    0,
                    TransactionController.MODE_RECORD,
                    TransactionController.ISOLATION_READ_COMMITTED,
                    null,
                    null,
                    ScanController.NA,
                    (Qualifier[][]) null,
                    null,
                    ScanController.NA);
            return new DerbyStorageScan(scan, rowShape, rowTemplate(context), projection);
        } catch (StandardException e) {
            throw new IllegalStateException("Could not open inherited Derby storage scan", e);
        }
    }

    @Override
    public DelosMutationPreparation validateMutable(
            DelosAccessContext context,
            DelosRowIdentity rowIdentity) {
        requirePhysicalAccess(context);
        StoreRowLocation location = DerbyStorageRowLocation.requireStoreRowLocation(rowIdentity);
        return DelosMutationPreparation.mutable(
                new DerbyStorageRowLocation(location),
                "Derby row identity accepted for inherited row-locking mutation");
    }

    @Override
    public DelosMutationResult insert(DelosAccessContext context, DelosRow row) {
        requirePhysicalAccess(context);
        Objects.requireNonNull(row, "row");
        try (OpenedConglomerate opened = openConglomerate(context)) {
            StoreRowLocation location = opened.controller().newRowLocationTemplate();
            opened.controller().insertAndFetchLocation(DerbyStorageRows.rowArray(row), location);
            return DelosMutationResult.inserted(new DerbyStorageRowLocation(location));
        } catch (StandardException e) {
            throw new IllegalStateException("Could not insert inherited Derby storage row", e);
        }
    }

    @Override
    public DelosMutationResult update(
            DelosAccessContext context,
            DelosRowIdentity rowIdentity,
            DelosRow replacement) {
        requirePhysicalAccess(context);
        Objects.requireNonNull(replacement, "replacement");
        StoreRowLocation location = DerbyStorageRowLocation.requireStoreRowLocation(rowIdentity);
        try (OpenedConglomerate opened = openConglomerate(context)) {
            boolean replaced = opened.controller().replace(
                    location,
                    DerbyStorageRows.rowArray(replacement),
                    null);
            return DelosMutationResult.affected(replaced ? 1L : 0L);
        } catch (StandardException e) {
            throw new IllegalStateException("Could not update inherited Derby storage row", e);
        }
    }

    @Override
    public DelosMutationResult delete(DelosAccessContext context, DelosRowIdentity rowIdentity) {
        requirePhysicalAccess(context);
        StoreRowLocation location = DerbyStorageRowLocation.requireStoreRowLocation(rowIdentity);
        try (OpenedConglomerate opened = openConglomerate(context)) {
            boolean deleted = opened.controller().delete(location);
            return DelosMutationResult.affected(deleted ? 1L : 0L);
        } catch (StandardException e) {
            throw new IllegalStateException("Could not delete inherited Derby storage row", e);
        }
    }

    @Override
    public DelosTableCostEstimate estimateTableCost(DelosAccessContext context) {
        requirePhysicalAccess(context);
        try (OpenedConglomerate opened = openConglomerate(context)) {
            SpaceInfo spaceInfo = opened.controller().getSpaceInfo();
            long allocatedPages = nonNegative(spaceInfo.getNumAllocatedPages());
            long freePages = nonNegative(spaceInfo.getNumFreePages());
            long unfilledPages = nonNegative(spaceInfo.getNumUnfilledPages());
            long usedPageEstimate = Math.max(0L, allocatedPages - freePages);
            return new DelosTableCostEstimate(
                    0L,
                    0L,
                    usedPageEstimate,
                    0L,
                    Math.max(usedPageEstimate, unfilledPages));
        } catch (StandardException e) {
            throw new IllegalStateException("Could not estimate inherited Derby storage table cost", e);
        }
    }

    private OpenedConglomerate openConglomerate(DelosAccessContext context) throws StandardException {
        ConglomerateController controller = transaction(context).openConglomerate(
                conglomerateId,
                false,
                TransactionController.OPENMODE_FORUPDATE,
                TransactionController.MODE_RECORD,
                TransactionController.ISOLATION_READ_COMMITTED);
        return new OpenedConglomerate(controller);
    }

    private static void requirePhysicalAccess(DelosAccessContext context) {
        if (!Objects.requireNonNull(context, "context").physicalAccessAllowed()) {
            throw new IllegalStateException("Physical Derby storage access is not allowed by context");
        }
    }

    private static TransactionController transaction(DelosAccessContext context) {
        return context.require(DerbyStorageTransaction.TRANSACTION_CONTROLLER_KEY);
    }

    private static StoreDataValue[] rowTemplate(DelosAccessContext context) {
        return context.find(DerbyStorageTransaction.ROW_TEMPLATE_KEY)
                .orElseGet(() -> new StoreDataValue[0]);
    }

    private static long nonNegative(long value) {
        return Math.max(value, 0L);
    }

    private record OpenedConglomerate(ConglomerateController controller) implements AutoCloseable {
        private OpenedConglomerate {
            Objects.requireNonNull(controller, "controller");
        }

        @Override
        public void close() throws StandardException {
            controller.close();
        }
    }
}
