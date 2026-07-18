/*

   Derby - Class org.apache.derby.impl.store.raw.data.DelosMvccConglomerateLifecycleOperation

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
package org.apache.derby.impl.store.raw.data;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.derby.iapi.services.io.LimitObjectInput;
import org.apache.derby.iapi.services.io.StoredFormatIds;
import org.apache.derby.iapi.store.raw.Compensation;
import org.apache.derby.iapi.store.raw.Loggable;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.raw.Undoable;
import org.apache.derby.iapi.store.raw.log.LogInstant;
import org.apache.derby.iapi.store.raw.xact.RawTransaction;
import org.apache.derby.iapi.store.types.DelosMvccConglomerateLifecycle;
import org.apache.derby.iapi.util.ByteArray;
import org.apache.derby.io.StorageFactory;
import org.apache.derby.io.StorageFile;
import org.apache.derby.io.WritableStorageFactory;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/** Raw-store authority for one transactional {@code delos_mvcc} create or drop. */
public final class DelosMvccConglomerateLifecycleOperation implements Undoable {
    private byte operationOrdinal;
    private long segmentId;
    private long containerId;

    public DelosMvccConglomerateLifecycleOperation() {
    }

    public DelosMvccConglomerateLifecycleOperation(DelosMvccConglomerateLifecycle lifecycle) {
        DelosMvccConglomerateLifecycle required = requireLifecycle(lifecycle);
        operationOrdinal = (byte) required.operation().ordinal();
        segmentId = required.segmentId();
        containerId = required.containerId();
    }

