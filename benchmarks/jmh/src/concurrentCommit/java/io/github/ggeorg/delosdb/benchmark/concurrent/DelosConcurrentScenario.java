/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.concurrent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Configuration and immutable scenario identity for the public-JDBC concurrency lane. */
final class DelosConcurrentScenario {
    private DelosConcurrentScenario() {
    }

    enum Provider {
        HEAP("heap", ""),
        MVCC("mvcc", " using delos_mvcc");

        private final String propertyValue;
        private final String tableSuffix;

        Provider(String propertyValue, String tableSuffix) {
            this.propertyValue = propertyValue;
            this.tableSuffix = tableSuffix;
        }

        String propertyValue() {
            return propertyValue;
        }

        String tableSuffix() {
            return tableSuffix;
        }

        static Provider fromProperty(String value) {
            return switch (value) {
                case "heap" -> HEAP;
                case "mvcc" -> MVCC;
                default -> throw new IllegalArgumentException("unsupported provider: " + value);
            };
        }
    }

    enum Topology {
        SAME_TABLE("same-table"),
        DIFFERENT_TABLES("different-tables"),
        DIFFERENT_DATABASES("different-databases");

        private final String propertyValue;

        Topology(String propertyValue) {
            this.propertyValue = propertyValue;
        }

        String propertyValue() {
            return propertyValue;
        }

        static Topology fromProperty(String value) {
            return switch (value) {
                case "same-table" -> SAME_TABLE;
                case "different-tables" -> DIFFERENT_TABLES;
                case "different-databases" -> DIFFERENT_DATABASES;
                default -> throw new IllegalArgumentException("unsupported topology: " + value);
            };
        }
    }

    enum Operation {
        NONE("none"),
        INSERT("insert"),
        UPDATE("update");

        private final String propertyValue;

        Operation(String propertyValue) {
            this.propertyValue = propertyValue;
        }

        String propertyValue() {
            return propertyValue;
        }

        static Operation fromProperty(String value) {
            return switch (value) {
                case "insert" -> INSERT;
                case "update" -> UPDATE;
                default -> throw new IllegalArgumentException("unsupported writer operation: " + value);
            };
        }
    }

    enum ReaderWorkload {
        NONE("none"),
        PRIMARY("primary"),
        SECONDARY("secondary"),
        RANGE("range"),
        RETAINED_SNAPSHOT("retained-snapshot");

        private final String propertyValue;

        ReaderWorkload(String propertyValue) {
            this.propertyValue = propertyValue;
        }

        String propertyValue() {
            return propertyValue;
        }

        static ReaderWorkload fromProperty(String value) {
            return switch (value) {
                case "primary" -> PRIMARY;
                case "secondary" -> SECONDARY;
                case "range" -> RANGE;
                case "retained-snapshot" -> RETAINED_SNAPSHOT;
                default -> throw new IllegalArgumentException("unsupported reader workload: " + value);
            };
        }
    }

    record Scenario(
            Provider provider,
            Topology topology,
            Operation operation,
            int writers,
            int readers,
            ReaderWorkload readerWorkload,
            int rowsPerTransaction,
            int resourceCapacity) {
        Scenario {
            if (provider == null || topology == null || operation == null || readerWorkload == null) {
                throw new NullPointerException("scenario enum values must not be null");
            }
            if (writers < 0 || readers < 0) {
                throw new IllegalArgumentException("writer and reader counts must not be negative");
            }
            if (resourceCapacity < 1 || resourceCapacity < Math.max(writers, readers)) {
                throw new IllegalArgumentException(
                        "resourceCapacity must cover every configured worker: " + resourceCapacity);
            }
            if (writers == 0 && readers == 0) {
                throw new IllegalArgumentException("a scenario requires at least one worker");
            }
            if (writers == 0 && operation != Operation.NONE) {
                throw new IllegalArgumentException("writerless scenarios must use operation=none");
            }
            if (writers > 0 && operation == Operation.NONE) {
                throw new IllegalArgumentException("writer scenarios require a concrete operation");
            }
            if (readers == 0 && readerWorkload != ReaderWorkload.NONE) {
                throw new IllegalArgumentException("readerless scenarios must use readerWorkload=none");
            }
            if (readers > 0 && readerWorkload == ReaderWorkload.NONE) {
                throw new IllegalArgumentException("reader scenarios require a concrete workload");
            }
            if (writers == 0 && rowsPerTransaction != 0) {
                throw new IllegalArgumentException("writerless scenarios must use rowsPerTransaction=0");
            }
            if (writers > 0 && rowsPerTransaction < 1) {
                throw new IllegalArgumentException("writer scenarios require positive rowsPerTransaction");
            }
        }

        String fileStem() {
            return provider.propertyValue()
                    + '-' + topology.propertyValue()
                    + '-' + operation.propertyValue()
                    + "-w" + writers
                    + '-' + readerWorkload.propertyValue()
                    + "-r" + readers
                    + "-n" + rowsPerTransaction
                    + "-c" + resourceCapacity;
        }
    }

