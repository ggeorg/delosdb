/*

   Derby - Class org.apache.derby.iapi.store.types.DelosRawStoreCommitParticipant

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

import org.apache.derby.shared.common.error.StandardException;

/** Raw-store capability used to enlist Derby heap commit in one database decision. */
public interface DelosRawStoreCommitParticipant {
    void stageDatabaseCommitDecision(DelosDatabaseCommitDecision decision)
            throws StandardException;

    /** Log one transactional {@code delos_mvcc} conglomerate create/drop lifecycle. */
    void stageMvccConglomerateLifecycle(DelosMvccConglomerateLifecycle lifecycle)
            throws StandardException;

    /**
     * Return whether the raw store forced the staged transaction decision even
     * if completion processing subsequently failed.
     */
    default boolean isDatabaseCommitDecisionDurable() {
        return false;
    }

    /** Enable or disable raw-store decision-force timing for the next commit. */
    default void setDatabaseCommitDecisionTimingEnabled(boolean enabled) {
    }

    /** Return the raw-store log-force interval for the most recent commit. */
    default long databaseCommitDecisionForceNanos() {
        return 0L;
    }
}
