/*

   Derby - Class org.apache.derby.iapi.store.access.conglomerate.AccessMethodConglomerateProperties

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.iapi.store.access.conglomerate;

/** Stable internal properties passed from SQL DDL to access-method creation. */
public final class AccessMethodConglomerateProperties {
    /**
     * Semicolon-separated unique-key definitions.
     *
     * <p>Each definition is {@code mode:deferred:column[,column...]}, where
     * mode is {@code S} for strict uniqueness or {@code N} for SQL UNIQUE
     * duplicate-null semantics, deferred is {@code 0} or {@code 1}, and
     * columns are zero-based base-table positions.</p>
     */
    public static final String UNIQUE_CONSTRAINTS =
            "derby.access.uniqueConstraints.v1";

    private AccessMethodConglomerateProperties() {
    }
}
