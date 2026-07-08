/*

   DelosDB - Class io.github.ggeorg.delosdb.storage.mvcc.store.MvccStorageLifecycleJfr

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
package io.github.ggeorg.delosdb.storage.mvcc.store;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/**
 * MVCC-local JFR hooks for durable store lifecycle points.
 *
 * <p>This helper intentionally lives in the MVCC store package so durable MVCC
 * code does not import Derby {@code org.apache.derby.*} API classes merely to
 * emit observability events. Events are observability-only and must not change
 * checkpoint ordering, recovery ordering, storage format, or visibility rules.</p>
 */
final class MvccStorageLifecycleJfr {
    private static final String EMPTY = "";

    private MvccStorageLifecycleJfr() {
    }

    static void recordCheckpoint(
            String storageId,
            long physicalVersionCount,
            long logicalRowCount,
            String status,
            boolean success,
            String failure) {
        MvccCheckpointEvent event = new MvccCheckpointEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.storageId = normalize(storageId);
        event.physicalVersionCount = physicalVersionCount;
        event.logicalRowCount = logicalRowCount;
        event.status = normalize(status);
        event.success = success;
        event.failure = normalize(failure);
        event.commit();
    }

    private static String normalize(String value) {
        return value == null ? EMPTY : value;
    }

    @Name("org.apache.derby.delosdb.mvcc.Checkpoint")
    @Label("DelosDB MVCC Checkpoint")
    @Category({"DelosDB", "Storage", "MVCC"})
    @Description("Records MVCC checkpoint rewrite lifecycle points.")
    public static final class MvccCheckpointEvent extends Event {
        @Label("Storage ID")
        public String storageId;
        @Label("Physical Versions")
        public long physicalVersionCount;
        @Label("Logical Rows")
        public long logicalRowCount;
        @Label("Status")
        public String status;
        @Label("Success")
        public boolean success;
        @Label("Failure")
        public String failure;
    }
}
