/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import io.github.ggeorg.delosdb.benchmark.jdbc.DelosBenchmarkOperation;

/** Shared, strict system-property parsing for the JDBC benchmark proof tasks. */
final class JdbcBenchmarkTestProperties {
    private JdbcBenchmarkTestProperties() {
    }

    static String required(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required system property " + key);
        }
        return value;
    }

    static int integer(String key, int fallback) {
        return Integer.parseInt(value(key, Integer.toString(fallback)));
    }

    static long longValue(String key, long fallback) {
        return Long.parseLong(value(key, Long.toString(fallback)));
    }

    static List<Integer> integerList(String key, String fallback) {
        return list(key, fallback, Integer::parseInt);
    }

    static List<DelosBenchmarkOperation> operationList(String key, String fallback) {
        return list(key, fallback, DelosBenchmarkOperation::valueOf);
    }

    private static <T> List<T> list(
            String key,
            String fallback,
            Function<String, T> parser) {
        List<T> values = Arrays.stream(value(key, fallback).split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .map(parser)
                .distinct()
                .toList();
        if (values.isEmpty()) {
            throw new IllegalArgumentException(
                    "System property must contain at least one value: " + key);
        }
        return values;
    }

    private static String value(String key, String fallback) {
        return System.getProperty(key, fallback);
    }
}
