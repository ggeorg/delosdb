/*

   Derby - Class org.apache.derby.impl.sql.execute.DelosMutationConflictMapper

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

package org.apache.derby.impl.sql.execute;

import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedWriteConflictException;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

import java.util.Objects;

/**
 * Maps provider-neutral MVCC write/write conflicts at the Derby native
 * mutation boundary.
 *
 * <p>Phase I2 deliberately does not claim row locks or reservation ownership.
 * It only makes the already-existing MVCC conflict signal observable through
 * Derby's transaction-conflict SQLState for native UPDATE/DELETE execution.</p>
 */
final class DelosMutationConflictMapper {
    private DelosMutationConflictMapper() {
    }

    static StandardException transactionConflict(
            VersionedWriteConflictException conflict,
            String operation,
            String qualifiedTableName) {
        Objects.requireNonNull(conflict, "conflict");
        String conflictDetail = "delos_mvcc " + Objects.requireNonNull(operation, "operation")
                + " write/write conflict on " + Objects.requireNonNull(qualifiedTableName, "qualifiedTableName")
                + ": " + conflict.getMessage();
        return StandardException.newException(
                SQLState.DEADLOCK,
                conflict,
                conflictDetail,
                "delos_mvcc-native-mutation");
    }
}
