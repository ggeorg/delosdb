/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageMetadataProvider

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
 * Provider boundary for read-only DelosDB storage metadata.
 *
 * <p>This is deliberately a small provider-chain abstraction inspired by
 * planner metadata systems. It is not a Calcite dependency and it does not
 * change Derby optimizer behavior. The chain gives heap, MVCC, and future
 * storage modes separate metadata providers behind one query facade.</p>
 */
public interface DelosStorageMetadataProvider {
    String providerId();

    default boolean supports(DelosStorageConsistencyTarget target) {
        Objects.requireNonNull(target, "target");
        return DelosStorageProviderIds.matches(providerId(), target.providerId());
    }

    DelosStorageMetadataSnapshot snapshot(DelosStorageConsistencyTarget target);

    static DelosStorageMetadataProvider fromDiagnostics(DelosStorageDiagnostics diagnostics) {
        return new DelosDiagnosticsStorageMetadataProvider(diagnostics);
    }
}
