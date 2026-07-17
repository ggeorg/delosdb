/*

   Derby - Class org.apache.derby.impl.store.raw.data.DelosDatabaseCommitDecisionUndoOperation

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

import org.apache.derby.iapi.services.io.LimitObjectInput;
import org.apache.derby.iapi.services.io.StoredFormatIds;
import org.apache.derby.iapi.store.raw.Compensation;
import org.apache.derby.iapi.store.raw.Loggable;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.raw.Undoable;
import org.apache.derby.iapi.store.raw.log.LogInstant;
import org.apache.derby.iapi.util.ByteArray;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.sanity.SanityManager;

/** Compensation operation which removes an uncommitted database decision marker. */
public final class DelosDatabaseCommitDecisionUndoOperation implements Compensation {
    private transient DelosDatabaseCommitDecisionOperation undoOperation;

    public DelosDatabaseCommitDecisionUndoOperation() {
    }

    DelosDatabaseCommitDecisionUndoOperation(DelosDatabaseCommitDecisionOperation undoOperation) {
        this.undoOperation = undoOperation;
    }

    @Override
    public void writeExternal(ObjectOutput out) {
    }

    @Override
    public void readExternal(ObjectInput in) {
    }

    @Override
    public int getTypeFormatId() {
        return StoredFormatIds.LOGOP_DELOS_DATABASE_COMMIT_DECISION_UNDO;
    }

    @Override
    public void setUndoOp(Undoable operation) {
        if (SanityManager.DEBUG) {
            SanityManager.ASSERT(operation instanceof DelosDatabaseCommitDecisionOperation);
        }
        undoOperation = (DelosDatabaseCommitDecisionOperation) operation;
    }

    @Override
    public boolean needsRedo(Transaction transaction) throws StandardException {
        return undoOperation == null
                || DelosDatabaseCommitDecisionOperation.decisionExists(
                        transaction, undoOperation.decision());
    }

    @Override
    public ByteArray getPreparedLog() {
        return null;
    }

    @Override
    public void doMe(Transaction transaction, LogInstant instant, LimitObjectInput in)
            throws StandardException, IOException {
        DelosDatabaseCommitDecisionOperation.deleteDecision(
                transaction, requireUndoOperation().decision());
    }

    @Override
    public void releaseResource(Transaction transaction) {
    }

    @Override
    public int group() {
        return Loggable.COMPENSATION | Loggable.RAWSTORE;
    }

    private DelosDatabaseCommitDecisionOperation requireUndoOperation() {
        if (undoOperation == null) {
            throw new IllegalStateException("database decision undo operation is not bound");
        }
        return undoOperation;
    }
}
