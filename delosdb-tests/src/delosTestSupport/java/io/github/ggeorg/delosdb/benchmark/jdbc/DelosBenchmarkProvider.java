/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

/** Storage providers supported by the provider-neutral JDBC benchmark surface. */
public enum DelosBenchmarkProvider {
    HEAP("heap", ""),
    MVCC("mvcc", " using delos_mvcc");

    private final String id;
    private final String createTableSuffix;

    DelosBenchmarkProvider(String id, String createTableSuffix) {
        this.id = id;
        this.createTableSuffix = createTableSuffix;
    }

    public String id() {
        return id;
    }

    public String createTableSuffix() {
        return createTableSuffix;
    }
}
