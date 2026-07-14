/*

   DelosDB - Class io.github.ggeorg.delosdb.storage.mvcc.bridge.MvccPreparedCommit

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

import java.util.List;
import java.util.Objects;

import io.github.ggeorg.delosdb.storage.mvcc.MvccTransaction;
import io.github.ggeorg.delosdb.storage.mvcc.store.PageVolumeMvccStateStore;

import org.apache.derby.iapi.store.types.StoreDataValue;

/** Immutable logical and encoded commit input prepared before durable publication. */
record MvccPreparedCommit(
        MvccInheritedHandles.Transaction handle,
        MvccTransaction transaction,
        List<PageVolumeMvccStateStore.PersistedChange<StoreDataValue[]>> changes,
        PageVolumeMvccStateStore.PreparedChanges preparedPageChanges,
        long writeIntentRevision,
        int writeIntentCount,
        List<String> payloadSummaries) {
    MvccPreparedCommit {
        handle = Objects.requireNonNull(handle, "handle");
        transaction = Objects.requireNonNull(transaction, "transaction");
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
        preparedPageChanges = Objects.requireNonNull(preparedPageChanges, "preparedPageChanges");
        payloadSummaries = List.copyOf(Objects.requireNonNull(payloadSummaries, "payloadSummaries"));
        if (writeIntentRevision < 0L) {
            throw new IllegalArgumentException("writeIntentRevision must not be negative");
        }
        if (writeIntentCount < 0) {
            throw new IllegalArgumentException("writeIntentCount must not be negative");
        }
        if (changes.size() != preparedPageChanges.size()) {
            throw new IllegalArgumentException("prepared page-change count does not match logical change count");
        }
        if (changes.size() != payloadSummaries.size()) {
            throw new IllegalArgumentException("prepared payload summary count does not match logical change count");
        }
    }

    int changedRowCount() {
        return changes.size();
    }
}