    record Config(
            List<Provider> providers,
            List<Integer> writers,
            List<Integer> readers,
            List<Topology> topologies,
            List<Operation> operations,
            List<ReaderWorkload> readerWorkloads,
            List<Integer> rowsPerTransaction,
            int transactionsPerWriter,
            int warmupTransactionsPerWriter,
            int measurementRounds,
            int readerMeasurementMillis,
            int readsPerReader,
            int warmupReadsPerReader,
            Path outputDirectory,
            Path databaseRoot,
            boolean keepJfr) {
        Config {
            providers = List.copyOf(providers);
            writers = List.copyOf(writers);
            readers = List.copyOf(readers);
            topologies = List.copyOf(topologies);
            operations = List.copyOf(operations);
            readerWorkloads = List.copyOf(readerWorkloads);
            rowsPerTransaction = List.copyOf(rowsPerTransaction);
            if (providers.isEmpty() || writers.isEmpty() || readers.isEmpty()
                    || topologies.isEmpty() || operations.isEmpty()
                    || readerWorkloads.isEmpty() || rowsPerTransaction.isEmpty()) {
                throw new IllegalArgumentException("configuration axes must not be empty");
            }
            if (transactionsPerWriter < 1 || readsPerReader < 1
                    || measurementRounds < 1 || readerMeasurementMillis < 1) {
                throw new IllegalArgumentException(
                        "measurement counts, rounds, and reader duration must be positive");
            }
            if (warmupTransactionsPerWriter < 0 || warmupReadsPerReader < 0) {
                throw new IllegalArgumentException("warmup operation counts must not be negative");
            }
            if (outputDirectory == null || databaseRoot == null) {
                throw new NullPointerException("output and database paths must not be null");
            }
        }

        static Config fromSystemProperties() {
            return new Config(
                    strings("delosdb.concurrentCommit.providers", "heap,mvcc").stream()
                            .map(Provider::fromProperty)
                            .toList(),
                    nonNegativeIntegers("delosdb.concurrentCommit.writers", "1,4"),
                    nonNegativeIntegers("delosdb.concurrentCommit.readers", "0,4"),
                    strings("delosdb.concurrentCommit.topologies",
                            "same-table,different-tables,different-databases").stream()
                            .map(Topology::fromProperty)
                            .toList(),
                    strings("delosdb.concurrentCommit.operations", "insert,update").stream()
                            .map(Operation::fromProperty)
                            .toList(),
                    strings("delosdb.concurrentCommit.readerWorkloads", "primary").stream()
                            .map(ReaderWorkload::fromProperty)
                            .toList(),
                    positiveIntegers("delosdb.concurrentCommit.rowsPerTransaction", "1"),
                    positiveInteger("delosdb.concurrentCommit.transactionsPerWriter", 20),
                    nonNegativeInteger("delosdb.concurrentCommit.warmupTransactionsPerWriter", 20),
                    positiveInteger("delosdb.concurrentCommit.measurementRounds", 5),
                    positiveInteger("delosdb.concurrentCommit.readerMeasurementMillis", 250),
                    positiveInteger("delosdb.concurrentCommit.readsPerReader", 200),
                    nonNegativeInteger("delosdb.concurrentCommit.warmupReadsPerReader", 200),
                    Path.of(System.getProperty(
                            "delosdb.concurrentCommit.outputDirectory",
                            "build/reports/concurrent-commit")),
                    Path.of(System.getProperty(
                            "delosdb.concurrentCommit.databaseRoot",
                            "build/concurrent-commit-databases")),
                    booleanProperty("delosdb.concurrentCommit.keepJfr", true));
        }

        List<Scenario> scenarios() {
            Set<Scenario> scenarios = new LinkedHashSet<>();
            int resourceCapacity = Math.max(
                    writers.stream().mapToInt(Integer::intValue).max().orElse(0),
                    readers.stream().mapToInt(Integer::intValue).max().orElse(0));
            resourceCapacity = Math.max(1, resourceCapacity);
            for (Provider provider : providers) {
                for (Topology topology : topologies) {
                    for (Operation configuredOperation : operations) {
                        for (int configuredRows : rowsPerTransaction) {
                            for (int writerCount : writers) {
                                for (ReaderWorkload configuredReaderWorkload : readerWorkloads) {
                                    for (int readerCount : readers) {
                                        Operation operation = writerCount == 0
                                                ? Operation.NONE
                                                : configuredOperation;
                                        ReaderWorkload readerWorkload = readerCount == 0
                                                ? ReaderWorkload.NONE
                                                : configuredReaderWorkload;
                                        int rows = writerCount == 0 ? 0 : configuredRows;
                                        if (writerCount != 0 || readerCount != 0) {
                                            scenarios.add(new Scenario(
                                                    provider,
                                                    topology,
                                                    operation,
                                                    writerCount,
                                                    readerCount,
                                                    readerWorkload,
                                                    rows,
                                                    resourceCapacity));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (scenarios.isEmpty()) {
                throw new IllegalArgumentException("configuration produced no runnable scenarios");
            }
            return List.copyOf(scenarios);
        }

        private static int positiveInteger(String name, int defaultValue) {
            int value = parseInteger(name, System.getProperty(name, Integer.toString(defaultValue)));
            if (value < 1) {
                throw new IllegalArgumentException(name + " must be positive: " + value);
            }
            return value;
        }

        private static int nonNegativeInteger(String name, int defaultValue) {
            int value = parseInteger(name, System.getProperty(name, Integer.toString(defaultValue)));
            if (value < 0) {
                throw new IllegalArgumentException(name + " must not be negative: " + value);
            }
            return value;
        }

        private static List<Integer> positiveIntegers(String name, String defaults) {
            List<Integer> values = integerValues(name, defaults);
            for (int value : values) {
                if (value < 1) {
                    throw new IllegalArgumentException(name + " must contain positive values: " + value);
                }
            }
            return values;
        }

        private static List<Integer> nonNegativeIntegers(String name, String defaults) {
            List<Integer> values = integerValues(name, defaults);
            for (int value : values) {
                if (value < 0) {
                    throw new IllegalArgumentException(name + " must not contain negative values: " + value);
                }
            }
            return values;
        }

        private static List<Integer> integerValues(String name, String defaults) {
            List<String> rawValues = strings(name, defaults);
            List<Integer> values = new ArrayList<>(rawValues.size());
            for (String rawValue : rawValues) {
                values.add(parseInteger(name, rawValue));
            }
            return List.copyOf(values);
        }

        private static boolean booleanProperty(String name, boolean defaultValue) {
            String value = System.getProperty(name, Boolean.toString(defaultValue));
            if ("true".equalsIgnoreCase(value)) {
                return true;
            }
            if ("false".equalsIgnoreCase(value)) {
                return false;
            }
            throw new IllegalArgumentException(name + " must be true or false: " + value);
        }

        private static int parseInteger(String name, String value) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(name + " must contain integers: " + value, failure);
            }
        }

        private static List<String> strings(String name, String defaults) {
            String raw = System.getProperty(name, defaults);
            Set<String> values = new LinkedHashSet<>();
            for (String value : raw.split(",")) {
                String normalized = value.trim().toLowerCase(Locale.ROOT);
                if (!normalized.isEmpty()) {
                    values.add(normalized);
                }
            }
            if (values.isEmpty()) {
                throw new IllegalArgumentException(name + " must contain at least one value");
            }
            return List.copyOf(values);
        }
    }
}
