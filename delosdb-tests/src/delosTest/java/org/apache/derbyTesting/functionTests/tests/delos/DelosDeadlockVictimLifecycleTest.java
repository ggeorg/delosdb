/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.DelosDeadlockVictimLifecycleTest

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
import org.apache.derby.impl.services.locks.DeadlockVictimLifecycleTestSupport;
import org.apache.derby.shared.common.reference.SQLState;

/** MariaDB-derived proof for deadlock-victim wake-up lifecycle safety. */
public final class DelosDeadlockVictimLifecycleTest extends TestCase {
    public void testVictimWakeupSurvivesLaterGrantSignal() throws Exception {
        assertVictimWinsWakeupRace(false);
    }

    public void testVictimWakeupOverridesQueuedGrantSignal() throws Exception {
        assertVictimWinsWakeupRace(true);
    }

    private void assertVictimWinsWakeupRace(boolean grantFirst) throws Exception {
        DeadlockVictimLifecycleTestSupport.Proof proof =
                DeadlockVictimLifecycleTestSupport.exercise(grantFirst);

        assertFalse("deadlock victim remained blocked", proof.victimStayedBlocked());
        assertEquals("unexpected victim failure: " + proof.victimFailureType(),
                SQLState.DEADLOCK, proof.victimSqlState());
        assertFalse("deadlock victim acquired the cancelled later lock",
                proof.victimAcquiredLaterLock());
        assertEquals(0, proof.victimWaitCount());

        assertFalse("surviving waiter remained blocked", proof.survivorStayedBlocked());
        assertNull("survivor failed: " + proof.survivorFailureType(),
                proof.survivorFailureType());
        assertTrue("survivor did not acquire the released lock",
                proof.survivorAcquiredLaterLock());
        assertTrue("survivor did not receive its actual queued lock",
                proof.survivorReceivedQueuedLock());
        assertEquals(1, proof.survivorLockCount());
        assertFalse("lock table retained a waiter after deadlock cleanup",
                proof.anyoneBlocked());
    }
}
