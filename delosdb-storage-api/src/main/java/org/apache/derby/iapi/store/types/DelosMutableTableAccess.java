/*

   Derby - Class org.apache.derby.iapi.store.types.DelosMutableTableAccess

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
 * Optional table access capability for transaction-aware row mutation.
 *
 * <p>Updates and deletes are by opaque row identity produced by the same table
 * access implementation.  The contract intentionally does not accept SQL text
 * and does not require identities to compare across providers.</p>
 */
public interface DelosMutableTableAccess extends DelosTableAccess {
    /**
     * Validate whether a row identity is mutable in the supplied context.
     *
     * <p>This is optimistic validation, not a lock or reservation. Providers
     * that cannot validate more deeply may conservatively report the identity
     * as mutable after checking only the common physical-access and identity
     * preconditions.</p>
     */
    default DelosMutationPreparation validateMutable(
            DelosAccessContext context,
            DelosRowIdentity rowIdentity) {
        requireMutablePreconditions(context, rowIdentity);
        return DelosMutationPreparation.mutable(rowIdentity, "row identity accepted by default validation");
    }

    /**
     * Prepare a row-identity mutation without claiming a lock acquisition.
     *
     * <p>The default implementation delegates to {@link #validateMutable} and
     * marks the result as prepared only if validation succeeded.</p>
     */
    default DelosMutationPreparation prepareMutation(
            DelosAccessContext context,
            DelosRowIdentity rowIdentity) {
        DelosMutationPreparation validation = validateMutable(context, rowIdentity);
        if (!validation.mutable()) {
            return validation;
        }
        return DelosMutationPreparation.prepared(rowIdentity, validation.message());
    }

    private static void requireMutablePreconditions(
            DelosAccessContext context,
            DelosRowIdentity rowIdentity) {
        if (!Objects.requireNonNull(context, "context").physicalAccessAllowed()) {
            throw new IllegalStateException("Physical table mutation access is not allowed by context");
        }
        Objects.requireNonNull(rowIdentity, "rowIdentity");
    }

    DelosMutationResult insert(DelosAccessContext context, DelosRow row);

    DelosMutationResult update(DelosAccessContext context,
                               DelosRowIdentity rowIdentity,
                               DelosRow replacement);

    DelosMutationResult delete(DelosAccessContext context, DelosRowIdentity rowIdentity);
}