    DelosMvccConglomerateLifecycle lifecycle() {
        DelosMvccConglomerateLifecycle.Operation[] operations =
                DelosMvccConglomerateLifecycle.Operation.values();
        int ordinal = Byte.toUnsignedInt(operationOrdinal);
        if (ordinal >= operations.length) {
            throw new IllegalStateException("Unknown delos_mvcc lifecycle operation " + ordinal);
        }
        return new DelosMvccConglomerateLifecycle(
                operations[ordinal], segmentId, containerId);
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        out.writeByte(operationOrdinal);
        out.writeLong(segmentId);
        out.writeLong(containerId);
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException {
        operationOrdinal = in.readByte();
        segmentId = in.readLong();
        containerId = in.readLong();
        try {
            lifecycle();
        } catch (RuntimeException invalidLifecycle) {
            throw new IOException("Invalid delos_mvcc conglomerate lifecycle record", invalidLifecycle);
        }
    }

    @Override
    public int getTypeFormatId() {
        return StoredFormatIds.LOGOP_DELOS_MVCC_CONGLOMERATE_LIFECYCLE;
    }

    @Override
    public ByteArray getPreparedLog() {
        return null;
    }

    @Override
    public void doMe(Transaction transaction, LogInstant instant, LimitObjectInput in)
            throws StandardException {
        RawTransaction rawTransaction = (RawTransaction) transaction;
        DelosMvccConglomerateLifecycle required = lifecycle();
        StorageFactory storageFactory = storageFactory(rawTransaction);
        if (rawTransaction.handlesPostTerminationWork()) {
            if (required.operation() == DelosMvccConglomerateLifecycle.Operation.CREATE) {
                ensurePendingCreateMarker(storageFactory, required);
            }
            return;
        }

        applyCommittedLifecycle(storageFactory, required);
    }

    @Override
    public boolean needsRedo(Transaction transaction) throws StandardException {
        StorageFactory storageFactory = storageFactory(transaction);
        DelosMvccConglomerateLifecycle required = lifecycle();
        return switch (required.operation()) {
            case CREATE -> pendingMarker(storageFactory, required).exists()
                    || committedMarker(storageFactory, required).exists();
            case DROP -> stateFilesExist(storageFactory, required);
        };
    }

    @Override
    public Compensation generateUndo(Transaction transaction, LimitObjectInput in) {
        return new DelosMvccConglomerateLifecycleUndoOperation(this);
    }

    @Override
    public void releaseResource(Transaction transaction) {
    }

    @Override
    public int group() {
        return Loggable.RAWSTORE;
    }

    static boolean undoNeedsRedo(
            Transaction transaction,
            DelosMvccConglomerateLifecycle lifecycle) throws StandardException {
        if (lifecycle.operation() == DelosMvccConglomerateLifecycle.Operation.DROP) {
            return false;
        }
        StorageFactory storageFactory = storageFactory(transaction);
        return pendingMarker(storageFactory, lifecycle).exists()
                || committedMarker(storageFactory, lifecycle).exists()
                || stateFilesExist(storageFactory, lifecycle);
    }

    static void applyUndo(
            Transaction transaction,
            DelosMvccConglomerateLifecycle lifecycle) throws StandardException {
        if (lifecycle.operation() == DelosMvccConglomerateLifecycle.Operation.CREATE) {
            abortCreate(storageFactory(transaction), lifecycle);
        }
    }

    private static void applyCommittedLifecycle(
            StorageFactory storageFactory,
            DelosMvccConglomerateLifecycle lifecycle) throws StandardException {
        switch (lifecycle.operation()) {
            case CREATE -> commitCreate(storageFactory, lifecycle);
            case DROP -> deleteStateFiles(storageFactory, lifecycle);
        }
    }

    private static void ensurePendingCreateMarker(
            StorageFactory storageFactory,
            DelosMvccConglomerateLifecycle lifecycle) throws StandardException {
        StorageFile pending = pendingMarker(storageFactory, lifecycle);
        if (!pending.exists()) {
            writeMarker(storageFactory, pending, markerPayload("PENDING", lifecycle));
        }
    }

    private static void commitCreate(
            StorageFactory storageFactory,
            DelosMvccConglomerateLifecycle lifecycle) throws StandardException {
        StorageFile committed = committedMarker(storageFactory, lifecycle);
        writeMarker(storageFactory, committed, markerPayload("COMMITTED", lifecycle));
        deleteIfPresent(pendingMarker(storageFactory, lifecycle));
        deleteIfPresent(committed);
    }

    private static void abortCreate(
            StorageFactory storageFactory,
            DelosMvccConglomerateLifecycle lifecycle) throws StandardException {
        deleteStateFiles(storageFactory, lifecycle);
        deleteIfPresent(pendingMarker(storageFactory, lifecycle));
        deleteIfPresent(committedMarker(storageFactory, lifecycle));
    }

    private static boolean stateFilesExist(
            StorageFactory storageFactory,
            DelosMvccConglomerateLifecycle lifecycle) {
        StorageFile directory = inheritedStoreDirectory(storageFactory, lifecycle);
        String[] names = directory.list();
        if (names == null) {
            return false;
        }
        String prefix = lifecycle.storageId() + ".";
        for (String name : names) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static void deleteStateFiles(
            StorageFactory storageFactory,
            DelosMvccConglomerateLifecycle lifecycle) throws StandardException {
        StorageFile directory = inheritedStoreDirectory(storageFactory, lifecycle);
        String[] names = directory.list();
        if (names == null) {
            return;
        }
        String prefix = lifecycle.storageId() + ".";
        for (String name : names) {
            if (name.startsWith(prefix)) {
                deleteIfPresent(storageFactory.newStorageFile(
                        lifecycle.inheritedStoreRelativeDirectory() + "/" + name));
            }
        }
        String[] remaining = directory.list();
        boolean tableStateRemains = false;
        if (remaining != null) {
            for (String name : remaining) {
                if (name.startsWith("conglomerate-")) {
                    tableStateRemains = true;
                    break;
                }
            }
        }
        if (!tableStateRemains) {
            deleteIfPresent(storageFactory.newStorageFile(
                    lifecycle.inheritedStoreRelativeDirectory()
                            + "/database-transactions.txstatus"));
        }
    }

    private static void writeMarker(
            StorageFactory storageFactory,
            StorageFile marker,
            byte[] payload) throws StandardException {
        if (!(storageFactory instanceof WritableStorageFactory writable)) {
            throw StandardException.newException(SQLState.FILE_READ_ONLY);
        }
        StorageFile directory = marker.getParentDir();
        if (!directory.exists() && !directory.mkdirs() && !directory.exists()) {
            throw fileFailure(
                    directory,
                    new IOException("Unable to create delos_mvcc lifecycle directory"));
        }
        try {
            directory.limitAccessToOwner();
            try (OutputStream output = marker.getOutputStream()) {
                output.write(payload);
                writable.sync(output, true);
            }
        } catch (IOException failure) {
            throw fileFailure(marker, failure);
        }
    }

    private static void deleteIfPresent(StorageFile file) throws StandardException {
        if (file.exists() && !file.delete()) {
            throw fileFailure(file, new IOException("Unable to delete " + file.getPath()));
        }
    }

    private static StorageFile inheritedStoreDirectory(
            StorageFactory storageFactory,
            DelosMvccConglomerateLifecycle lifecycle) {
        return storageFactory.newStorageFile(lifecycle.inheritedStoreRelativeDirectory());
    }

    private static StorageFile pendingMarker(
            StorageFactory storageFactory,
            DelosMvccConglomerateLifecycle lifecycle) {
        return storageFactory.newStorageFile(lifecycle.pendingCreateMarkerRelativePath());
    }

    private static StorageFile committedMarker(
            StorageFactory storageFactory,
            DelosMvccConglomerateLifecycle lifecycle) {
        return storageFactory.newStorageFile(lifecycle.committedCreateMarkerRelativePath());
    }

    private static StorageFactory storageFactory(Transaction transaction) {
        return storageFactory((RawTransaction) transaction);
    }

    private static StorageFactory storageFactory(RawTransaction transaction) {
        return transaction.getDataFactory().getStorageFactory();
    }

    private static byte[] markerPayload(
            String state,
            DelosMvccConglomerateLifecycle lifecycle) {
        return (state + "\t" + lifecycle.segmentId() + "\t" + lifecycle.containerId() + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static DelosMvccConglomerateLifecycle requireLifecycle(
            DelosMvccConglomerateLifecycle lifecycle) {
        if (lifecycle == null) {
            throw new NullPointerException("lifecycle");
        }
        return lifecycle;
    }

    private static StandardException fileFailure(StorageFile file, IOException failure) {
        return StandardException.newException(
                SQLState.FILE_UNEXPECTED_EXCEPTION,
                new IOException("delos_mvcc lifecycle I/O failed for " + file.getPath(), failure));
    }

    @Override
    public String toString() {
        return "DelosDB MVCC conglomerate lifecycle " + lifecycle();
    }

}
