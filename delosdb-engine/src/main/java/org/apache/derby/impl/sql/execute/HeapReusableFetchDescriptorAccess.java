/*

   Derby - Class org.apache.derby.impl.sql.execute.HeapReusableFetchDescriptorAccess

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

import java.util.concurrent.atomic.LongAdder;

/** Internal switch and diagnostics for the reusable Heap fetch-descriptor experiment. */
final class HeapReusableFetchDescriptorAccess {
    private static final String ENABLE_PROPERTY =
            "delosdb.experimental.heapReusableFetchDescriptor";
    private static final String DIAGNOSTIC_PROPERTY =
            "delosdb.diagnostic.heapReusableFetchDescriptor";

    private static final LongAdder FETCHES = new LongAdder();

    private HeapReusableFetchDescriptorAccess() {
    }

    static boolean enabled() {
        return Boolean.getBoolean(ENABLE_PROPERTY);
    }

    static void recordFetch() {
        if (Boolean.getBoolean(DIAGNOSTIC_PROPERTY)) {
            FETCHES.increment();
        }
    }

    static void resetDiagnosticsForTesting() {
        FETCHES.reset();
    }

    static long diagnosticsForTesting() {
        return FETCHES.sum();
    }
}
