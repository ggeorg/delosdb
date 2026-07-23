/*

   Derby - Class org.apache.derby.impl.store.raw.data.DelosHeapPageBuffer

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.raw.data;

import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * Stable JDK 25 view over one inherited RawStore heap page array.
 *
 * <p>The byte array remains the page-cache and page-codec owner. When the database-scoped Stage 8.5
 * proof is armed, the wrapper may also own one bounded native physical-I/O mirror. Reads copy the
 * completed native image into the inherited array and writes copy the inherited array into the
 * native mirror immediately before the positional transfer. Heap fallback remains zero-copy.</p>
 */
final class DelosHeapPageBuffer implements AutoCloseable {
    private final byte[] array;
    private final MemorySegment heapSegment;
    private final DelosRawStoreNativeMemory.Lease nativeLease;

    private DelosHeapPageBuffer(
            byte[] array,
            DelosRawStoreNativeMemory nativeMemory) {
        this.array = Objects.requireNonNull(array, "array");
        this.heapSegment = MemorySegment.ofArray(array);
        this.nativeLease = nativeMemory == null
                ? null
                : nativeMemory.allocate(array.length);
    }

    static DelosHeapPageBuffer wrap(byte[] array) {
        return new DelosHeapPageBuffer(array, null);
    }

    static DelosHeapPageBuffer wrap(
            byte[] array,
            DelosRawStoreNativeMemory nativeMemory) {
        return new DelosHeapPageBuffer(
                array,
                Objects.requireNonNull(nativeMemory, "nativeMemory"));
    }

    byte[] array() {
        return array;
    }

    MemorySegment segment() {
        return heapSegment;
    }

    MemorySegment readSegment() {
        return nativeLease == null ? heapSegment : nativeLease.segment();
    }

    void completeRead() {
        if (nativeLease != null) {
            MemorySegment.copy(
                    nativeLease.segment(), 0L,
                    heapSegment, 0L,
                    array.length);
        }
    }

    MemorySegment writeSegment() {
        if (nativeLease == null) {
            return heapSegment;
        }
        MemorySegment.copy(
                heapSegment, 0L,
                nativeLease.segment(), 0L,
                array.length);
        return nativeLease.segment();
    }

    boolean nativeIo() {
        return nativeLease != null;
    }

    int length() {
        return array.length;
    }

    boolean wraps(byte[] candidate) {
        return array == candidate;
    }

    @Override
    public void close() {
        if (nativeLease != null) {
            nativeLease.close();
        }
    }
}
