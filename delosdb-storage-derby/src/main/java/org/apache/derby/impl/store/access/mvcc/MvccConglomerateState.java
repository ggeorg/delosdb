/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccConglomerateState

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

import io.github.ggeorg.delosdb.storage.mvcc.MvccTable;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionManager;

import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.types.StoreDataValue;

/**
 * MODULE6D shared state behind the inherited MVCC conglomerate skeleton.
 *
 * <p>This remains a preflight state holder, not a final catalog/directory or
 * page-backed table root. Its purpose is to prove that inherited Derby
 * ConglomerateController writes can feed the MVCC visibility kernel and that
 * inherited ScanController reads can consume a snapshot from that kernel.</p>
 */
final class MvccConglomerateState {
    private final ContainerKey key;
    private final MvccTable<Long, StoreDataValue[]> table = new MvccTable<>();
    private final MvccTransactionManager transactions = new MvccTransactionManager();
    private long nextRowId = 1L;

    MvccConglomerateState(ContainerKey key) {
        this.key = key;
    }

    ContainerKey key() {
        return key;
    }

    MvccTable<Long, StoreDataValue[]> table() {
        return table;
    }

    MvccTransactionManager transactions() {
        return transactions;
    }

    synchronized long nextRowId() {
        return nextRowId++;
    }
}
