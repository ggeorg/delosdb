/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

/** Independently timed parts of one logical JDBC benchmark operation. */
public enum DelosBenchmarkPhase {
    PREPARE,
    EXECUTE,
    COMMIT,
    ROLLBACK
}
