/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.Objects;

/** Shared file and JDBC cleanup primitives for the opt-in benchmark drivers. */
final class DelosBenchmarkSupport {
    private DelosBenchmarkSupport() {
    }

    @FunctionalInterface
    interface ConnectionWork<T> {
        T execute(Connection connection) throws Exception;
    }

    static void prepareOutput(Path databaseRoot, Path reportDirectory) throws IOException {
        Files.createDirectories(reportDirectory);
        deleteRecursively(databaseRoot);
    }

    static void writeUtf8(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    static <T> T withFreshEmbeddedDatabase(Path database, ConnectionWork<T> work)
            throws Exception {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(work, "work");
        deleteRecursively(database);

        Connection connection = null;
        T result = null;
        Throwable failure = null;
        try {
            connection = DriverManager.getConnection(
                    "jdbc:derby:" + database + ";create=true");
            result = work.execute(connection);
        } catch (Throwable operationFailure) {
            failure = operationFailure;
        }

        if (connection != null) {
            failure = rollbackOpenConnection(connection, failure);
            failure = closeConnection(connection, failure);
        }
        try {
            deleteRecursively(database);
        } catch (Throwable cleanupFailure) {
            failure = preserve(failure, cleanupFailure);
        }

        if (failure != null) {
            throwFailure(failure);
        }
        return result;
    }

    static void rollbackAfterFailure(Connection connection, Throwable primaryFailure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            primaryFailure.addSuppressed(rollbackFailure);
        }
    }

    static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path candidate : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(candidate);
            }
        }
    }

    private static Throwable rollbackOpenConnection(
            Connection connection,
            Throwable primaryFailure) {
        try {
            if (!connection.isClosed() && !connection.getAutoCommit()) {
                connection.rollback();
            }
        } catch (Throwable cleanupFailure) {
            return preserve(primaryFailure, cleanupFailure);
        }
        return primaryFailure;
    }

    private static Throwable closeConnection(
            Connection connection,
            Throwable primaryFailure) {
        try {
            connection.close();
        } catch (Throwable cleanupFailure) {
            return preserve(primaryFailure, cleanupFailure);
        }
        return primaryFailure;
    }

    private static Throwable preserve(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        if (first != next) {
            first.addSuppressed(next);
        }
        return first;
    }

    private static void throwFailure(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Unexpected benchmark cleanup failure", failure);
    }
}
