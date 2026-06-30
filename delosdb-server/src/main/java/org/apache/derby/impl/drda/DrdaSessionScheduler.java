/*

   Derby - Class org.apache.derby.impl.drda.DrdaSessionScheduler

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

package org.apache.derby.impl.drda;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Serializes access to the DRDA run queue and idle-thread accounting.
 *
 * <p>This class deliberately preserves the old NetworkServerControlImpl
 * scheduling semantics. It is a cleanup seam only: connection threads still use
 * the inherited blocking scheduler and callers still decide when to create new
 * DRDAConnThread instances.</p>
 */
final class DrdaSessionScheduler {
    private final List<Session> runQueue = new ArrayList<Session>();
    private int idleThreads;

    synchronized boolean hasIdleThreadForNewSession() {
        return runQueue.size() < idleThreads;
    }

    synchronized void enqueue(Session session) {
        runQueue.add(session);
        notify();
    }

    synchronized Session nextSession(
            Session currentSession,
            BooleanSupplier shutdownRequested) {
        if (shutdownRequested.getAsBoolean()) {
            return null;
        }

        try {
            if (runQueue.size() == 0) {
                if (currentSession == null) {
                    while (runQueue.size() == 0) {
                        idleThreads++;
                        wait();
                        if (shutdownRequested.getAsBoolean()) {
                            return null;
                        }
                        idleThreads--;
                    }
                } else {
                    return currentSession;
                }
            }

            Session next = runQueue.remove(0);
            if (currentSession != null) {
                enqueue(currentSession);
            }
            return next;
        } catch (InterruptedException e) {
            // Preserve the inherited scheduler accounting: a waiting thread is
            // going away, so it no longer counts as available for assignment.
            idleThreads--;
            return null;
        }
    }

    synchronized List<Session> drainQueuedSessions() {
        List<Session> queued = new ArrayList<Session>(runQueue);
        runQueue.clear();
        return queued;
    }

    synchronized List<Session> snapshotQueuedSessions() {
        return new ArrayList<Session>(runQueue);
    }

    synchronized int waitingSessionCount() {
        return runQueue.size();
    }

    synchronized void wakeAll() {
        notifyAll();
    }
}
