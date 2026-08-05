/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

/** Position of a measured sample inside one benchmark transaction. */
public enum DelosBenchmarkSampleScope {
    FIRST_OPERATION,
    REPEATED_OPERATIONS,
    TRANSACTION_END
}
