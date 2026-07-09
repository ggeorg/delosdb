/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.derbynet.DrdaVirtualThreadFairnessAuditTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */
package org.apache.derbyTesting.functionTests.tests.derbynet;

import org.apache.derby.impl.drda.ProtocolTestAdapter.FairnessReport;
import org.apache.derby.impl.drda.ProtocolTestAdapter.VirtualThreadFairnessProbe;
import org.apache.derbyTesting.junit.BaseTestCase;

/**
 * White-box audit for the opt-in DRDA virtual-thread fairness seam.
 */
public final class DrdaVirtualThreadFairnessAuditTest extends BaseTestCase {
    public DrdaVirtualThreadFairnessAuditTest(String name) {
        super(name);
    }

    public void testVirtualThreadModeDispatchesEveryQueuedSessionOnce()
            throws Exception {
        VirtualThreadFairnessProbe probe = new VirtualThreadFairnessProbe();
        int sessionCount = 16;

        FairnessReport report = probe.audit(
                probe.virtualModeName(), sessionCount);

        assertEquals(probe.virtualModeName(), report.modeName());
        assertEquals(sessionCount, report.queuedSessionCount());
        assertEquals(sessionCount, report.startedWorkerCount());
        assertEquals(sessionCount, report.virtualWorkerCount());
        assertEquals(sessionCount, report.selectedSessionCount());
        assertEquals(0, report.duplicateSelectionCount());
        assertEquals(0, report.missingSessionCount());
        assertEquals(0, report.waitingSessionCount());
        assertTrue(report.fair());
        assertContains(report.summaryLine(), "mode=virtual");
        assertContains(report.summaryLine(), "fair=true");
        assertContainsAllSessionIds(report, sessionCount);
    }

    public void testPlatformModeUsesSameFairSchedulerWithoutVirtualWorkers()
            throws Exception {
        VirtualThreadFairnessProbe probe = new VirtualThreadFairnessProbe();
        int sessionCount = 8;

        FairnessReport report = probe.audit(
                probe.platformModeName(), sessionCount);

        assertEquals(probe.platformModeName(), report.modeName());
        assertEquals(sessionCount, report.queuedSessionCount());
        assertEquals(sessionCount, report.startedWorkerCount());
        assertEquals(0, report.virtualWorkerCount());
        assertEquals(sessionCount, report.selectedSessionCount());
        assertEquals(0, report.duplicateSelectionCount());
        assertEquals(0, report.missingSessionCount());
        assertEquals(0, report.waitingSessionCount());
        assertTrue(report.fair());
        assertContains(report.summaryLine(), "mode=platform");
        assertContains(report.summaryLine(), "fair=true");
        assertContainsAllSessionIds(report, sessionCount);
    }

    public void testFairnessAuditRejectsEmptySessionSet() throws Exception {
        VirtualThreadFairnessProbe probe = new VirtualThreadFairnessProbe();

        try {
            probe.audit(probe.virtualModeName(), 0);
            fail("empty DRDA fairness audit should be rejected");
        } catch (IllegalArgumentException expected) {
            assertContains(expected.getMessage(), "sessionCount");
        }
    }

    private static void assertContainsAllSessionIds(
            FairnessReport report,
            int sessionCount) {
        boolean[] seen = new boolean[sessionCount + 1];
        int[] selectedSessionIds = report.selectedSessionIds();
        assertEquals("selected array length", sessionCount,
                selectedSessionIds.length);
        for (int selectedSessionId : selectedSessionIds) {
            assertTrue("selected session id in range: " + selectedSessionId,
                    selectedSessionId >= 1 && selectedSessionId <= sessionCount);
            seen[selectedSessionId] = true;
        }
        for (int i = 1; i <= sessionCount; i++) {
            assertTrue("missing selected session id " + i, seen[i]);
        }
    }

    private static void assertContains(String text, String fragment) {
        assertTrue("expected <" + text + "> to contain <" + fragment + ">",
                text.indexOf(fragment) >= 0);
    }
}
