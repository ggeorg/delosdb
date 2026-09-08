/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.derby.impl.store.raw.data;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.CRC32;

import org.apache.derby.iapi.services.io.ArrayInputStream;

/** Version-one literal/copy recipe. Copies refer only to an archived record,
 * never to a different WAL record. No compression of unrelated row values.
 * Inputs are bounded single-page record images, not arbitrary byte streams.
 */
final class ArchivedBeforeImage {
    static final int MAX_IMAGE_BYTES = 262144;
    private static final int MIN_COPY = 16;
    private static final int TABLE_SIZE = 4096;

    private ArchivedBeforeImage() {
    }

    static byte[] encode(byte[] image, byte[] archive) throws IOException {
        checkLength(image.length);
        checkLength(archive.length);
        int[] dictionary = new int[TABLE_SIZE];
        Arrays.fill(dictionary, -1);
        for (int offset = 0; offset + MIN_COPY <= archive.length; offset++) {
            int bucket = hash(archive, offset);
            // Prefer the earliest occurrence: repetitive values then permit
            // one long COPY rather than many copies of their final few bytes.
            if (dictionary[bucket] == -1) {
                dictionary[bucket] = offset;
            }
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(image.length);
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt(image.length);
        out.writeInt(checksum(image));
        int literal = 0;
        int position = 0;
        while (position + MIN_COPY <= image.length) {
            int candidate = dictionary[hash(image, position)];
            int length = 0;
            if (candidate >= 0) {
                int limit = Math.min(image.length - position, archive.length - candidate);
                while (length < limit && image[position + length] == archive[candidate + length]) {
                    length++;
                }
            }
            if (length < MIN_COPY) {
                position++;
                continue;
            }
            literal(out, image, literal, position);
            out.writeInt(-length);
            out.writeInt(candidate);
            position += length;
            literal = position;
        }
        literal(out, image, literal, image.length);
        out.flush();
        return buffer.toByteArray();
    }

    static byte[] decode(byte[] recipe, byte[] archive) throws IOException {
        checkLength(archive.length);
        if (recipe.length < 8 || recipe.length > MAX_IMAGE_BYTES * 2) {
            throw new IOException("Invalid archived before-image recipe size");
        }
        ArrayInputStream in = new ArrayInputStream(recipe);
        int size = in.readInt();
        checkLength(size);
        int expectedChecksum = in.readInt();
        byte[] image = new byte[size];
        int position = 0;
        while (position < size) {
            int token = in.readInt();
            if (token == 0 || token == Integer.MIN_VALUE) {
                throw new IOException("Invalid archived before-image token");
            }
            int length = Math.abs(token);
            if (length > size - position) {
                throw new IOException("Archived before-image token exceeds destination");
            }
            if (token > 0) {
                in.readFully(image, position, length);
            } else {
                int offset = in.readInt();
                if (offset < 0 || length > archive.length || offset > archive.length - length) {
                    throw new IOException("Archived before-image copy exceeds source");
                }
                System.arraycopy(archive, offset, image, position, length);
            }
            position += length;
        }
        if (in.available() != 0 || checksum(image) != expectedChecksum) {
            throw new IOException("Archived before image does not match its recorded checksum");
        }
        return image;
    }

    static void checkLength(int length) throws IOException {
        if (length <= 0 || length > MAX_IMAGE_BYTES) {
            throw new IOException("Invalid archived record image length: " + length);
        }
    }

    private static int checksum(byte[] image) {
        CRC32 crc = new CRC32();
        crc.update(image);
        return (int) crc.getValue();
    }

    private static int hash(byte[] bytes, int offset) {
        int word = ((bytes[offset] & 255) << 24) | ((bytes[offset + 1] & 255) << 16)
                | ((bytes[offset + 2] & 255) << 8) | (bytes[offset + 3] & 255);
        return (word * 0x9E3779B9) >>> 20;
    }

    private static void literal(DataOutputStream out, byte[] image, int start, int end)
            throws IOException {
        if (end > start) {
            out.writeInt(end - start);
            out.write(image, start, end - start);
        }
    }
}
