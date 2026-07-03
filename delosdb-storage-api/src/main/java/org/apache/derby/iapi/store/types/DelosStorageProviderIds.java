/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageProviderIds

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

import java.util.Locale;

/**
 * Canonical provider identifiers for DelosDB storage diagnostics.
 *
 * <p>This is a small shared cleanup boundary.  It keeps heap/MVCC provider-id
 * normalization in one place so consistency, inspection, and registry code do
 * not grow independent string rules.</p>
 */
public final class DelosStorageProviderIds {
    public static final String MVCC_PROVIDER_ID = "delos_mvcc";
    public static final String HEAP_PROVIDER_ID = "derby_heap";

    private DelosStorageProviderIds() {
    }

    public static String normalize(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("provider id must not be blank");
        }
        return providerId.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean matches(String leftProviderId, String rightProviderId) {
        return normalize(leftProviderId).equals(normalize(rightProviderId));
    }
}
