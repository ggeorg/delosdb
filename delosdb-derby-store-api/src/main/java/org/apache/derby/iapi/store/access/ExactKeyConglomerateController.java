/*

   Derby - Class org.apache.derby.iapi.store.access.ExactKeyConglomerateController

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
package org.apache.derby.iapi.store.access;

import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Optional controller capability for deleting a fully specified keyed row
 * without constructing a separate scan controller.
 *
 * <p>The supplied row must contain the complete stored key, including any
 * base-row location field used to disambiguate duplicate logical keys. The
 * caller remains responsible for the normal base-row and transaction locking
 * contract of the owning DML operation.</p>
 */
public interface ExactKeyConglomerateController {
    /**
     * Delete the row whose complete stored key equals {@code row}.
     *
     * @return {@code true} when a live row was deleted, or {@code false} when
     *         no live exact match exists
     */
    boolean deleteExact(StoreDataValue[] row) throws StandardException;
}
