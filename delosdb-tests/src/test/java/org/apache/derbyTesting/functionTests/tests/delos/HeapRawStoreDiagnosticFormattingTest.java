/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.HeapRawStoreDiagnosticFormattingTest

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

/**
 * Checks that diagnostic-only heap/raw-store formatting helper extraction keeps
 * inherited Derby text output stable.
 */
public final class HeapRawStoreDiagnosticFormattingTest extends TestCase
{
    public void testSummaryKeepsInheritedRatioShape()
    {
        assertEquals(
            "\t# of free bytes       = 20.\t(2.5 free bytes/page).\n",
            D_DiagnosticFormatting.summary(
                "# of free bytes       = ", 20, 2.5d, "free bytes/page"));
    }

    public void testHeapSummaryKeepsTinyRatioAsNotApplicable()
    {
        assertEquals(
            "\t# of reserved bytes   = 1.\t(NA reserved bytes/page).\n",
            D_DiagnosticFormatting.summaryOrNotApplicableForTinyRatio(
                "# of reserved bytes   = ", 1, 0.001d, "reserved bytes/page"));
    }
}
