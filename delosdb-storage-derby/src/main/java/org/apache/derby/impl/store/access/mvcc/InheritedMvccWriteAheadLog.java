/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.InheritedMvccWriteAheadLog

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
import java.util.Objects;

import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.storage.mvcc.DelosLogSequenceNumber;
import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccLogWriter;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;

import org.apache.derby.iapi.store.raw.ContainerKey;

/**
 * Provider-local write-ahead log boundary for inherited MVCC page-volume state.
 *
 * <p>This is deliberately not Derby WAL and not full ARIES. MODULE13 wires the
 * existing MVCC log writer into the inherited Derby store/access path so page
 * versions written by {@link InheritedMvccPageVolumeStateStore} receive forced
 * log records and pageLSNs before the page-volume write occurs.</p>
 */
final class InheritedMvccWriteAheadLog {
    private final Path path;
    private final VersionedTableMetadata metadata;
    private final MvccLogWriter writer;

    private InheritedMvccWriteAheadLog(Path path, VersionedTableMetadata metadata, MvccLogWriter writer) {
        this.path = path;
        this.metadata = metadata;
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    static InheritedMvccWriteAheadLog open(Path databaseDirectory, ContainerKey key) {
        Path logFile = logFile(databaseDirectory, key);
        if (logFile == null || key.getContainerId() == 0L) {
            return disabled();
        }
        return new InheritedMvccWriteAheadLog(
                logFile,
                new VersionedTableMetadata(
                        "INHERITED_MVCC",
                        "CONGLOMERATE_" + key.getSegmentId() + "_" + key.getContainerId()),
                MvccLogWriter.open(logFile));
    }

    static InheritedMvccWriteAheadLog disabled() {
        return new InheritedMvccWriteAheadLog(null, null, MvccLogWriter.disabled());
    }

    Path path() {
        return path;
    }

    boolean enabled() {
        return writer.isEnabled();
    }

    void appendBegin(long transactionId) {
        if (enabled()) {
            writer.appendBegin(new MvccTransactionId(transactionId));
        }
    }

    DelosLogSequenceNumber appendInsertVersion(long transactionId, long rowId) {
        if (!enabled()) {
            return DelosLogSequenceNumber.NONE;
        }
        return writer.appendInsertVersion(new MvccTransactionId(transactionId), metadata, rowId).lsn();
    }

    DelosLogSequenceNumber appendUpdateVersion(long transactionId, long rowId) {
        if (!enabled()) {
            return DelosLogSequenceNumber.NONE;
        }
        return writer.appendUpdateVersion(new MvccTransactionId(transactionId), metadata, rowId).lsn();
    }

    DelosLogSequenceNumber appendDeleteVersion(long transactionId, long rowId) {
        if (!enabled()) {
            return DelosLogSequenceNumber.NONE;
        }
        return writer.appendDeleteVersion(new MvccTransactionId(transactionId), metadata, rowId).lsn();
    }

    void appendCommit(long transactionId, long commitSequence) {
        if (enabled()) {
            writer.appendCommit(new MvccTransactionId(transactionId), new MvccCommitSequence(commitSequence));
        }
    }

    void appendAbort(long transactionId) {
        if (enabled()) {
            writer.appendAbort(new MvccTransactionId(transactionId));
        }
    }

    private static Path logFile(Path databaseDirectory, ContainerKey key) {
        Path directory = InheritedMvccPageVolumeStateStore.inheritedStoreDirectory(databaseDirectory);
        if (directory == null) {
            return null;
        }
        return directory.resolve("conglomerate-" + key.getSegmentId() + "-" + key.getContainerId() + ".wal");
    }
}
