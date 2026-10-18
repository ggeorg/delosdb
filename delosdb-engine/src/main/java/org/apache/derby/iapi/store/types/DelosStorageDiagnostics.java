/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageDiagnostics

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
 * Provider-neutral storage diagnostics façade.
 *
 * <p>The façade preserves the stable diagnostics type used by tests and
 * inspection tools, while the inherited interfaces keep database lifecycle,
 * persistence, space management, maintenance, index, cache/consistency, and
 * operation concerns independently readable and maintainable.</p>
 */
public interface DelosStorageDiagnostics
        extends DelosStorageDatabaseDiagnostics,
                DelosStorageSpaceDiagnostics {
    // The heap provider overrides these with checks of its container files;
    // no other provider has heap containers to inspect.
    default DelosHeapRawStoreBoundaryDiagnostics heapRawStoreBoundaryDiagnosticsForTesting(
            int segment,
            long containerId) {
        throw heapOnly("raw-store boundary");
    }

    default DelosHeapStorageStatistics heapStorageStatisticsForTesting(
            int segment,
            long containerId,
            long... indexContainerIds) {
        return DelosHeapStorageStatistics.fromDiagnostics(this, segment, containerId, indexContainerIds);
    }

    default DelosHeapStorageDiagnostics heapStorageDiagnosticsForTesting(
            int segment,
            long containerId,
            long... indexContainerIds) {
        throw heapOnly("storage");
    }

    default DelosHeapSanityDiagnostics heapSanityDiagnosticsForTesting(int segment, long containerId) {
        throw heapOnly("sanity");
    }

    private UnsupportedOperationException heapOnly(String diagnostics) {
        return new UnsupportedOperationException(
                "heap " + diagnostics + " diagnostics are not available for provider " + providerId());
    }
}
