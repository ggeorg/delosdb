/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.derby.impl.store.raw.data;

/** Test-only bridge for per-writer heap insert lanes. */
public final class HeapInsertLaneTestSupport {
    private HeapInsertLaneTestSupport() {
    }

    /** Containers switched to insert lanes in this JVM since boot. */
    public static long laneSwitches() {
        return FileContainer.insertLaneSwitchesForTesting();
    }
}
