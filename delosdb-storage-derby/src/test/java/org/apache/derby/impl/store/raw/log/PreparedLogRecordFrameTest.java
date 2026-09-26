/*

   Derby - Class org.apache.derby.impl.store.raw.log.PreparedLogRecordFrameTest

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

import java.util.Arrays;

public final class PreparedLogRecordFrameTest {

    private PreparedLogRecordFrameTest() {
    }

    public static void main(String[] args) {
        byte[] data = {10, 11, 12, 13, 14};
        byte[] optional = {21, 22, 23};
        byte[] frame = PreparedLogRecordFrame.ensureCapacity(null, 8);
        int length = PreparedLogRecordFrame.prepare(
                frame, 8, data, 0, optional, 0, optional.length);
        PreparedLogRecordFrame.patchInstant(frame, 0, 0x0102030405060708L);

        byte[] expected = {
                0, 0, 0, 8,
                1, 2, 3, 4, 5, 6, 7, 8,
                10, 11, 12, 13, 14, 21, 22, 23,
                0, 0, 0, 8
        };
        if (length != expected.length
                || !Arrays.equals(expected, Arrays.copyOf(frame, length))) {
            throw new AssertionError("prepared physical WAL frame mismatch");
        }
        if (PreparedLogRecordFrame.ensureCapacity(frame, 4) != frame) {
            throw new AssertionError("prepared frame buffer was not reused");
        }
        System.out.println("PREPARED_LOG_RECORD_FRAME_OK");
    }
}
