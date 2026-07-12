/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

/** Prepared-statement lifecycle used by one benchmark measurement. */
public enum DelosBenchmarkStatementMode {
    /** Prepare and close a fresh JDBC statement for every logical operation. */
    FRESH_PER_OPERATION(false),

    /** Prepare once and reuse the same JDBC statement across operations and transactions. */
    REUSED_ACROSS_TRANSACTIONS(true);

    private final boolean reusesStatement;

    DelosBenchmarkStatementMode(boolean reusesStatement) {
        this.reusesStatement = reusesStatement;
    }

    public boolean reusesStatement() {
        return reusesStatement;
    }

    public boolean measuresPreparePerOperation() {
        return !reusesStatement;
    }
}
