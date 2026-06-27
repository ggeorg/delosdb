/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccStoreAccessTransactionRegistry

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

import org.apache.derby.iapi.store.types.DelosStorageTable;
import org.apache.derby.iapi.store.types.DelosStorageTransaction;
import org.apache.derby.iapi.store.types.DelosStorageTransactionRegistry;

/**
 * Compatibility shim for older MVCC bridge callers.
 *
 * <p>The registry state now lives in the provider-neutral storage-api
 * {@link DelosStorageTransactionRegistry}. This class remains only so existing
 * bridge code and smoke fixtures do not need to import engine internals.</p>
 */
public final class MvccStoreAccessTransactionRegistry {
    private MvccStoreAccessTransactionRegistry() {
    }

    public static Writer register(
            Object derbyTransaction,
            DelosStorageTable table,
            DelosStorageTransaction transaction) {
        return register(derbyTransaction, table, transaction, () -> { });
    }

    public static Writer register(
            Object derbyTransaction,
            DelosStorageTable table,
            DelosStorageTransaction transaction,
            Runnable afterCommit) {
        return new Writer(DelosStorageTransactionRegistry.register(
                derbyTransaction,
                table,
                transaction,
                afterCommit));
    }

    public static void complete(Writer writer) {
        if (writer != null) {
            DelosStorageTransactionRegistry.complete(writer.delegate);
        }
    }

    public static void commit(Object derbyTransaction) {
        DelosStorageTransactionRegistry.commit(derbyTransaction);
    }

    public static void abort(Object derbyTransaction) {
        DelosStorageTransactionRegistry.abort(derbyTransaction);
    }

    public static int pendingCountForTesting(Object derbyTransaction) {
        return DelosStorageTransactionRegistry.pendingCountForTesting(derbyTransaction);
    }

    public static int totalPendingCountForTesting() {
        return DelosStorageTransactionRegistry.totalPendingCountForTesting();
    }

    public static void clearForTesting() {
        DelosStorageTransactionRegistry.clearForTesting();
    }

    public static final class Writer {
        private final DelosStorageTransactionRegistry.Writer delegate;

        private Writer(DelosStorageTransactionRegistry.Writer delegate) {
            this.delegate = delegate;
        }

        public void commit() {
            delegate.commit();
        }

        public void abort() {
            delegate.abort();
        }
    }
}
