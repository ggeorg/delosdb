/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.DelosIsolationSpecificationRunner

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

package org.apache.derbyTesting.functionTests.tests.delos;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.derbyTesting.functionTests.tests.delos.isolation.DelosIsolationSpecification;

/** Executes one DelosDB isolation specification over its declared matrix. */
final class DelosIsolationSpecificationRunner extends MvccSqlTestSupport {
    private static final String MVCC_ENABLED_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.enabled";
    private static final long DEFAULT_AWAIT_MILLIS = 10_000L;

    private DelosIsolationSpecificationRunner() {
    }

    static int run(DelosIsolationSpecification specification) throws Exception {
        int cases = 0;
        for (DelosIsolationSpecification.Provider provider : specification.providers()) {
            for (DelosIsolationSpecification.Storage storage : specification.storages()) {
                for (DelosIsolationSpecification.ConnectionMode connection : specification.connections()) {
                    if (connection != DelosIsolationSpecification.ConnectionMode.EMBEDDED) {
                        throw new AssertionError("Unsupported isolation connection mode: " + connection);
                    }
                    for (DelosIsolationSpecification.Permutation permutation
                            : specification.permutations()) {
                        if (permutation.appliesTo(provider, storage)) {
                            runCase(specification, permutation, provider, storage);
                            cases++;
                        }
                    }
                }
            }
        }
        if (cases == 0) {
            throw new AssertionError("Isolation specification produced no executable cases: "
                    + specification.id());
        }
        return cases;
    }

    private static void runCase(
            DelosIsolationSpecification specification,
            DelosIsolationSpecification.Permutation permutation,
            DelosIsolationSpecification.Provider provider,
            DelosIsolationSpecification.Storage storage) throws Exception {
        String database = databaseName(specification.id(), permutation.name(), provider, storage);
        String label = specification.id() + '/' + permutation.name() + '/'
                + provider.name().toLowerCase() + '/' + storage.name().toLowerCase();

        try (SystemPropertyScope ignored = provider == DelosIsolationSpecification.Provider.MVCC
                ? setSystemProperty(MVCC_ENABLED_PROPERTY, "true")
                : clearSystemProperty(MVCC_ENABLED_PROPERTY)) {
            Throwable failure = null;
            try {
                createAndSetUpDatabase(database, specification, provider);
                List<StepOutcome> outcomes = executePermutation(
                        database, specification, permutation, provider, label);
                assertSqlStateCounts(permutation, outcomes, label);
                assertFinalState(database, specification, provider, label);
                executeTeardown(database, specification, provider);
            } catch (Exception | Error caseFailure) {
                failure = caseFailure;
                throw caseFailure;
            } finally {
                try {
                    shutdownDatabase(database);
                } catch (SQLException shutdownFailure) {
                    if (failure == null) {
                        throw shutdownFailure;
                    }
                    failure.addSuppressed(shutdownFailure);
                }
            }
        }
    }

