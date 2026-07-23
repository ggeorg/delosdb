/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageInspector

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

/** Provider-neutral read-only storage inspection surface. */
public interface DelosStorageInspector {
    String providerId();

    DelosStorageInspection inspect(int segment, long containerId);

    static DelosStorageInspector fromDiagnostics(DelosStorageDiagnostics diagnostics) {
        Objects.requireNonNull(diagnostics, "diagnostics");
        return new DelosStorageInspector() {
            @Override
            public String providerId() {
                return diagnostics.providerId();
            }

            @Override
            public DelosStorageInspection inspect(int segment, long containerId) {
                return DelosStorageInspection.fromDiagnostics(diagnostics, segment, containerId);
            }
        };
    }
}
