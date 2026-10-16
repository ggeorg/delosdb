/*

   Derby - Class org.apache.derby.iapi.store.access.conglomerate.AccessMethodTransactionLifecycle

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
package org.apache.derby.iapi.store.access.conglomerate;

import java.util.Objects;

import org.apache.derby.iapi.store.access.DatabaseInstant;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Database-access-method state attached to one access transaction.
 *
 * <p>The access transaction brackets the real RawStore operations and invokes
 * these callbacks in registration order. Physical undo, commit, savepoint and
 * recovery authority remains in RawStore. Implementations may retain only
 * transaction-local semantic state.</p>
 *
 * <p>Post-operation callbacks run after RawStore has already completed the
 * corresponding operation. They must therefore be deterministic and must not
 * initiate an independent commit, abort or durable publication.</p>
 */
public interface AccessMethodTransactionLifecycle {

    /** Commit form selected by the inherited access transaction. */
    enum CommitMode {
        SYNCHRONIZED,
        NO_SYNC_RELEASE_LOCKS,
        NO_SYNC_KEEP_LOCKS
    }

    /** XA boundary which must be observed before RawStore changes XA state. */
    enum XaOperation {
        MORPH_LOCAL_TO_XA,
        PREPARE,
        COMMIT_ONE_PHASE,
        COMMIT_TWO_PHASE,
        ROLLBACK
    }

    /** Exact inherited savepoint identity. */
    record SavepointIdentity(String name, Object kind) {
        public SavepointIdentity {
            Objects.requireNonNull(name, "name");
        }
    }

    /** Called after non-held controllers close and before RawStore commit. */
    default void beforeCommit(CommitMode mode) throws StandardException {
    }

    /** Called only after RawStore reports a successful commit. */
    default void afterCommit(CommitMode mode, DatabaseInstant instant) {
    }

    /** Called when commit preparation or RawStore commit fails. */
    default void commitFailed(CommitMode mode, Throwable failure) {
    }

    /** Called before controller closure and RawStore abort. */
    default void beforeAbort() {
    }

    /** Called only after RawStore reports a successful abort. */
    default void afterAbort() {
    }

    /** Called when abort preparation or RawStore abort fails. */
    default void abortFailed(Throwable failure) {
    }

    /** Called after RawStore successfully creates the savepoint. */
    default void afterSetSavepoint(SavepointIdentity savepoint) throws StandardException {
    }

    /** Called after RawStore successfully rolls back to the retained savepoint. */
    default void afterRollbackToSavepoint(SavepointIdentity savepoint) throws StandardException {
    }

    /** Called after RawStore successfully releases the savepoint and newer markers. */
    default void afterReleaseSavepoint(SavepointIdentity savepoint) throws StandardException {
    }

    /** Called before a nested user transaction is created. */
    default void beforeNestedUserTransaction(boolean readOnly) throws StandardException {
    }

    /** Called before RawStore enters the requested XA operation. */
    default void beforeXaOperation(XaOperation operation) throws StandardException {
    }

    /** Called before transaction destruction begins. Must not publish commit state. */
    default void beforeDestroy() {
    }

    /** Called in the destruction finally path, even when RawStore destroy fails. */
    default void afterDestroy() {
    }
}
