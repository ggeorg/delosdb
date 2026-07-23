/*

   Derby - Class org.apache.derby.iapi.store.types.DelosDatabaseMemorySnapshot

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derby.iapi.store.types;

import java.util.Objects;

/** Immutable database-scoped observation of inherited memory-storage accounting. */
public record DelosDatabaseMemorySnapshot(
        int schemaVersion,
        String providerId,
        String databaseIdentity,
        boolean runtimeActive,
        boolean memoryDatabase,
        long limitBytes,
        long usedBytes,
        long peakBytes,
        long rejectedGrowthCount,
        int entryCount) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public DelosDatabaseMemorySnapshot {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        providerId = DelosStorageProviderIds.normalize(providerId);
        databaseIdentity = requireNonBlank(databaseIdentity, "databaseIdentity");
        if (limitBytes < 0L || usedBytes < 0L || peakBytes < 0L
                || rejectedGrowthCount < 0L || entryCount < 0) {
            throw new IllegalArgumentException("memory snapshot values must be non-negative");
        }
        if (usedBytes > peakBytes || (memoryDatabase && peakBytes > limitBytes)) {
            throw new IllegalArgumentException("memory accounting values are inconsistent");
        }
        if (!memoryDatabase && (limitBytes != 0L || usedBytes != 0L || peakBytes != 0L
                || rejectedGrowthCount != 0L || entryCount != 0)) {
            throw new IllegalArgumentException(
                    "non-memory databases cannot report memory-storage accounting");
        }
    }

    public static DelosDatabaseMemorySnapshot unavailable(String providerId) {
        return new DelosDatabaseMemorySnapshot(
                CURRENT_SCHEMA_VERSION,
                providerId,
                "<unbound>",
                false,
                false,
                0L,
                0L,
                0L,
                0L,
                0);
    }

    private static String requireNonBlank(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
