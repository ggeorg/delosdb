/*

   DelosDB - Class io.github.ggeorg.delosdb.storage.mvcc.durable.MvccPageMutationContext

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

package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.util.Objects;

/**
 * Narrow mutation boundary for physical MVCC page updates.
 *
 * <p>This first context is intentionally small: it accounts for page capacity
 * reservations, page writes, free-space-map updates, reusable-page-index updates,
 * and clean close/abort discipline. Later overlays can move checksum/generation,
 * redo/checkpoint metadata, overflow, and index page updates behind this same
 * boundary without changing the SQL layer.</p>
 */
final class MvccPageMutationContext implements AutoCloseable {
    private final PageBackedMvccTableStore owner;
    private final String operation;
    private boolean committed;
    private boolean closed;

    MvccPageMutationContext(PageBackedMvccTableStore owner, String operation) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.owner.recordMutationContextBegin(operation);
    }

    String operation() {
        return operation;
    }

    void reservePageCapacity(int bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("reserved bytes must not be negative: " + bytes);
        }
        owner.recordMutationContextPageReservation(bytes);
    }

    void recordPageWrite() {
        owner.recordMutationContextPageWrite();
    }

    void recordFreeSpaceMapUpdate() {
        owner.recordMutationContextFreeSpaceMapUpdate();
    }

    void recordReusablePageIndexUpdate() {
        owner.recordMutationContextReusableIndexUpdate();
    }

    void commit() {
        if (closed) {
            throw new IllegalStateException("MVCC page mutation context is already closed: " + operation);
        }
        committed = true;
        closed = true;
        owner.recordMutationContextCommit(operation);
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            if (!committed) {
                owner.recordMutationContextAbort(operation);
            }
        }
    }
}
