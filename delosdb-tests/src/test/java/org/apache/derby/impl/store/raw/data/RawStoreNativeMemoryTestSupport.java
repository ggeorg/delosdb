/*

   Derby - Class org.apache.derby.impl.store.raw.data.RawStoreNativeMemoryTestSupport

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.raw.data;

import org.apache.derby.iapi.store.types.DelosRawStoreIoMetrics;
import org.apache.derby.iapi.store.types.DelosRawStoreIoSnapshot;

/** Test-only bridge for the package-private Stage 8.5 native-memory proof seam. */
public final class RawStoreNativeMemoryTestSupport {
    private RawStoreNativeMemoryTestSupport() {
    }

    public static void installLimit(String databaseIdentity, long hardLimitBytes) {
        DelosRawStoreNativeMemoryDirectory.installLimitForTesting(
                databaseIdentity, hardLimitBytes);
    }

    public static void clear(String databaseIdentity) {
        DelosRawStoreNativeMemoryDirectory.clearForTesting(databaseIdentity);
    }

    public static ControllerProof exerciseController() {
        DelosRawStoreIoMetrics exactMetrics = new DelosRawStoreIoMetrics();
        exactMetrics.bind("file:/stage85-controller", false);
        DelosRawStoreNativeMemory exact =
                new DelosRawStoreNativeMemory(exactMetrics);
        exact.bind("file:/stage85-controller", false, true, 8192L);

        DelosRawStoreNativeMemory.Lease first = exact.allocate(4096L);
        DelosRawStoreNativeMemory.Lease second = exact.allocate(4096L);
        DelosRawStoreNativeMemory.Lease rejected = exact.allocate(4096L);
        DelosRawStoreIoSnapshot atLimit = exactMetrics.snapshot();

        first.close();
        DelosRawStoreNativeMemory.Lease replacement = exact.allocate(4096L);
        DelosRawStoreIoSnapshot afterReuse = exactMetrics.snapshot();

        second.close();
        replacement.close();
        exact.shutdown();
        exactMetrics.shutdown();
        DelosRawStoreIoSnapshot cleanTerminal = exactMetrics.snapshot();

        DelosRawStoreIoMetrics leakMetrics = new DelosRawStoreIoMetrics();
        leakMetrics.bind("file:/stage85-leak", false);
        DelosRawStoreNativeMemory leak =
                new DelosRawStoreNativeMemory(leakMetrics);
        leak.bind("file:/stage85-leak", false, true, 4096L);
        DelosRawStoreNativeMemory.Lease leaked = leak.allocate(4096L);
        if (leaked == null) {
            throw new AssertionError("expected one native lease for leak proof");
        }
        leak.shutdown();
        leakMetrics.shutdown();
        DelosRawStoreIoSnapshot leakTerminal = leakMetrics.snapshot();

        return new ControllerProof(
                first != null,
                second != null,
                rejected == null,
                replacement != null,
                atLimit,
                afterReuse,
                cleanTerminal,
                leakTerminal);
    }

    public record ControllerProof(
            boolean firstAllocated,
            boolean secondAllocated,
            boolean thirdRejected,
            boolean replacementAllocated,
            DelosRawStoreIoSnapshot atLimit,
            DelosRawStoreIoSnapshot afterReuse,
            DelosRawStoreIoSnapshot cleanTerminal,
            DelosRawStoreIoSnapshot leakTerminal) {
    }
}
