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
 * <p>The byte array remains the page-cache owner and the durable page format remains unchanged.
 * The memory segment is an alias over that same array; it owns no native memory and requires no
 * arena or explicit close operation.</p>
 */
final class DelosHeapPageBuffer {
    private final byte[] array;
    private final MemorySegment segment;

    private DelosHeapPageBuffer(byte[] array) {
        this.array = Objects.requireNonNull(array, "array");
        this.segment = MemorySegment.ofArray(array);
    }

    static DelosHeapPageBuffer wrap(byte[] array) {
        return new DelosHeapPageBuffer(array);
    }

    byte[] array() {
        return array;
    }

    MemorySegment segment() {
        return segment;
    }

    int length() {
        return array.length;
    }

    boolean wraps(byte[] candidate) {
        return array == candidate;
    }
}
