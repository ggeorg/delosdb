/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.derby.impl.store.raw.log;

/** Test-only bridge to the default log buffer size. */
public final class LogBufferSizeTestSupport {
    private LogBufferSizeTestSupport() {
    }

    /** Default log buffer size for this JVM's maximum heap. */
    public static int defaultLogBufferSize() {
        return LogToFile.defaultLogBufferSize();
    }

    /** Prints the default for the spawning test. */
    public static void main(String[] args) {
        System.out.println("defaultLogBufferSize=" + defaultLogBufferSize());
    }
}
