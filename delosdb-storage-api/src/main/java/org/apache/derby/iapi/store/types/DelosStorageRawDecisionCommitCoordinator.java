/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageRawDecisionCommitCoordinator

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

/** Coordinator capable of staging external participants before Derby raw-store commit. */
public interface DelosStorageRawDecisionCommitCoordinator extends DelosStorageCommitCoordinator {
    PreparedCommit prepareForRawStoreDecision(List<Participant> participants);

    interface PreparedCommit {
        DelosDatabaseCommitDecision decision();

        /** Test/research-only protocol boundary immediately before Derby raw-store commit. */
        default void beforeRawStoreCommit() {
        }

        /** Test/research-only protocol boundary immediately after Derby raw-store commit. */
        default void afterRawStoreCommit() {
        }

        /** Called only after Derby raw store has committed the decision marker. */
        void publishAfterRawStoreCommit();

        /** Called only when the raw-store transaction did not commit. */
        void abortBeforeRawStoreCommit();
    }
}
