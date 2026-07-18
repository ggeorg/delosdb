/*

   DelosDB - Class io.github.ggeorg.delosdb.storage.mvcc.bridge.MvccFailureReplayTestSupport

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
package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.apache.derby.iapi.store.types.DelosStorageTableKey;
import org.apache.derby.iapi.store.types.DelosStorageTransaction;
import org.apache.derby.iapi.store.types.DelosStorageTransactionRegistry;
import org.apache.derby.iapi.store.types.StoreDataValue;

/** Shared deterministic two-participant setup and digest helpers for failure replay tests. */
final class MvccFailureReplayTestSupport {
    private MvccFailureReplayTestSupport() {
    }

    static Object writeTwoTables(MvccInheritedStore store) {
        Object owner = new Object();
        MvccInheritedTable first = openTable(store, 1L, 101L);
        MvccInheritedTable second = openTable(store, 1L, 102L);
        DelosStorageTransaction firstTx = first.beginTransaction();
        DelosStorageTransaction secondTx = second.beginTransaction();
        DelosStorageTransactionRegistry.register(owner, first, firstTx);
        DelosStorageTransactionRegistry.register(owner, second, secondTx);
        first.insert(1L, emptyRow(), firstTx);
        second.insert(1L, emptyRow(), secondTx);
        return owner;
    }

    static String reopenedDigest(Path database) {
        MvccInheritedStore store = new MvccInheritedStore(database);
        try {
            MvccInheritedTable first = openTable(store, 1L, 101L);
            MvccInheritedTable second = openTable(store, 1L, 102L);
            return MvccFailureExperimentManifest.digest(List.of(
                    "first:1=" + read(first, 1L).isPresent(),
                    "second:1=" + read(second, 1L).isPresent()));
        } finally {
            store.close();
        }
    }

    static String committedDigest() {
        return MvccFailureExperimentManifest.digest(List.of(
                "first:1=true",
                "second:1=true"));
    }

    static String emptyDigest() {
        return MvccFailureExperimentManifest.digest(List.of(
                "first:1=false",
                "second:1=false"));
    }

    private static MvccInheritedTable openTable(
            MvccInheritedStore store,
            long segmentId,
            long containerId) {
        return (MvccInheritedTable) store.openTable(
                new DelosStorageTableKey(segmentId, containerId));
    }

    private static StoreDataValue[] emptyRow() {
        return new StoreDataValue[0];
    }

    private static Optional<StoreDataValue[]> read(
            MvccInheritedTable table,
            long rowId) {
        DelosStorageTransaction transaction = table.beginReadOnlyTransaction();
        try {
            return table.read(rowId, table.snapshot(transaction));
        } finally {
            table.abort(transaction);
        }
    }
}
