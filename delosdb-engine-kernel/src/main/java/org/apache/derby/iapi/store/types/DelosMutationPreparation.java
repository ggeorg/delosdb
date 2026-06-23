/*

   Derby - Class org.apache.derby.iapi.store.types.DelosMutationPreparation

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

import java.util.Objects;

/**
 * Result of an optimistic row-identity mutation preparation step.
 *
 * <p>Phase I Option A is deliberately not a lock API.  A successful instance
 * means the provider has validated that the supplied row identity is currently
 * mutable for the supplied access context.  It does not claim that a row lock,
 * reservation, or write latch has been acquired.</p>
 */
public record DelosMutationPreparation(
        DelosRowIdentity rowIdentity,
        boolean mutable,
        boolean prepared,
        String message) {
    public DelosMutationPreparation {
        rowIdentity = Objects.requireNonNull(rowIdentity, "rowIdentity");
        message = message == null ? "" : message;
        if (prepared && !mutable) {
            throw new IllegalArgumentException("prepared mutations must also be mutable");
        }
    }

    public static DelosMutationPreparation mutable(DelosRowIdentity rowIdentity, String message) {
        return new DelosMutationPreparation(rowIdentity, true, false, message);
    }

    public static DelosMutationPreparation prepared(DelosRowIdentity rowIdentity, String message) {
        return new DelosMutationPreparation(rowIdentity, true, true, message);
    }

    public static DelosMutationPreparation notMutable(DelosRowIdentity rowIdentity, String message) {
        return new DelosMutationPreparation(rowIdentity, false, false, message);
    }
}
