/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.HeapRawStoreDiagnosticCompatibilityTest

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

import org.apache.derby.impl.store.access.conglomerate.D_DiagnosticFormatting;
import org.apache.derby.impl.store.raw.data.D_RawPageSanityAssertions;

/** Stable inherited diagnostic-text contract for heap/raw-store helper consolidation. */
public final class HeapRawStoreDiagnosticCompatibilityTest extends TestCase {
    public void testDiagnosticSummaryFormattingKeepsInheritedShape() {
        assertEquals(
                "\t# of free bytes       = 20.\t(2.5 free bytes/page).\n",
                D_DiagnosticFormatting.summary(
                        "# of free bytes       = ", 20, 2.5d, "free bytes/page"));
        assertEquals(
                "\t# of reserved bytes   = 1.\t(NA reserved bytes/page).\n",
                D_DiagnosticFormatting.summaryOrNotApplicableForTinyRatio(
                        "# of reserved bytes   = ", 1, 0.001d, "reserved bytes/page"));
    }

    public void testRawPageDebugAssertionMessagesStayStable() {
        assertEquals(
                "recordId changed from 10 to 11 but page number did not change 42",
                D_RawPageSanityAssertions.recordIdPageChangeMessage(10, 11, 42));
        assertEquals(
                "restoreMe cannot restore to a different slot. doMe slot:3 undoMe slot: 4 recordId:99",
                D_RawPageSanityAssertions.restoreSlotMismatchMessage(3, 4, 99));
    }
}
