/*

   Derby - Class org.apache.derby.impl.store.raw.log.ConcurrentLogAppendPublicationTest

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

/** Source-level proof for contiguous publication of out-of-order WAL copies. */
public final class ConcurrentLogAppendPublicationTest {
    private ConcurrentLogAppendPublicationTest() {
    }

    public static void main(String[] args) {
        publishesOnlyContiguousCompletedRanges();
        resetRequiresQuiescence();
    }

    static void publishesOnlyContiguousCompletedRanges() {
        ConcurrentLogAppendPublication publication = new ConcurrentLogAppendPublication();
        byte[] target = new byte[256];
        ConcurrentLogAppendReservation first = reservation(target, 0, 64, 7L, 100L, 164L);
        ConcurrentLogAppendReservation second = reservation(target, 64, 64, 7L, 164L, 228L);
        ConcurrentLogAppendReservation third = reservation(target, 128, 64, 7L, 228L, 292L);

        publication.reserve(first);
        publication.reserve(second);
        publication.reserve(third);
        publication.complete(second);
        publication.complete(third);

        assertEquals(100L, publication.publishedEndPosition(),
                "later completions must not cross the first publication hole");
        assertEquals(1, publication.outstanding(), "only the first reservation remains pending");

        publication.complete(first);
        assertEquals(292L, publication.publishedEndPosition(),
                "closing the first hole must publish the whole completed suffix");
        assertEquals(0, publication.outstanding(), "all reservations must be published");
    }

    static void resetRequiresQuiescence() {
        ConcurrentLogAppendPublication publication = new ConcurrentLogAppendPublication();
        byte[] target = new byte[64];
        ConcurrentLogAppendReservation reservation = reservation(target, 0, 32, 9L, 24L, 56L);
        publication.reserve(reservation);
        try {
            publication.resetPublished(9L, 56L);
            throw new AssertionError("reset unexpectedly accepted a pending reservation");
        } catch (IllegalStateException expected) {
            // expected
        }
        publication.complete(reservation);
        publication.resetPublished(10L, 24L);
        assertEquals(10L, publication.publishedLogFileNumber(), "reset log file");
        assertEquals(24L, publication.publishedEndPosition(), "reset end position");
    }

    private static ConcurrentLogAppendReservation reservation(
            byte[] target, int offset, int length, long file, long base, long end) {
        return new ConcurrentLogAppendReservation(
                target, offset, length, 0L, file, base, end);
    }

    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }
}
