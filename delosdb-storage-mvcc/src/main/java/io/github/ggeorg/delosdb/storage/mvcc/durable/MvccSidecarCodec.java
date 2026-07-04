/*

   DelosDB - Class io.github.ggeorg.delosdb.storage.mvcc.durable.MvccSidecarCodec

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

package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Binary MVCC sidecar codec for big-endian payloads with a checksum trailer. */
final class MvccSidecarCodec {
    static final int CHECKSUM_BYTES = Integer.BYTES;

    private MvccSidecarCodec() {
    }

    static Optional<ByteBuffer> readPayloadIfExists(
            Path path,
            int minimumPayloadBytes,
            String description) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(description, "description");
        if (minimumPayloadBytes < 0) {
            throw new IllegalArgumentException("minimumPayloadBytes must not be negative: " + minimumPayloadBytes);
        }
        if (!Files.exists(path)) {
            return Optional.empty();
        }

        int minimumTotalBytes = Math.addExact(minimumPayloadBytes, CHECKSUM_BYTES);
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length < minimumTotalBytes) {
            throw new IllegalStateException(description + " is truncated: " + path);
        }
        int payloadLength = bytes.length - CHECKSUM_BYTES;
        int storedChecksum = ByteBuffer.wrap(bytes, payloadLength, CHECKSUM_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .getInt();
        int actualChecksum = MvccSidecarFiles.checksum(bytes, 0, payloadLength);
        if (storedChecksum != actualChecksum) {
            throw new IllegalStateException(description + " checksum mismatch: " + path);
        }
        return Optional.of(ByteBuffer.wrap(bytes, 0, payloadLength).order(ByteOrder.BIG_ENDIAN));
    }

    static ByteBuffer allocatePayload(int payloadLength) {
        if (payloadLength < 0) {
            throw new IllegalArgumentException("payloadLength must not be negative: " + payloadLength);
        }
        return ByteBuffer.allocate(Math.addExact(payloadLength, CHECKSUM_BYTES)).order(ByteOrder.BIG_ENDIAN);
    }

    static void rewritePayload(Path path, ByteBuffer payloadBuffer, int payloadLength) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(payloadBuffer, "payloadBuffer");
        if (payloadLength < 0) {
            throw new IllegalArgumentException("payloadLength must not be negative: " + payloadLength);
        }
        if (!payloadBuffer.hasArray()) {
            throw new IllegalArgumentException("payloadBuffer must be array-backed");
        }
        int expectedCapacity = Math.addExact(payloadLength, CHECKSUM_BYTES);
        if (payloadBuffer.arrayOffset() != 0) {
            throw new IllegalArgumentException("payloadBuffer must have zero array offset");
        }
        if (payloadBuffer.capacity() != expectedCapacity) {
            throw new IllegalArgumentException("payloadBuffer capacity " + payloadBuffer.capacity()
                    + " does not match payloadLength " + payloadLength + " plus checksum trailer");
        }
        if (payloadBuffer.position() != payloadLength) {
            throw new IllegalStateException("payloadBuffer position " + payloadBuffer.position()
                    + " does not match payloadLength " + payloadLength);
        }
        byte[] bytes = payloadBuffer.array();
        payloadBuffer.putInt(MvccSidecarFiles.checksum(bytes, 0, payloadLength));
        MvccSidecarFiles.rewriteAtomically(path, bytes);
    }
}
