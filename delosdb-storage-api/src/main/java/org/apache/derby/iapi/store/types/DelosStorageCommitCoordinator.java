/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageCommitCoordinator

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

import java.util.List;
import java.util.Objects;

/**
 * Provider-owned coordinator for one failure-atomic storage transaction.
 *
 * <p>The SQL transaction registry supplies the complete participant set. The
 * coordinator must leave every supplied provider transaction terminal before
 * returning or throwing: committed after one authoritative decision, or
 * aborted before that decision.</p>
 */
public interface DelosStorageCommitCoordinator {
    void commit(List<Participant> participants);

    record Participant(DelosStorageTable table, DelosStorageTransaction transaction) {
        public Participant {
            table = Objects.requireNonNull(table, "table");
            transaction = Objects.requireNonNull(transaction, "transaction");
        }
    }
}
