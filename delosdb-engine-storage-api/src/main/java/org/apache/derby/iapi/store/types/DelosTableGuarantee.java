/*

   Derby - Class org.apache.derby.iapi.store.types.DelosTableGuarantee

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
 * Semantic guarantees advertised by a {@link DelosTableAccess}.
 *
 * <p>Guarantees are deliberately separate from {@link DelosTableCapability}.
 * Capabilities describe which method surface exists.  Guarantees describe the
 * behavioral promises a provider makes when that surface is used.</p>
 */
public enum DelosTableGuarantee {
    /** Provider supplies row-level locking semantics for mutable access. */
    ROW_LOCKING,

    /** Provider has a durable recovery log; this is not claimed to be full WAL/PITR. */
    DURABLE_RECOVERY_LOG,

    /** Reads observe an MVCC snapshot rather than Derby heap lock-based visibility. */
    SNAPSHOT_ISOLATION
}
