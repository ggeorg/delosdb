/*

   Derby - Class org.apache.derby.iapi.store.types.DelosMutationResult

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

import java.util.Optional;

/** Result from a row-identity based mutation. */
public record DelosMutationResult(long affectedRows, Optional<DelosRowIdentity> rowIdentity) {
    public DelosMutationResult {
        if (affectedRows < 0) {
            throw new IllegalArgumentException("affectedRows must not be negative");
        }
        rowIdentity = rowIdentity == null ? Optional.empty() : rowIdentity;
    }

    public static DelosMutationResult affected(long affectedRows) {
        return new DelosMutationResult(affectedRows, Optional.empty());
    }

    public static DelosMutationResult inserted(DelosRowIdentity rowIdentity) {
        return new DelosMutationResult(1, Optional.of(rowIdentity));
    }
}