    private static void createAndSetUpDatabase(
            String database,
            DelosIsolationSpecification specification,
            DelosIsolationSpecification.Provider provider) throws Exception {
        try (Connection connection = openDatabase(database, true)) {
            // Keep database-property writes in their default autocommit transactions.
            // RawStore MVCC metadata is initialized by a nested user transaction;
            // carrying the property-conglomerate lock into CREATE TABLE would make
            // that nested initialization wait on its own parent transaction.
            executeStatement(connection,
                    "call syscs_util.syscs_set_database_property('derby.locks.deadlockTimeout', '2')");
            executeStatement(connection,
                    "call syscs_util.syscs_set_database_property('derby.locks.waitTimeout', '8')");

            connection.setAutoCommit(false);
            try {
                for (String sql : specification.setup()) {
                    executeStatement(connection, expand(sql, provider));
                }
                connection.commit();
            } catch (SQLException | RuntimeException | Error setupFailure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    setupFailure.addSuppressed(rollbackFailure);
                }
                throw setupFailure;
            }
        }
    }

    private static List<StepOutcome> executePermutation(
            String database,
            DelosIsolationSpecification specification,
            DelosIsolationSpecification.Permutation permutation,
            DelosIsolationSpecification.Provider provider,
            String label) throws Exception {
        Map<String, SessionRuntime> sessions = new LinkedHashMap<>();
        Map<String, PendingOperation> pending = new LinkedHashMap<>();
        List<StepOutcome> outcomes = new ArrayList<>();
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            for (DelosIsolationSpecification.Session session : specification.sessions().values()) {
                Connection connection = openDatabase(database, false);
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(session.isolation(provider));
                sessions.put(session.name(), new SessionRuntime(session, connection));
            }

            for (DelosIsolationSpecification.Operation operation : permutation.operations()) {
                switch (operation.type()) {
                    case RUN -> outcomes.add(executeReferencedStep(
                            operation.step(), sessions, provider, label));
                    case START -> startOperation(
                            database, operation, sessions, provider, label, pending, executor);
                    case ASSERT_BLOCKED -> assertBlocked(
                            database, pending, operation.token(), operation.timeoutMillis(), label);
                    case AWAIT -> outcomes.add(await(
                            pending, operation.token(), operation.timeoutMillis(), label));
                    case DRAIN_AND_COMMIT -> drainAndCommit(
                            pending, outcomes, operation.timeoutMillis(), label);
                }
            }
            if (!pending.isEmpty()) {
                throw new AssertionError(label + ": async operations were not awaited: "
                        + pending.keySet());
            }
            return outcomes;
        } finally {
            for (PendingOperation operation : pending.values()) {
                operation.future().cancel(true);
            }
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            closeSessions(sessions);
        }
    }

    private static void startOperation(
            String database,
            DelosIsolationSpecification.Operation operation,
            Map<String, SessionRuntime> sessions,
            DelosIsolationSpecification.Provider provider,
            String label,
            Map<String, PendingOperation> pending,
            ExecutorService executor) throws Exception {
        if (pending.containsKey(operation.token())) {
            throw new AssertionError(label + ": duplicate async token " + operation.token());
        }
        StepReference reference = resolve(operation.step(), sessions, label);
        int waitBaseline = countWaitingLocks(database);
        CountDownLatch started = new CountDownLatch(1);
        Future<StepOutcome> future = executor.submit(() -> {
            started.countDown();
            return executeStep(reference.runtime(), reference.step(), provider,
                    label + '/' + operation.step());
        });
        pending.put(operation.token(), new PendingOperation(
                reference.runtime(), operation.step(), waitBaseline, started, future));
    }

    private static StepOutcome executeReferencedStep(
            String reference,
            Map<String, SessionRuntime> sessions,
            DelosIsolationSpecification.Provider provider,
            String label) throws Exception {
        StepReference resolved = resolve(reference, sessions, label);
        return executeStep(resolved.runtime(), resolved.step(), provider, label + '/' + reference);
    }

    private static StepReference resolve(
            String reference,
            Map<String, SessionRuntime> sessions,
            String label) {
        int separator = reference == null ? -1 : reference.indexOf('.');
        if (separator <= 0 || separator == reference.length() - 1) {
            throw new AssertionError(label + ": step reference must be session.step: " + reference);
        }
        String sessionName = reference.substring(0, separator);
        String stepName = reference.substring(separator + 1);
        SessionRuntime runtime = sessions.get(sessionName);
        if (runtime == null) {
            throw new AssertionError(label + ": unknown session " + sessionName);
        }
        DelosIsolationSpecification.Step step = runtime.definition().steps().get(stepName);
        if (step == null) {
            throw new AssertionError(label + ": unknown step " + reference);
        }
        return new StepReference(runtime, step);
    }

    private static StepOutcome executeStep(
            SessionRuntime runtime,
            DelosIsolationSpecification.Step step,
            DelosIsolationSpecification.Provider provider,
            String label) throws Exception {
        try {
            switch (step.action()) {
                case SQL -> executeSql(runtime.connection(), step, provider, label);
                case COMMIT -> runtime.connection().commit();
                case ROLLBACK -> runtime.connection().rollback();
                case SAVEPOINT -> runtime.savepoints().put(
                        step.savepoint(), runtime.connection().setSavepoint(step.savepoint()));
                case ROLLBACK_TO_SAVEPOINT -> runtime.connection().rollback(
                        requireSavepoint(runtime, step.savepoint(), label));
                case RELEASE_SAVEPOINT -> {
                    Savepoint savepoint = requireSavepoint(runtime, step.savepoint(), label);
                    runtime.connection().releaseSavepoint(savepoint);
                    runtime.savepoints().remove(step.savepoint());
                }
            }
            if (!step.successAllowed()) {
                throw new AssertionError(label + ": expected SQLState "
                        + step.acceptedSqlStates() + " but step succeeded");
            }
            return new StepOutcome(label, null);
        } catch (SQLException failure) {
            String sqlState = failure.getSQLState();
            if (!step.acceptedSqlStates().contains(sqlState)) {
                throw failure;
            }
            return new StepOutcome(label, sqlState);
        }
    }

    private static Savepoint requireSavepoint(
            SessionRuntime runtime,
            String name,
            String label) {
        Savepoint savepoint = runtime.savepoints().get(name);
        if (savepoint == null) {
            throw new AssertionError(label + ": unknown savepoint " + name);
        }
        return savepoint;
    }

    private static void executeSql(
            Connection connection,
            DelosIsolationSpecification.Step step,
            DelosIsolationSpecification.Provider provider,
            String label) throws Exception {
        String sql = expand(step.sql(), provider);
        try (Statement statement = connection.createStatement()) {
            boolean hasResultSet = statement.execute(sql);
            if (hasResultSet) {
                if (!step.rowsDeclared()) {
                    throw new AssertionError(label + ": query step must declare rows: " + sql);
                }
                try (ResultSet resultSet = statement.getResultSet()) {
                    assertEquals(label + ": rows for " + sql,
                            step.expectedRows(), collectRows(resultSet));
                }
            } else {
                if (step.rowsDeclared()) {
                    throw new AssertionError(label + ": non-query step declared rows: " + sql);
                }
                if (step.expectedUpdateCount() != null) {
                    assertEquals(label + ": update count for " + sql,
                            step.expectedUpdateCount().intValue(), statement.getUpdateCount());
                }
            }
        }
    }

    private static List<String> collectRows(ResultSet resultSet) throws SQLException {
        List<String> rows = new ArrayList<>();
        int columnCount = resultSet.getMetaData().getColumnCount();
        while (resultSet.next()) {
            StringBuilder row = new StringBuilder();
            for (int column = 1; column <= columnCount; column++) {
                if (column > 1) {
                    row.append('|');
                }
                row.append(resultSet.getString(column));
            }
            rows.add(row.toString());
        }
        return rows;
    }

    private static void assertBlocked(
            String database,
            Map<String, PendingOperation> pending,
            String token,
            long timeoutMillis,
            String label) throws Exception {
        PendingOperation operation = requireOperation(pending, token, label);
        long timeout = timeoutMillis > 0 ? timeoutMillis : 1000L;
        if (!operation.started().await(timeout, TimeUnit.MILLISECONDS)) {
            throw new AssertionError(label + ": async operation did not start: " + token);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (operation.future().isDone()) {
                awaitFuture(operation.future(), token, label);
                throw new AssertionError(label + ": async operation " + token
                        + " completed but blocking was expected");
            }
            if (countWaitingLocks(database) > operation.waitBaseline()) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError(label + ": operation " + token
                + " remained incomplete but no Derby heavyweight lock wait was observed");
    }

    private static int countWaitingLocks(String database) throws SQLException {
        // Use a plain control connection. MvccSqlTestSupport.openDatabase() also
        // activates persisted MVCC conglomerates and would perturb the lock table
        // that this probe is intended to observe.
        try (Connection connection = DriverManager.getConnection("jdbc:derby:" + database);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select count(*) from SYSCS_DIAG.LOCK_TABLE where STATE = 'WAIT'")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static StepOutcome await(
            Map<String, PendingOperation> pending,
            String token,
            long timeoutMillis,
            String label) throws Exception {
        PendingOperation operation = requireOperation(pending, token, label);
        StepOutcome outcome = awaitFuture(
                operation.future(), token, label,
                timeoutMillis > 0 ? timeoutMillis : DEFAULT_AWAIT_MILLIS);
        pending.remove(token);
        return outcome;
    }

    private static void drainAndCommit(
            Map<String, PendingOperation> pending,
            List<StepOutcome> outcomes,
            long timeoutMillis,
            String label) throws Exception {
        long timeout = timeoutMillis > 0 ? timeoutMillis : DEFAULT_AWAIT_MILLIS;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        while (!pending.isEmpty()) {
            boolean progressed = false;
            for (String token : List.copyOf(pending.keySet())) {
                PendingOperation operation = pending.get(token);
                if (operation.future().isDone()) {
                    outcomes.add(awaitFuture(operation.future(), token, label));
                    operation.runtime().connection().commit();
                    pending.remove(token);
                    progressed = true;
                }
            }
            if (!progressed) {
                if (System.nanoTime() >= deadline) {
                    throw new AssertionError(label + ": mutually blocked operations did not resolve: "
                            + pending.keySet());
                }
                Thread.sleep(10L);
            }
        }
    }

    private static StepOutcome awaitFuture(
            Future<StepOutcome> future,
            String token,
            String label) throws Exception {
        return awaitFuture(future, token, label, DEFAULT_AWAIT_MILLIS);
    }

    private static StepOutcome awaitFuture(
            Future<StepOutcome> future,
            String token,
            String label,
            long timeoutMillis) throws Exception {
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (ExecutionException failure) {
            rethrow(failure.getCause());
            throw new AssertionError("unreachable");
        } catch (TimeoutException failure) {
            throw new AssertionError(label + ": async operation did not complete: " + token, failure);
        }
    }

    private static PendingOperation requireOperation(
            Map<String, PendingOperation> pending,
            String token,
            String label) {
        PendingOperation operation = pending.get(token);
        if (operation == null) {
            throw new AssertionError(label + ": unknown async token " + token);
        }
        return operation;
    }

    private static void assertSqlStateCounts(
            DelosIsolationSpecification.Permutation permutation,
            List<StepOutcome> outcomes,
            String label) {
        for (DelosIsolationSpecification.SqlStateAssertion assertion
                : permutation.sqlStateAssertions()) {
            int count = 0;
            for (StepOutcome outcome : outcomes) {
                if (assertion.sqlState().equals(outcome.sqlState())) {
                    count++;
                }
            }
            if (count < assertion.minimum() || count > assertion.maximum()) {
                throw new AssertionError(label + ": SQLState " + assertion.sqlState()
                        + " count " + count + " is outside [" + assertion.minimum()
                        + ',' + assertion.maximum() + "]; outcomes=" + outcomes);
            }
        }
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError(failure);
    }

    private static void assertFinalState(
            String database,
            DelosIsolationSpecification specification,
            DelosIsolationSpecification.Provider provider,
            String label) throws Exception {
        if (specification.finalAssertions().isEmpty()) {
            return;
        }
        try (Connection connection = openDatabase(database, false)) {
            for (DelosIsolationSpecification.QueryAssertion assertion
                    : specification.finalAssertions()) {
                String sql = expand(assertion.sql(), provider);
                try (Statement statement = connection.createStatement();
                     ResultSet resultSet = statement.executeQuery(sql)) {
                    assertEquals(label + ": final rows for " + sql,
                            assertion.rows(), collectRows(resultSet));
                }
            }
        }
    }

    private static void executeTeardown(
            String database,
            DelosIsolationSpecification specification,
            DelosIsolationSpecification.Provider provider) throws Exception {
        if (specification.teardown().isEmpty()) {
            return;
        }
        try (Connection connection = openDatabase(database, false)) {
            connection.setAutoCommit(false);
            for (String sql : specification.teardown()) {
                executeStatement(connection, expand(sql, provider));
            }
            connection.commit();
        }
    }

    private static void closeSessions(Map<String, SessionRuntime> sessions) throws Exception {
        Exception failure = null;
        for (SessionRuntime runtime : sessions.values()) {
            try {
                if (!runtime.connection().isClosed()) {
                    runtime.connection().rollback();
                    runtime.connection().close();
                }
            } catch (Exception closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static String expand(
            String sql,
            DelosIsolationSpecification.Provider provider) {
        return sql.replace("${providerClause}", provider.tableClause());
    }

    private static String databaseName(
            String specificationId,
            String permutationName,
            DelosIsolationSpecification.Provider provider,
            DelosIsolationSpecification.Storage storage) {
        String stem = "delos-isolation-"
                + sanitize(specificationId) + '-'
                + sanitize(permutationName) + '-'
                + provider.name().toLowerCase() + '-'
                + Long.toUnsignedString(System.nanoTime());
        return storage == DelosIsolationSpecification.Storage.MEMORY
                ? "memory:" + stem
                : stem;
    }

    private static String sanitize(String value) {
        return value.toLowerCase().replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    private record StepReference(
            SessionRuntime runtime,
            DelosIsolationSpecification.Step step) {
    }

    private record SessionRuntime(
            DelosIsolationSpecification.Session definition,
            Connection connection,
            Map<String, Savepoint> savepoints) {
        private SessionRuntime(
                DelosIsolationSpecification.Session definition,
                Connection connection) {
            this(definition, connection, new LinkedHashMap<>());
        }
    }

    private record PendingOperation(
            SessionRuntime runtime,
            String stepReference,
            int waitBaseline,
            CountDownLatch started,
            Future<StepOutcome> future) {
    }

    private record StepOutcome(String stepReference, String sqlState) {
    }
}
