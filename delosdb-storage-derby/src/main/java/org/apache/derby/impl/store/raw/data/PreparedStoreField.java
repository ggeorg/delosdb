/*

   Derby - Class org.apache.derby.impl.store.raw.data.PreparedStoreField

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements. See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.raw.data;

/** Immutable writer-local field payload prepared before target-page latching. */
final class PreparedStoreField {
    private final Object original;
    private final int status;
    private final byte[] data;

    PreparedStoreField(Object original, int status, byte[] data) {
        this.original = original;
        this.status = status;
        this.data = data;
    }

    Object original() {
        return original;
    }

    int status() {
        return status;
    }

    byte[] data() {
        return data;
    }
}
