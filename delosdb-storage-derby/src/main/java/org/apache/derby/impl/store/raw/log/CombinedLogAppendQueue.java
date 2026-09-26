/*

   Derby - Class org.apache.derby.impl.store.raw.log.CombinedLogAppendQueue

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

package org.apache.derby.impl.store.raw.log;

/**
 * Allocation-free pending-request queue for one active WAL append combiner.
 * The queue monitor protects only request linking and combiner ownership; WAL
 * ordering remains authoritative in LogToFile.
 */
final class CombinedLogAppendQueue {
    private CombinedLogAppendRequest head;
    private CombinedLogAppendRequest tail;
    private boolean combinerActive;

    synchronized boolean enqueue(CombinedLogAppendRequest request) {
        request.next = null;
        if (tail == null) {
            head = request;
        } else {
            tail.next = request;
        }
        tail = request;
        if (combinerActive) {
            return false;
        }
        combinerActive = true;
        return true;
    }

    synchronized CombinedLogAppendRequest takeBatchOrDeactivate() {
        CombinedLogAppendRequest batch = head;
        if (batch == null) {
            combinerActive = false;
            return null;
        }
        head = null;
        tail = null;
        return batch;
    }
}
