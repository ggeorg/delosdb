/*

   Derby - Class org.apache.derby.impl.store.raw.log.ConcurrentLogAppendReservation

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

/** One disjoint in-memory WAL range reserved under {@link LogToFile}. */
final class ConcurrentLogAppendReservation {
    final byte[] target;
    final int targetOffset;
    final int frameLength;
    final long instant;
    final long logFileNumber;
    final long publicationBasePosition;
    final long endPosition;
    volatile boolean complete;
    ConcurrentLogAppendReservation next;

    ConcurrentLogAppendReservation(
            byte[] target,
            int targetOffset,
            int frameLength,
            long instant,
            long logFileNumber,
            long publicationBasePosition,
            long endPosition) {
        this.target = target;
        this.targetOffset = targetOffset;
        this.frameLength = frameLength;
        this.instant = instant;
        this.logFileNumber = logFileNumber;
        this.publicationBasePosition = publicationBasePosition;
        this.endPosition = endPosition;
    }
}
