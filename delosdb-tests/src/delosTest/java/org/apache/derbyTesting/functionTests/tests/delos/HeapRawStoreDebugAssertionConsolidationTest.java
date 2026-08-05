/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.HeapRawStoreDebugAssertionConsolidationTest

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

package org.apache.derbyTesting.functionTests.tests.delos;

import junit.framework.TestCase;
import org.apache.derby.impl.store.raw.data.D_RawPageSanityAssertions;

/**
 * Verifies that heap/raw-store debug assertion consolidation preserves the
 * inherited assertion text shape while centralizing duplicate message helpers.
 */
public final class HeapRawStoreDebugAssertionConsolidationTest extends TestCase {
    public void testRecordIdPageChangeMessageIsStable() {
        assertEquals(
                "recordId changed from 10 to 11 but page number did not change 42",
                D_RawPageSanityAssertions.recordIdPageChangeMessage(10, 11, 42));
    }

    public void testRestoreSlotMismatchMessageIsStable() {
        assertEquals(
                "restoreMe cannot restore to a different slot. doMe slot:3 undoMe slot: 4 recordId:99",
                D_RawPageSanityAssertions.restoreSlotMismatchMessage(3, 4, 99));
    }
}
