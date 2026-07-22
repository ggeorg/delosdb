/*

   Derby - Class org.apache.derby.impl.io.vfmem.DataStoreMemoryBudget

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.io.vfmem;

import java.io.IOException;

/** Database-scoped accounted payload budget for the virtual-file store. */
final class DataStoreMemoryBudget {
    private long limitBytes = Long.MAX_VALUE;
    private long usedBytes;
    private long peakBytes;
    private long rejectedGrowthCount;

    synchronized void configureLimit(long maximumBytes) throws IOException {
        if (maximumBytes <= 0L) {
            throw new IOException(
                    "Memory database limit must be positive: " + maximumBytes);
        }
        if (usedBytes > maximumBytes) {
            rejectedGrowthCount++;
            throw new IOException(
                    "Memory database already uses " + usedBytes
                            + " bytes, above configured limit " + maximumBytes);
        }
        limitBytes = maximumBytes;
    }

    synchronized void reserve(long bytes) throws IOException {
        if (bytes <= 0L) {
            return;
        }
        if (bytes > limitBytes - usedBytes) {
            rejectedGrowthCount++;
            throw new IOException(
                    "Memory database limit exceeded: requested " + bytes
                            + " additional bytes with " + usedBytes
                            + " of " + limitBytes + " bytes already accounted");
        }
        usedBytes += bytes;
        peakBytes = Math.max(peakBytes, usedBytes);
    }

    synchronized void release(long bytes) {
        if (bytes <= 0L) {
            return;
        }
        usedBytes = Math.max(0L, usedBytes - bytes);
    }

    synchronized long limitBytes() {
        return limitBytes;
    }

    synchronized long usedBytes() {
        return usedBytes;
    }

    synchronized long peakBytes() {
        return peakBytes;
    }

    synchronized long rejectedGrowthCount() {
        return rejectedGrowthCount;
    }
}
