/*

   Derby - Class org.apache.derby.iapi.store.types.DelosTransactionSnapshot

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
package org.apache.derby.iapi.store.types;

/** Versioned immutable observation of one active provider transaction participant. */
public record DelosTransactionSnapshot(
        int schemaVersion,
        String providerId,
        String databaseIdentity,
        long segmentId,
        long containerId,
        long providerTransactionId,
        String accessMode,
        String state,
        long capturedAtEpochMillis,
        int writeIntentCount,
        int appendedWriteIntentCount,
        int savepointCount,
        long writeIntentRevision) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String READ_ONLY = "READ_ONLY";
    public static final String READ_WRITE = "READ_WRITE";
    public static final String ACTIVE = "ACTIVE";

    public DelosTransactionSnapshot {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        providerId = DelosStorageProviderIds.normalize(providerId);
        databaseIdentity = DelosStorageText.requireNonBlank(
                databaseIdentity, "databaseIdentity");
        accessMode = DelosStorageText.requireNonBlank(accessMode, "accessMode");
        state = DelosStorageText.requireNonBlank(state, "state");
        if (!READ_ONLY.equals(accessMode) && !READ_WRITE.equals(accessMode)) {
            throw new IllegalArgumentException("unsupported transaction accessMode: " + accessMode);
        }
        if (!ACTIVE.equals(state)) {
            throw new IllegalArgumentException("unsupported transaction state: " + state);
        }
        if (segmentId < 0 || containerId < 0L || providerTransactionId <= 0L
                || capturedAtEpochMillis < 0L || writeIntentCount < 0
                || appendedWriteIntentCount < 0 || savepointCount < 0
                || writeIntentRevision < 0L) {
            throw new IllegalArgumentException("transaction identity and counters must be non-negative");
        }
        if (READ_ONLY.equals(accessMode)
                && (writeIntentCount != 0 || appendedWriteIntentCount != 0)) {
            throw new IllegalArgumentException("read-only transaction cannot expose write intents");
        }
    }

    public String tableIdentity() {
        return "segment-" + segmentId + "/container-" + containerId;
    }

}
