/*

   Derby - Class org.apache.derby.impl.store.raw.data.DelosRawStoreNativeMemoryDirectory

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.raw.data;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Test-only planning directory for the package-private native-memory proof seam. */
final class DelosRawStoreNativeMemoryDirectory {
    private static final int MAX_PLANNED_DATABASES = 64;
    private static final Map<String, Long> PLANNED_LIMITS =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > MAX_PLANNED_DATABASES;
                }
            };

    private DelosRawStoreNativeMemoryDirectory() {
    }

    static synchronized void installLimitForTesting(
            String databaseIdentity,
            long hardLimitBytes) {
        String identity = requireIdentity(databaseIdentity);
        if (hardLimitBytes <= 0L) {
            throw new IllegalArgumentException(
                    "Native-memory hard limit must be positive");
        }
        PLANNED_LIMITS.put(identity, hardLimitBytes);
    }

    static synchronized void clearForTesting(String databaseIdentity) {
        PLANNED_LIMITS.remove(requireIdentity(databaseIdentity));
    }

    static synchronized long consumeLimit(String databaseIdentity) {
        Long limit = PLANNED_LIMITS.remove(requireIdentity(databaseIdentity));
        return limit == null ? DelosRawStoreNativeMemory.DEFAULT_LIMIT_BYTES : limit;
    }

    private static String requireIdentity(String value) {
        String normalized = Objects.requireNonNull(value, "databaseIdentity").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("databaseIdentity must not be blank");
        }
        return normalized;
    }
}
