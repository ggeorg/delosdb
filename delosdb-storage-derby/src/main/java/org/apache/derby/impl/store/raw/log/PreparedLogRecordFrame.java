/*

   Derby - Class org.apache.derby.impl.store.raw.log.PreparedLogRecordFrame

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
 * Builds the physical bytes of one ordinary WAL record outside the shared
 * {@link LogToFile} append monitor. The eight-byte instant slot is patched
 * only after LogToFile reserves the authoritative log position.
 */
final class PreparedLogRecordFrame {

    static final int FIXED_OVERHEAD = 16;
    private static final int INSTANT_OFFSET = 4;
    private static final int HEADER_SIZE = 12;

    private PreparedLogRecordFrame() {
    }

    static byte[] ensureCapacity(byte[] frame, int logicalLength) {
        int required = frameLength(logicalLength);
        if (frame != null && frame.length >= required) {
            return frame;
        }
        int capacity = frame == null ? 256 : frame.length;
        while (capacity < required) {
            capacity = Math.max(required, capacity << 1);
        }
        return new byte[capacity];
    }

    static int prepare(byte[] frame,
                       int logicalLength,
                       byte[] data,
                       int dataOffset,
                       byte[] optionalData,
                       int optionalDataOffset,
                       int optionalDataLength) {
        int dataLength = logicalLength - optionalDataLength;
        int required = frameLength(logicalLength);
        if (logicalLength <= 0 || dataLength < 0 || frame.length < required) {
            throw new IllegalArgumentException("invalid prepared log record length");
        }

        writeInt(logicalLength, frame, 0);
        writeLong(0L, frame, INSTANT_OFFSET);
        System.arraycopy(data, dataOffset, frame, HEADER_SIZE, dataLength);
        if (optionalDataLength != 0) {
            System.arraycopy(optionalData, optionalDataOffset,
                    frame, HEADER_SIZE + dataLength, optionalDataLength);
        }
        writeInt(logicalLength, frame, HEADER_SIZE + logicalLength);
        return required;
    }

    static void patchInstant(byte[] frame, int offset, long instant) {
        writeLong(instant, frame, offset + INSTANT_OFFSET);
    }

    static int frameLength(int logicalLength) {
        return logicalLength + FIXED_OVERHEAD;
    }

    private static void writeInt(int value, byte[] target, int offset) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static void writeLong(long value, byte[] target, int offset) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            target[offset++] = (byte) (value >>> shift);
        }
    }
}
