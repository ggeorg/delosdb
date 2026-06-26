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

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import io.github.ggeorg.delosdb.storage.mvcc.MvccTransaction;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionManager;

/**
 * MODULE6G transaction-scoped writer registry for inherited MVCC store/access writes.
 *
 * <p>Derby closes a {@code ConglomerateController} at statement end, before the
 * language transaction commits or rolls back. MODULE6G therefore cannot treat
 * controller close as MVCC commit/abort for normal SQL INSERT. This registry
 * keeps the controller-local MVCC writer attached to Derby's transaction object
 * and lets {@code GenericLanguageConnectionContext} complete it from the normal
 * Derby commit/rollback lifecycle.</p>
 */
public final class MvccStoreAccessTransactionRegistry {
    private static final Map<Object, List<Writer>> WRITERS = new IdentityHashMap<>();

    private MvccStoreAccessTransactionRegistry() {
    }

    public static synchronized Writer register(
            Object derbyTransaction,
            MvccTransactionManager manager,
            MvccTransaction transaction) {
        return register(derbyTransaction, manager, transaction, () -> { });
    }

    public static synchronized Writer register(
            Object derbyTransaction,
            MvccTransactionManager manager,
            MvccTransaction transaction,
            Runnable afterCommit) {
        Writer writer = new Writer(derbyTransaction, manager, transaction, afterCommit);
        WRITERS.computeIfAbsent(derbyTransaction, ignored -> new ArrayList<>()).add(writer);
        return writer;
    }

    public static synchronized void complete(Writer writer) {
        if (writer == null) {
            return;
        }
        List<Writer> writers = WRITERS.get(writer.derbyTransaction);
        if (writers == null) {
            return;
        }
        writers.remove(writer);
        if (writers.isEmpty()) {
            WRITERS.remove(writer.derbyTransaction);
        }
    }

    public static void commit(Object derbyTransaction) {
        for (Writer writer : drain(derbyTransaction)) {
            writer.commit();
        }
    }

    public static void abort(Object derbyTransaction) {
        for (Writer writer : drain(derbyTransaction)) {
            writer.abort();
        }
    }

    public static synchronized int pendingCountForTesting(Object derbyTransaction) {
        List<Writer> writers = WRITERS.get(derbyTransaction);
        return writers == null ? 0 : writers.size();
    }

    public static synchronized int totalPendingCountForTesting() {
        int count = 0;
        for (List<Writer> writers : WRITERS.values()) {
            count += writers.size();
        }
        return count;
    }

    public static synchronized void clearForTesting() {
        WRITERS.clear();
    }

    private static synchronized List<Writer> drain(Object derbyTransaction) {
        List<Writer> writers = WRITERS.remove(derbyTransaction);
        if (writers == null || writers.isEmpty()) {
            return List.of();
        }
        return List.copyOf(writers);
    }

    public static final class Writer {
        private final Object derbyTransaction;
        private final MvccTransactionManager manager;
        private final MvccTransaction transaction;
        private final Runnable afterCommit;
        private boolean completed;

        private Writer(
                Object derbyTransaction,
                MvccTransactionManager manager,
                MvccTransaction transaction,
                Runnable afterCommit) {
            this.derbyTransaction = derbyTransaction;
            this.manager = manager;
            this.transaction = transaction;
            this.afterCommit = afterCommit == null ? () -> { } : afterCommit;
        }

        public void commit() {
            if (!completed) {
                manager.commit(transaction);
                afterCommit.run();
                completed = true;
            }
        }

        public void abort() {
            if (!completed) {
                manager.abort(transaction);
                completed = true;
            }
        }
    }
}
