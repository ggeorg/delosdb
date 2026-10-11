/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

import org.apache.derby.impl.store.raw.log.LogBufferSizeTestSupport;

/**
 * The default log buffer is 1/64 of the maximum heap, between Derby's 32K and
 * 1M: 1M for ordinary heaps, smaller for small embedded heaps.
 */
public final class LogBufferSizeDefaultTest extends TestCase {

    public void testDefaultFollowsHeap() {
        long expected = Math.max(32768,
                Math.min(1024 * 1024, Runtime.getRuntime().maxMemory() / 64));
        assertEquals(expected, LogBufferSizeTestSupport.defaultLogBufferSize());
    }

    public void testSmallAndOrdinaryHeaps() throws Exception {
        assertEquals(16 * 1024 * 1024 / 64, defaultFor("-Xmx16m"), 64 * 1024);
        assertEquals(1024 * 1024, defaultFor("-Xmx256m"));
    }

    /** The default in a JVM with the given heap option. */
    private static int defaultFor(String heap) throws Exception {
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                heap, "-cp", System.getProperty("java.class.path"),
                LogBufferSizeTestSupport.class.getName())
                .redirectErrorStream(true)
                .start();
        assertTrue("process did not finish", process.waitFor(60, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int at = output.indexOf("defaultLogBufferSize=");
        assertTrue(output, at >= 0);
        return Integer.parseInt(output.substring(at + "defaultLogBufferSize=".length()).trim());
    }
}
