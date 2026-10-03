/*

   Derby - Class org.apache.derby.impl.store.raw.log.ConcurrentLogAppendPublication

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
 * Tracks the contiguous publication frontier for concurrently copied WAL
 * ranges. Synchronization is deliberately supplied by the owning LogToFile so
 * reservation ordering remains under the existing RawStore WAL authority.
 */
final class ConcurrentLogAppendPublication {
    private ConcurrentLogAppendReservation head;
    private ConcurrentLogAppendReservation tail;
    private int outstanding;
    private long publishedLogFileNumber = -1L;
    private long publishedEndPosition = -1L;

    void reserve(ConcurrentLogAppendReservation reservation) {
        if (head == null) {
            if (publishedLogFileNumber != reservation.logFileNumber
                    || publishedEndPosition < reservation.publicationBasePosition) {
                publishedLogFileNumber = reservation.logFileNumber;
                publishedEndPosition = reservation.publicationBasePosition;
            }
            head = reservation;
        } else {
            tail.next = reservation;
        }
        tail = reservation;
        outstanding++;
    }

    void complete(ConcurrentLogAppendReservation reservation) {
        if (reservation.complete) {
            throw new IllegalStateException("concurrent WAL reservation already completed");
        }
        reservation.complete = true;
        outstanding--;
        while (head != null && head.complete) {
            publishedLogFileNumber = head.logFileNumber;
            publishedEndPosition = head.endPosition;
            head = head.next;
        }
        if (head == null) {
            tail = null;
        }
    }

    void resetPublished(long logFileNumber, long endPosition) {
        if (outstanding != 0) {
            throw new IllegalStateException("cannot reset WAL publication with pending reservations");
        }
        head = null;
        tail = null;
        publishedLogFileNumber = logFileNumber;
        publishedEndPosition = endPosition;
    }

    int outstanding() {
        return outstanding;
    }

    long publishedLogFileNumber() {
        return publishedLogFileNumber;
    }

    long publishedEndPosition() {
        return publishedEndPosition;
    }
}
