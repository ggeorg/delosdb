/*

   Derby - Interface org.apache.derby.io.DatabaseMemoryStorage

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derby.io;

import java.io.IOException;

/**
 * Optional database-scoped accounting contract for in-memory storage factories.
 *
 * <p>The reported byte count is the storage implementation's accounted payload
 * capacity, not a whole-JVM heap measurement. Implementations must reject growth
 * before the configured limit is exceeded and must keep accounting isolated per
 * database namespace.</p>
 */
public interface DatabaseMemoryStorage {
    String MEMORY_LIMIT_PROPERTY = "delosdb.memory.maxBytes";
    long DEFAULT_MEMORY_LIMIT_BYTES = 256L * 1024L * 1024L;

    /** Stable identity of this memory-database namespace. */
    String memoryDatabaseIdentity();

    /** Configure the maximum accounted payload capacity for this database. */
    void configureMemoryLimit(long maximumBytes) throws IOException;

    /** Current configured maximum accounted payload capacity. */
    long memoryLimitBytes();

    /** Current accounted payload capacity. */
    long memoryUsedBytes();

    /** Highest accounted payload capacity observed since database creation. */
    long memoryPeakBytes();

    /** Number of growth requests rejected by the configured limit. */
    long memoryRejectedGrowthCount();

    /** Current number of virtual filesystem entries in this database. */
    int memoryEntryCount();
}
