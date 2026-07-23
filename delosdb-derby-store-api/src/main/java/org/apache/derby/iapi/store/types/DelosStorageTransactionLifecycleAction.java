/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageTransactionLifecycleAction

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

/**
 * External storage lifecycle work subordinate to one Derby raw-store transaction.
 *
 * <p>Commit is invoked only after the authoritative raw-store commit. Abort is
 * invoked only while the raw-store transaction is still abortable or after it
 * has been rolled back. Implementations must be idempotent because reopen
 * recovery may complete the same lifecycle from the raw log.</p>
 */
public interface DelosStorageTransactionLifecycleAction {
    DelosMvccConglomerateLifecycle lifecycle();

    void commitAfterRawStoreCommit();

    void abortBeforeRawStoreCommit();
}
