/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageTransactionDiagnostics

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

/** Value-only diagnostic capability implemented by an opaque provider transaction handle. */
public interface DelosStorageTransactionDiagnostics {
    /** Capture one internally coherent provider-transaction observation. */
    Values diagnosticValues();

    default long providerTransactionId() {
        return diagnosticValues().providerTransactionId();
    }

    default boolean readOnly() {
        return diagnosticValues().readOnly();
    }

    default int writeIntentCount() {
        return diagnosticValues().writeIntentCount();
    }

    default int appendedWriteIntentCount() {
        return diagnosticValues().appendedWriteIntentCount();
    }

    default int savepointCount() {
        return diagnosticValues().savepointCount();
    }

    default long writeIntentRevision() {
        return diagnosticValues().writeIntentRevision();
    }

    /** Immutable provider-owned values captured as one atomic diagnostic unit. */
    record Values(
            long providerTransactionId,
            boolean readOnly,
            int writeIntentCount,
            int appendedWriteIntentCount,
            int savepointCount,
            long writeIntentRevision) {
        public Values {
            if (providerTransactionId <= 0L
                    || writeIntentCount < 0
                    || appendedWriteIntentCount < 0
                    || savepointCount < 0
                    || writeIntentRevision < 0L) {
                throw new IllegalArgumentException(
                        "provider transaction identity and counters must be non-negative");
            }
            if (appendedWriteIntentCount < writeIntentCount) {
                throw new IllegalArgumentException(
                        "appended write-intent count cannot be smaller than surviving intents");
            }
            if (readOnly && (writeIntentCount != 0 || appendedWriteIntentCount != 0)) {
                throw new IllegalArgumentException(
                        "read-only provider transaction cannot expose write intents");
            }
        }
    }
}
