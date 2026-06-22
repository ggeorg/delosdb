/*

   Derby - Class org.apache.derby.iapi.store.types.DelosTableAccess

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

import java.util.Set;

/**
 * Store-neutral table identity and shape contract.
 *
 * <p>C20 intentionally keeps the base contract non-operational: it does not
 * expose scans, index access, inserts, updates, or deletes.  A no-store profile
 * such as storeless can implement this interface only and decline every
 * physical access path by not implementing the capability-specific siblings.</p>
 */
public interface DelosTableAccess {
    /** Stable catalog identity for this table access object. */
    DelosTableIdentity identity();

    /** Logical row shape visible above the storage boundary. */
    DelosTableShape rowShape();

    /** Capabilities advertised by the concrete table access object. */
    DelosTableCapabilities capabilities();

    /**
     * Semantic guarantees advertised by the concrete table access object.
     *
     * <p>The default is intentionally empty so a base-only/no-store profile can
     * implement the identity/shape contract without promising physical access
     * or transactional behavior.</p>
     */
    default Set<DelosTableGuarantee> guarantees() {
        return Set.of();
    }
}
