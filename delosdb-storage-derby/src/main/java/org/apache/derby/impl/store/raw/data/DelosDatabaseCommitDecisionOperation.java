/*

   Derby - Class org.apache.derby.impl.store.raw.data.DelosDatabaseCommitDecisionOperation

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
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.OutputStream;
import java.util.Arrays;

import org.apache.derby.iapi.services.io.LimitObjectInput;
import org.apache.derby.iapi.services.io.StoredFormatIds;
import org.apache.derby.iapi.store.raw.Compensation;
import org.apache.derby.iapi.store.raw.Loggable;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.raw.Undoable;
import org.apache.derby.iapi.store.raw.log.LogInstant;
import org.apache.derby.iapi.store.raw.xact.RawTransaction;
import org.apache.derby.iapi.store.types.DelosDatabaseCommitDecision;
import org.apache.derby.iapi.util.ByteArray;
import org.apache.derby.io.StorageFactory;
import org.apache.derby.io.StorageFile;
import org.apache.derby.io.WritableStorageFactory;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/** Transactional raw-store marker for one database-scoped commit decision. */
public final class DelosDatabaseCommitDecisionOperation implements Undoable {
    private long transactionId;
    private long commitSequence;

    public DelosDatabaseCommitDecisionOperation() {
    }

    public DelosDatabaseCommitDecisionOperation(DelosDatabaseCommitDecision decision) {
        this.transactionId = decision.transactionId();
        this.commitSequence = decision.commitSequence();
    }

    DelosDatabaseCommitDecision decision() {
        return new DelosDatabaseCommitDecision(transactionId, commitSequence);
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        out.writeLong(transactionId);
        out.writeLong(commitSequence);
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException {
        transactionId = in.readLong();
        commitSequence = in.readLong();
        decision();
    }

    @Override
    public int getTypeFormatId() {
        return StoredFormatIds.LOGOP_DELOS_DATABASE_COMMIT_DECISION;
    }

    @Override
    public ByteArray getPreparedLog() {
        return null;
    }

    @Override
    public void doMe(Transaction transaction, LogInstant instant, LimitObjectInput in)
            throws StandardException {
        writeDecision(transaction, decision());
    }

    @Override
    public boolean needsRedo(Transaction transaction) throws StandardException {
        StorageFile marker = markerFile(transaction, decision());
        if (!marker.exists()) {
            return true;
        }
        try (InputStream input = marker.getInputStream()) {
            return !Arrays.equals(input.readAllBytes(), decision().encoded());
        } catch (IOException failure) {
            throw fileFailure(marker, failure);
        }
    }

    @Override
    public Compensation generateUndo(Transaction transaction, LimitObjectInput in) {
        return new DelosDatabaseCommitDecisionUndoOperation(this);
    }

    @Override
    public void releaseResource(Transaction transaction) {
    }

    @Override
    public int group() {
        return Loggable.RAWSTORE;
    }

    static void deleteDecision(Transaction transaction, DelosDatabaseCommitDecision decision)
            throws StandardException {
        StorageFile marker = markerFile(transaction, decision);
        if (marker.exists() && !marker.delete()) {
            throw StandardException.newException(
                    SQLState.FILE_UNEXPECTED_EXCEPTION,
                    new IOException("Unable to remove database commit decision " + marker.getPath()));
        }
    }

    static boolean decisionExists(Transaction transaction, DelosDatabaseCommitDecision decision)
            throws StandardException {
        return markerFile(transaction, decision).exists();
    }

    private static void writeDecision(
            Transaction transaction,
            DelosDatabaseCommitDecision decision) throws StandardException {
        StorageFactory storageFactory = storageFactory(transaction);
        if (!(storageFactory instanceof WritableStorageFactory writable)) {
            throw StandardException.newException(SQLState.FILE_READ_ONLY);
        }
        StorageFile directory = storageFactory.newStorageFile(DelosDatabaseCommitDecision.DIRECTORY);
        if (!directory.exists() && !directory.mkdirs() && !directory.exists()) {
            throw StandardException.newException(
                    SQLState.FILE_UNEXPECTED_EXCEPTION,
                    new IOException("Unable to create database decision directory " + directory.getPath()));
        }
        try {
            directory.limitAccessToOwner();
        } catch (IOException failure) {
            throw fileFailure(directory, failure);
        }

        StorageFile marker = storageFactory.newStorageFile(decision.relativePath());
        try (OutputStream output = marker.getOutputStream()) {
            output.write(decision.encoded());
            writable.sync(output, true);
        } catch (IOException failure) {
            throw fileFailure(marker, failure);
        }
    }

    private static StorageFile markerFile(
            Transaction transaction,
            DelosDatabaseCommitDecision decision) throws StandardException {
        return storageFactory(transaction).newStorageFile(decision.relativePath());
    }

    private static StorageFactory storageFactory(Transaction transaction) {
        return ((RawTransaction) transaction).getDataFactory().getStorageFactory();
    }

    private static StandardException fileFailure(StorageFile file, IOException failure) {
        return StandardException.newException(
                SQLState.FILE_UNEXPECTED_EXCEPTION,
                new IOException("Database commit decision I/O failed for " + file.getPath(), failure));
    }

    @Override
    public String toString() {
        return "DelosDB database commit decision " + transactionId + "/" + commitSequence;
    }
}
