/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.derbynet.DrdaSessionSchedulerTest

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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.derby.impl.drda.ProtocolTestAdapter.SchedulerProbe;
import org.apache.derbyTesting.junit.BaseTestCase;

/**
 * White-box gate for the inherited DRDA session scheduler semantics.
 */
public final class DrdaSessionSchedulerTest extends BaseTestCase {
    public DrdaSessionSchedulerTest(String name) {
        super(name);
    }

    public void testFifoSelectionAndTimesliceRequeue() throws Exception {
        SchedulerProbe scheduler = new SchedulerProbe();

        scheduler.enqueue(1);
        scheduler.enqueue(2);
        assertIntArrayEquals(
                new int[] {1, 2},
                scheduler.snapshotQueuedSessionIds());

        assertEquals(1, scheduler.nextSessionId(null));
        assertIntArrayEquals(new int[] {2}, scheduler.snapshotQueuedSessionIds());

        assertEquals(2, scheduler.nextSessionId(Integer.valueOf(1)));
        assertIntArrayEquals(new int[] {1}, scheduler.snapshotQueuedSessionIds());

        assertEquals(1, scheduler.nextSessionId(Integer.valueOf(2)));
        assertIntArrayEquals(new int[] {2}, scheduler.drainQueuedSessionIds());
        assertEquals(0, scheduler.waitingSessionCount());
    }

    public void testIdleWorkerWakesForQueuedSession() throws Exception {
        SchedulerProbe scheduler = new SchedulerProbe();
        AtomicInteger selectedSession = new AtomicInteger(-2);
        Thread worker = schedulerWorker(scheduler, selectedSession);

        worker.start();
        awaitIdleWorker(scheduler);
        assertTrue(scheduler.hasIdleThreadForNewSession());

        scheduler.enqueue(10);
        join(worker);

        assertEquals(10, selectedSession.get());
        assertEquals(0, scheduler.idleThreadCount());
        assertFalse(scheduler.hasIdleThreadForNewSession());
    }

    public void testShutdownWakeDoesNotLeakIdleThreadAccounting()
            throws Exception {
        SchedulerProbe scheduler = new SchedulerProbe();
        AtomicInteger selectedSession = new AtomicInteger(-2);
        Thread worker = schedulerWorker(scheduler, selectedSession);

        worker.start();
        awaitIdleWorker(scheduler);

        scheduler.requestShutdown();
        join(worker);

        assertEquals(-1, selectedSession.get());
        assertEquals(0, scheduler.idleThreadCount());
        assertFalse(scheduler.hasIdleThreadForNewSession());
    }

    private static Thread schedulerWorker(
            final SchedulerProbe scheduler,
            final AtomicInteger selectedSession) {
        Thread worker = new Thread(() -> {
            try {
                selectedSession.set(scheduler.nextSessionId(null));
            } catch (Exception e) {
                selectedSession.set(-3);
            }
        }, "drda-scheduler-test-worker");
        worker.setDaemon(true);
        return worker;
    }

    private static void awaitIdleWorker(SchedulerProbe scheduler)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while (System.currentTimeMillis() < deadline) {
            if (scheduler.idleThreadCount() == 1) {
                return;
            }
            Thread.sleep(10L);
        }
        fail("scheduler worker did not become idle");
    }

    private static void join(Thread worker) throws InterruptedException {
        worker.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse("scheduler worker did not stop", worker.isAlive());
    }

    private static void assertIntArrayEquals(int[] expected, int[] actual) {
        assertEquals("array length", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("array element " + i, expected[i], actual[i]);
        }
    }
}
