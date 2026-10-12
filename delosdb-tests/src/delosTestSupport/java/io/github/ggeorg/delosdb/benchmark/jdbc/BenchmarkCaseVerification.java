/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Spec;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Verification;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Workload;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.f08TransactionStatusCrossEngineEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.freshRealisticTransactionFitnessEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.gen2A1ThroughputSentinelEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.gen2BThroughputSentinelEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.postModernizationMutationCheckpointEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.ORDER_CUSTOMERS;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.ORDER_DELIVERY_BASE;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.ORDER_NEW_BASE;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.ORDER_NEW_LINE_COUNT;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.bankBranchTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.bankHistoryTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.bankTellerTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.fixtureQuantities;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.orderCustomerId;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.orderCustomerTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.orderEntryType;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.orderLineTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.orderTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.orderWarehouseTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.format;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.mix;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.authoritativeInsertState;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.bankAccountId;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.bankBranchId;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.bankDelta;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.bankTellerId;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.bindFitnessRead;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.fitnessReadSql;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.orderNewLineAmount;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.orderNewTotalAmount;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.orderPaymentAmount;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.orderStockId;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.orderWarehouseId;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.quantity;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.readOperationIds;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.verifyInsertedState;

/** Verification and restoration using borrowed verifier and captured case baselines. */
record BenchmarkCaseVerification(
        BenchmarkConfiguration options,
        Spec spec,
        Connection verifier,
        String table,
        int rowCount,
        int transactionsPerClient,
        int[] mutationIds,
        int[] mutationBaseline,
        int[] deleteReinsertIds,
        DelosSqlSemanticOracle.Result deleteReinsertBaseline,
        int insertFirstId,
        int insertLastId) {
    Verification verifyAndRestore(boolean captureSqlOracle) throws SQLException {
        try {
            long fingerprint = mix(spec.clients(), spec.operationsPerTransaction());
            if (spec.workload().isReadOnly()) {
                DelosSqlSemanticOracle.Result oracle = captureSqlOracle
                        ? authoritativeReadOracle(verifier, table, spec, rowCount)
                        : null;
                verifier.rollback();
                return new Verification(fingerprint, oracle);
            }
            if (spec.workload().isRealisticTransaction()) {
                return verifyAndRestoreRealisticTransaction(captureSqlOracle, fingerprint);
            }
            if (spec.workload().isInsert()) {
                long expectedRows = Math.multiplyExact(
                        Math.multiplyExact((long) spec.clients(), transactionsPerClient),
                        spec.operationsPerTransaction());
                long insertedFingerprint = verifyInsertedState(
                        verifier, table, insertFirstId, insertLastId, rowCount, expectedRows,
                        options.payload());
                DelosSqlSemanticOracle.Result oracle = captureSqlOracle
                        ? DelosSqlSemanticOracle.mutation(
                                expectedRows,
                                authoritativeInsertState(verifier, table, insertFirstId, insertLastId))
                        : null;
                if (gen2A1ThroughputSentinelEnabled()
                        || gen2BThroughputSentinelEnabled()
                        || postModernizationMutationCheckpointEnabled()
                        || f08TransactionStatusCrossEngineEnabled()) {
                    // One fresh append-only interval per database. These benchmark modes
                    // deliberately exclude cleanup DELETE from INSERT timing/semantics.
                    verifier.rollback();
                    return new Verification(mix(fingerprint, insertedFingerprint), oracle);
                }
                try (PreparedStatement cleanup = verifier.prepareStatement(
                        "delete from " + table + " where id >= ? and id <= ?")) {
                    cleanup.setInt(1, insertFirstId);
                    cleanup.setInt(2, insertLastId);
                    int deleted = cleanup.executeUpdate();
                    if (deleted != expectedRows) {
                        throw new SQLException(
                                "INSERT fitness cleanup row-count drift for " + spec
                                        + ": expected=" + expectedRows + ", actual=" + deleted);
                    }
                }
                verifier.commit();
                return new Verification(mix(fingerprint, insertedFingerprint), oracle);
            }
            if (spec.workload().isDeleteReinsert()) {
                DelosSqlSemanticOracle.Result current =
                        authoritativeDeleteReinsertState(verifier, table, deleteReinsertIds);
                if (!Objects.equals(deleteReinsertBaseline.fingerprint(), current.fingerprint())
                        || deleteReinsertBaseline.count() != current.count()) {
                    throw new IllegalStateException(
                            "Delete/reinsert state drift for " + spec
                                    + ": expected=" + deleteReinsertBaseline
                                    + ", actual=" + current);
                }
                DelosSqlSemanticOracle.Result oracle = null;
                if (captureSqlOracle) {
                    long pairs = Math.multiplyExact(
                            Math.multiplyExact((long) spec.clients(), transactionsPerClient),
                            spec.operationsPerTransaction());
                    oracle = DelosSqlSemanticOracle.mutation(Math.multiplyExact(2L, pairs), current);
                }
                verifier.rollback();
                return new Verification(mix(fingerprint, current.fingerprint().hashCode()), oracle);
            }
            if (spec.workload().isFreshIndexedUpdate()) {
                long affectedRows = Math.multiplyExact(
                        Math.multiplyExact((long) spec.clients(), transactionsPerClient),
                        spec.operationsPerTransaction());
                if (affectedRows > rowCount) {
                    throw new IllegalStateException(
                            "Fresh indexed UPDATE exceeds fixture rows: affected=" + affectedRows
                                    + ", rows=" + rowCount + ", spec=" + spec);
                }
                int[] quantities = fixtureQuantities(rowCount);
                long freshFingerprint = fingerprint;
                try (PreparedStatement verify = verifier.prepareStatement(
                        "select id, quantity from " + table + " where id >= 1 and id <= ? order by id")) {
                    verify.setLong(1, affectedRows);
                    try (ResultSet resultSet = verify.executeQuery()) {
                        int expectedId = 1;
                        while (resultSet.next()) {
                            int id = resultSet.getInt(1);
                            int actual = resultSet.getInt(2);
                            if (id != expectedId) {
                                throw new IllegalStateException(
                                        "Fresh indexed UPDATE id drift: expected=" + expectedId
                                                + ", actual=" + id);
                            }
                            int expected = Math.addExact(quantities[id], 1);
                            if (actual != expected) {
                                throw new IllegalStateException(
                                        "Fresh indexed UPDATE value drift: id=" + id
                                                + ", expected=" + expected + ", actual=" + actual);
                            }
                            freshFingerprint = mix(mix(freshFingerprint, id), actual);
                            expectedId++;
                        }
                        if ((long) expectedId - 1L != affectedRows) {
                            throw new IllegalStateException(
                                    "Fresh indexed UPDATE row-count drift: expected=" + affectedRows
                                            + ", actual=" + (expectedId - 1));
                        }
                    }
                }
                DelosSqlSemanticOracle.Result oracle = null;
                if (captureSqlOracle) {
                    try (PreparedStatement statement = verifier.prepareStatement(
                            "select id, quantity from " + table
                                    + " where id >= 1 and id <= ? order by id")) {
                        statement.setLong(1, affectedRows);
                        try (ResultSet resultSet = statement.executeQuery()) {
                            oracle = DelosSqlSemanticOracle.mutation(
                                    affectedRows,
                                    DelosSqlSemanticOracle.query(
                                            resultSet, DelosSqlSemanticOracle.RowOrder.ORDERED));
                        }
                    }
                }
                verifier.rollback();
                return new Verification(freshFingerprint, oracle);
            }
            int increment = Math.multiplyExact(transactionsPerClient, spec.operationsPerTransaction());
            for (int index = 0; index < mutationIds.length; index++) {
                int expected = mutationBaseline[index];
                if (spec.workload() == Workload.DISJOINT_INDEXED_UPDATE
                        || spec.workload() == Workload.LONG_READER_DISJOINT_WRITER) {
                    expected += increment;
                } else if (spec.workload() == Workload.CONTENDED_INDEXED_UPDATE
                        || spec.workload() == Workload.LONG_READER_HOT_WRITER) {
                    expected += Math.multiplyExact(spec.clients(), increment);
                } else if (spec.workload() == Workload.MIXED_80R20W) {
                    expected += transactionsPerClient / 5;
                } else if (spec.workload() == Workload.MIXED_50R50W_HOT) {
                    int writersPerKey = spec.clients() / mutationIds.length;
                    expected += Math.multiplyExact(writersPerKey, transactionsPerClient / 2);
                }
                int actual = quantity(verifier, table, mutationIds[index]);
                if (actual != expected) {
                    throw new IllegalStateException("Concurrent semantic drift for " + spec
                            + ", id=" + mutationIds[index] + ": expected=" + expected + ", actual=" + actual);
                }
                fingerprint = mix(mix(fingerprint, mutationIds[index]), actual);
            }
            DelosSqlSemanticOracle.Result oracle = null;
            if (captureSqlOracle) {
                DelosSqlSemanticOracle.Result finalState =
                        authoritativeMutationState(verifier, table, mutationIds);
                long affectedRows;
                if (spec.workload() == Workload.MIXED_80R20W) {
                    affectedRows = Math.multiplyExact((long) spec.clients(), transactionsPerClient / 5);
                } else if (spec.workload() == Workload.MIXED_50R50W_HOT) {
                    affectedRows = Math.multiplyExact((long) spec.clients(), transactionsPerClient / 2);
                } else {
                    affectedRows = Math.multiplyExact(
                            Math.multiplyExact((long) spec.clients(), transactionsPerClient),
                            spec.operationsPerTransaction());
                }
                oracle = DelosSqlSemanticOracle.mutation(affectedRows, finalState);
            }
            try (PreparedStatement restore = verifier.prepareStatement(
                    "update " + table + " set quantity = ? where id = ?")) {
                for (int index = 0; index < mutationIds.length; index++) {
                    restore.setInt(1, mutationBaseline[index]);
                    restore.setInt(2, mutationIds[index]);
                    if (restore.executeUpdate() != 1) {
                        throw new SQLException(
                                "Concurrent restore did not affect one row: id=" + mutationIds[index]);
                    }
                }
            }
            verifier.commit();
            return new Verification(fingerprint, oracle);
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                verifier.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    private Verification verifyAndRestoreRealisticTransaction(
            boolean captureSqlOracle, long fingerprint) throws SQLException {
        return spec.workload() == Workload.BANK_TRANSACTION
                ? verifyAndRestoreBankTransaction(captureSqlOracle, fingerprint)
                : verifyAndRestoreOrderEntry(captureSqlOracle, fingerprint);
    }

    private Verification verifyAndRestoreBankTransaction(
            boolean captureSqlOracle, long fingerprint) throws SQLException {
        int[] quantities = fixtureQuantities(rowCount);
        Map<Integer, Integer> accountDelta = new java.util.TreeMap<>();
        Map<Integer, Long> tellerDelta = new java.util.TreeMap<>();
        Map<Integer, Long> branchDelta = new java.util.TreeMap<>();
        long expectedTotal = 0L;
        int expectedTransactions = Math.multiplyExact(spec.clients(), transactionsPerClient);
        for (int client = 0; client < spec.clients(); client++) {
            for (int transaction = 0; transaction < transactionsPerClient; transaction++) {
                int globalOrdinal = client * transactionsPerClient + transaction;
                int accountId = bankAccountId(globalOrdinal, rowCount);
                int branchId = bankBranchId(accountId, rowCount);
                int tellerId = bankTellerId(branchId, globalOrdinal);
                int delta = bankDelta(globalOrdinal);
                accountDelta.merge(accountId, delta, Math::addExact);
                tellerDelta.merge(tellerId, (long) delta, Math::addExact);
                branchDelta.merge(branchId, (long) delta, Math::addExact);
                expectedTotal = Math.addExact(expectedTotal, delta);
            }
        }

        long actualAccountTotal = 0L;
        for (Map.Entry<Integer, Integer> entry : accountDelta.entrySet()) {
            int actual = quantity(verifier, table, entry.getKey());
            int expected = Math.addExact(quantities[entry.getKey()], entry.getValue());
            if (actual != expected) {
                throw new IllegalStateException("Bank account state drift: id=" + entry.getKey()
                        + ", expected=" + expected + ", actual=" + actual);
            }
            actualAccountTotal = Math.addExact(
                    actualAccountTotal, (long) actual - quantities[entry.getKey()]);
            fingerprint = mix(mix(fingerprint, entry.getKey()), actual);
        }
        long actualTellerTotal = verifyBalanceTable(
                verifier, bankTellerTableName(table), tellerDelta, "bank teller");
        long actualBranchTotal = verifyBalanceTable(
                verifier, bankBranchTableName(table), branchDelta, "bank branch");
        long actualHistoryTotal = 0L;
        int historyRows = 0;
        try (Statement statement = verifier.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "select tx_id, account_id, teller_id, branch_id, amount from "
                                + bankHistoryTableName(table) + " order by tx_id")) {
            while (resultSet.next()) {
                int txId = resultSet.getInt(1);
                int globalOrdinal = txId - 1;
                if (globalOrdinal < 0 || globalOrdinal >= expectedTransactions) {
                    throw new IllegalStateException("Unexpected bank history tx_id=" + txId);
                }
                int expectedAccount = bankAccountId(globalOrdinal, rowCount);
                int expectedBranch = bankBranchId(expectedAccount, rowCount);
                int expectedTeller = bankTellerId(expectedBranch, globalOrdinal);
                int expectedDelta = bankDelta(globalOrdinal);
                if (resultSet.getInt(2) != expectedAccount
                        || resultSet.getInt(3) != expectedTeller
                        || resultSet.getInt(4) != expectedBranch
                        || resultSet.getInt(5) != expectedDelta) {
                    throw new IllegalStateException("Bank history state drift for tx_id=" + txId);
                }
                actualHistoryTotal = Math.addExact(actualHistoryTotal, expectedDelta);
                historyRows++;
            }
        }
        if (historyRows != expectedTransactions) {
            throw new IllegalStateException("Bank history row-count drift: expected="
                    + expectedTransactions + ", actual=" + historyRows);
        }
        if (actualAccountTotal != expectedTotal
                || actualTellerTotal != expectedTotal
                || actualBranchTotal != expectedTotal
                || actualHistoryTotal != expectedTotal) {
            throw new IllegalStateException(
                    "Bank invariant drift: expectedTotal=" + expectedTotal
                            + ", account=" + actualAccountTotal
                            + ", teller=" + actualTellerTotal
                            + ", branch=" + actualBranchTotal
                            + ", history=" + actualHistoryTotal);
        }

        DelosSqlSemanticOracle.Result oracle = null;
        if (captureSqlOracle) {
            Map<String, DelosSqlSemanticOracle.Result> components = new LinkedHashMap<>();
            components.put("accounts", queryOracle(verifier,
                    "select id, quantity from " + table + " order by id"));
            components.put("tellers", queryOracle(verifier,
                    "select id, branch_id, balance from " + bankTellerTableName(table) + " order by id"));
            components.put("branches", queryOracle(verifier,
                    "select id, balance from " + bankBranchTableName(table) + " order by id"));
            components.put("history", queryOracle(verifier,
                    "select tx_id, account_id, teller_id, branch_id, amount from "
                            + bankHistoryTableName(table) + " order by tx_id"));
            oracle = DelosSqlSemanticOracle.composite("FITNESS_BANK_TRANSACTION", components);
        }

        if (freshRealisticTransactionFitnessEnabled()) {
            verifier.rollback();
            return new Verification(mix(fingerprint, expectedTotal), oracle);
        }

        try (PreparedStatement restore = verifier.prepareStatement(
                        "update " + table + " set quantity = ? where id = ?");
                Statement cleanup = verifier.createStatement()) {
            for (int accountId : accountDelta.keySet()) {
                restore.setInt(1, quantities[accountId]);
                restore.setInt(2, accountId);
                if (restore.executeUpdate() != 1) {
                    throw new SQLException("Bank account restore failed for id=" + accountId);
                }
            }
            cleanup.executeUpdate("update " + bankTellerTableName(table) + " set balance = 0");
            cleanup.executeUpdate("update " + bankBranchTableName(table) + " set balance = 0");
            cleanup.executeUpdate("delete from " + bankHistoryTableName(table));
        }
        verifier.commit();
        return new Verification(mix(fingerprint, expectedTotal), oracle);
    }

    private Verification verifyAndRestoreOrderEntry(
            boolean captureSqlOracle, long fingerprint) throws SQLException {
        int customerCount = Math.min(ORDER_CUSTOMERS, rowCount);
        int[] quantities = fixtureQuantities(rowCount);
        Map<Integer, Integer> stockDelta = new java.util.TreeMap<>();
        Map<Integer, Long> customerBalance = new java.util.TreeMap<>();
        Map<Integer, Integer> customerLastOrder = new java.util.TreeMap<>();
        Map<Integer, Long> warehouseBalance = new java.util.TreeMap<>();
        Map<Integer, ExpectedOrder> expectedOrders = new java.util.TreeMap<>();
        Map<OrderLineKey, ExpectedOrderLine> expectedLines = new java.util.TreeMap<>();

        for (int client = 0; client < spec.clients(); client++) {
            for (int transaction = 0; transaction < transactionsPerClient; transaction++) {
                int globalOrdinal = client * transactionsPerClient + transaction;
                int customerId = orderCustomerId(client, transaction, spec.clients(), customerCount);
                int warehouseId = orderWarehouseId(client);
                switch (orderEntryType(transaction)) {
                    case NEW_ORDER -> {
                        int orderId = ORDER_NEW_BASE + globalOrdinal;
                        expectedOrders.put(
                                orderId, new ExpectedOrder(customerId, 0, orderNewTotalAmount()));
                        customerLastOrder.put(customerId, orderId);
                        for (int line = 1; line <= ORDER_NEW_LINE_COUNT; line++) {
                            int stockId = orderStockId(globalOrdinal, line, rowCount);
                            int amount = orderNewLineAmount(line);
                            stockDelta.merge(stockId, -1, Math::addExact);
                            expectedLines.put(
                                    new OrderLineKey(orderId, line),
                                    new ExpectedOrderLine(stockId, 1, amount));
                        }
                    }
                    case PAYMENT -> {
                        int amount = orderPaymentAmount(globalOrdinal);
                        customerBalance.merge(customerId, (long) amount, Math::addExact);
                        warehouseBalance.merge(warehouseId, (long) amount, Math::addExact);
                    }
                    case ORDER_STATUS, STOCK_LEVEL -> {
                        // Read-only members of the deterministic mix.
                    }
                    case DELIVERY -> {
                        int orderId = ORDER_DELIVERY_BASE + globalOrdinal;
                        expectedOrders.put(orderId, new ExpectedOrder(customerId, 1, 50));
                        customerBalance.merge(customerId, 50L, Math::addExact);
                    }
                    case NEW_ORDER_ROLLBACK -> {
                        // Deliberate rollback: no SQL-visible state is allowed to remain.
                    }
                }
            }
        }

        for (Map.Entry<Integer, Integer> entry : stockDelta.entrySet()) {
            int actual = quantity(verifier, table, entry.getKey());
            int expected = Math.addExact(quantities[entry.getKey()], entry.getValue());
            if (actual != expected) {
                throw new IllegalStateException("Order Entry stock drift: id=" + entry.getKey()
                        + ", expected=" + expected + ", actual=" + actual);
            }
            fingerprint = mix(mix(fingerprint, entry.getKey()), actual);
        }

        try (Statement statement = verifier.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "select id, balance, last_order from " + orderCustomerTableName(table)
                                + " order by id")) {
            int rows = 0;
            while (resultSet.next()) {
                int id = resultSet.getInt(1);
                long expectedBalance = customerBalance.getOrDefault(id, 0L);
                int expectedLastOrder = customerLastOrder.getOrDefault(id, 0);
                if (resultSet.getLong(2) != expectedBalance || resultSet.getInt(3) != expectedLastOrder) {
                    throw new IllegalStateException("Order Entry customer drift: id=" + id
                            + ", expectedBalance=" + expectedBalance
                            + ", actualBalance=" + resultSet.getLong(2)
                            + ", expectedLastOrder=" + expectedLastOrder
                            + ", actualLastOrder=" + resultSet.getInt(3));
                }
                rows++;
            }
            if (rows != customerCount) {
                throw new IllegalStateException("Order Entry customer row-count drift: expected="
                        + customerCount + ", actual=" + rows);
            }
        }
        long verifiedWarehouseTotal = verifyBalanceTable(
                verifier, orderWarehouseTableName(table), warehouseBalance, "order warehouse");
        long expectedWarehouseTotal = 0L;
        for (long amount : warehouseBalance.values()) {
            expectedWarehouseTotal = Math.addExact(expectedWarehouseTotal, amount);
        }
        if (verifiedWarehouseTotal != expectedWarehouseTotal) {
            throw new IllegalStateException("Order Entry warehouse invariant drift");
        }

        try (Statement statement = verifier.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "select id, customer_id, status, amount from " + orderTableName(table)
                                + " order by id")) {
            int rows = 0;
            while (resultSet.next()) {
                int id = resultSet.getInt(1);
                ExpectedOrder expected = expectedOrders.get(id);
                if (expected == null) {
                    throw new IllegalStateException("Unexpected Order Entry order row id=" + id);
                }
                if (resultSet.getInt(2) != expected.customerId()
                        || resultSet.getInt(3) != expected.status()
                        || resultSet.getInt(4) != expected.amount()) {
                    throw new IllegalStateException("Order Entry order drift: id=" + id);
                }
                rows++;
            }
            if (rows != expectedOrders.size()) {
                throw new IllegalStateException("Order Entry order row-count drift: expected="
                        + expectedOrders.size() + ", actual=" + rows);
            }
        }

        try (Statement statement = verifier.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "select order_id, line_no, stock_id, quantity, amount from "
                                + orderLineTableName(table) + " order by order_id, line_no")) {
            int rows = 0;
            while (resultSet.next()) {
                OrderLineKey key = new OrderLineKey(resultSet.getInt(1), resultSet.getInt(2));
                ExpectedOrderLine expected = expectedLines.get(key);
                if (expected == null) {
                    throw new IllegalStateException("Unexpected Order Entry line " + key);
                }
                if (resultSet.getInt(3) != expected.stockId()
                        || resultSet.getInt(4) != expected.quantity()
                        || resultSet.getInt(5) != expected.amount()) {
                    throw new IllegalStateException("Order Entry line drift: " + key);
                }
                rows++;
            }
            if (rows != expectedLines.size()) {
                throw new IllegalStateException("Order Entry line row-count drift: expected="
                        + expectedLines.size() + ", actual=" + rows);
            }
        }

        DelosSqlSemanticOracle.Result oracle = null;
        if (captureSqlOracle) {
            Map<String, DelosSqlSemanticOracle.Result> components = new LinkedHashMap<>();
            components.put("stock", queryOracle(verifier,
                    "select id, quantity from " + table + " order by id"));
            components.put("warehouses", queryOracle(verifier,
                    "select id, balance from " + orderWarehouseTableName(table) + " order by id"));
            components.put("customers", queryOracle(verifier,
                    "select id, balance, last_order from " + orderCustomerTableName(table) + " order by id"));
            components.put("orders", queryOracle(verifier,
                    "select id, customer_id, status, amount from " + orderTableName(table) + " order by id"));
            components.put("lines", queryOracle(verifier,
                    "select order_id, line_no, stock_id, quantity, amount from "
                            + orderLineTableName(table) + " order by order_id, line_no"));
            oracle = DelosSqlSemanticOracle.composite("FITNESS_ORDER_ENTRY_MIX", components);
        }

        if (freshRealisticTransactionFitnessEnabled()) {
            verifier.rollback();
            return new Verification(mix(fingerprint, expectedOrders.size()), oracle);
        }

        try (PreparedStatement restore = verifier.prepareStatement(
                        "update " + table + " set quantity = ? where id = ?");
                Statement cleanup = verifier.createStatement()) {
            for (int stockId : stockDelta.keySet()) {
                restore.setInt(1, quantities[stockId]);
                restore.setInt(2, stockId);
                if (restore.executeUpdate() != 1) {
                    throw new SQLException("Order Entry stock restore failed for id=" + stockId);
                }
            }
            cleanup.executeUpdate("update " + orderCustomerTableName(table)
                    + " set balance = 0, last_order = 0");
            cleanup.executeUpdate("update " + orderWarehouseTableName(table) + " set balance = 0");
            cleanup.executeUpdate("delete from " + orderLineTableName(table));
            cleanup.executeUpdate("delete from " + orderTableName(table) + " where id >= " + ORDER_NEW_BASE);
            cleanup.executeUpdate("update " + orderTableName(table)
                    + " set status = 0 where id >= " + ORDER_DELIVERY_BASE + " and id < " + ORDER_NEW_BASE);
        }
        verifier.commit();
        return new Verification(mix(fingerprint, expectedOrders.size()), oracle);
    }

    private static long verifyBalanceTable(
            Connection connection,
            String table,
            Map<Integer, Long> expectedBalances,
            String label) throws SQLException {
        long total = 0L;
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "select id, balance from " + table + " order by id")) {
            while (resultSet.next()) {
                int id = resultSet.getInt(1);
                long actual = resultSet.getLong(2);
                long expected = expectedBalances.getOrDefault(id, 0L);
                if (actual != expected) {
                    throw new IllegalStateException(label + " balance drift: id=" + id
                            + ", expected=" + expected + ", actual=" + actual);
                }
                total = Math.addExact(total, actual);
            }
        }
        return total;
    }

    private static DelosSqlSemanticOracle.Result queryOracle(
            Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            return DelosSqlSemanticOracle.query(
                    resultSet, DelosSqlSemanticOracle.RowOrder.ORDERED);
        }
    }

    private static DelosSqlSemanticOracle.Result authoritativeReadOracle(
            Connection connection,
            String table,
            Spec spec,
            int rowCount) throws SQLException {
        if (spec.workload() == Workload.PRIMARY_KEY_READ_HOT) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "select quantity from " + table + " where id = ?")) {
                statement.setInt(1, 1);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return DelosSqlSemanticOracle.query(
                            resultSet, DelosSqlSemanticOracle.RowOrder.ORDERED);
                }
            }
        }
        if (spec.workload() == Workload.PRIMARY_KEY_READ_DISJOINT) {
            Map<String, DelosSqlSemanticOracle.Result> clients = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "select quantity from " + table + " where id = ?")) {
                for (int client = 0; client < spec.clients(); client++) {
                    int id = 1 + (int) (((long) client * rowCount) / spec.clients());
                    statement.setInt(1, id);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        clients.put(
                                String.format(Locale.ROOT, "client-%03d", client),
                                DelosSqlSemanticOracle.query(
                                        resultSet, DelosSqlSemanticOracle.RowOrder.ORDERED));
                    }
                }
            }
            return DelosSqlSemanticOracle.composite("FITNESS_POINT_READ", clients);
        }
        if (spec.workload() == Workload.PRIMARY_KEY_READ_RANDOM) {
            Map<String, DelosSqlSemanticOracle.Result> clients = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "select id, quantity from " + table + " where id = ?")) {
                for (int client = 0; client < spec.clients(); client++) {
                    int[] ids = readOperationIds(
                            spec, rowCount, client, spec.operationsPerTransaction());
                    List<DelosSqlSemanticOracle.Result> operations = new ArrayList<>(ids.length);
                    for (int id : ids) {
                        statement.setInt(1, id);
                        try (ResultSet resultSet = statement.executeQuery()) {
                            operations.add(DelosSqlSemanticOracle.query(
                                    resultSet, DelosSqlSemanticOracle.RowOrder.ORDERED));
                        }
                    }
                    clients.put(
                            String.format(Locale.ROOT, "client-%03d", client),
                            DelosSqlSemanticOracle.sequence(
                                    "FITNESS_POINT_READ_RANDOM_CLIENT", operations));
                }
            }
            return DelosSqlSemanticOracle.composite(
                    "FITNESS_POINT_READ_RANDOM", clients);
        }
        if (spec.workload().isRangeScan()
                && !spec.workload().isCoveringRangeScan()
                && !spec.workload().isRowBearingComparisonRangeScan()
                && !spec.workload().isMvccNaturalOrderRangeScan()) {
            Map<String, DelosSqlSemanticOracle.Result> clients = new LinkedHashMap<>();
            int rangeRows = spec.workload().rangeRows(rowCount);
            int startCount = rowCount - rangeRows + 1;
            String projection = spec.workload().isIndexOnlyRangeScan() ? "id" : "id, quantity";
            String sql = "select " + projection + " from " + table
                    + " where id >= ? and id < ? order by id";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int client = 0; client < spec.clients(); client++) {
                    int start = 1 + (int) (((long) client * startCount) / spec.clients());
                    statement.setInt(1, start);
                    statement.setInt(2, start + rangeRows);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        clients.put(
                                String.format(Locale.ROOT, "client-%03d", client),
                                DelosSqlSemanticOracle.query(
                                        resultSet, DelosSqlSemanticOracle.RowOrder.ORDERED));
                    }
                }
            }
            return DelosSqlSemanticOracle.composite("FITNESS_RANGE_SCAN", clients);
        }
        if (spec.workload().isFitnessRead()) {
            String sql = fitnessReadSql(spec.workload(), table);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bindFitnessRead(statement, spec.workload());
                try (ResultSet resultSet = statement.executeQuery()) {
                    DelosSqlSemanticOracle.RowOrder order =
                            spec.workload() == Workload.PROJECTION_COVERED
                                    || spec.workload() == Workload.JOIN_INDEXED_1TO1
                                    || spec.workload() == Workload.JOIN_3WAY_SELECTIVE
                                    || spec.workload() == Workload.JOIN_4WAY_FANOUT
                                    ? DelosSqlSemanticOracle.RowOrder.UNORDERED
                                    : DelosSqlSemanticOracle.RowOrder.ORDERED;
                    return DelosSqlSemanticOracle.query(resultSet, order);
                }
            }
        }
        throw new SQLException(
                "Phase 0A SQL oracle is not wired for fitness workload " + spec.workload());
    }

    static DelosSqlSemanticOracle.Result authoritativeDeleteReinsertState(
            Connection connection,
            String table,
            int[] ids) throws SQLException {
        if (ids.length == 0) {
            throw new SQLException("Delete/reinsert SQL oracle requires at least one row id");
        }
        StringBuilder sql = new StringBuilder(
                "select id, category, bucket, quantity, payload from " + table + " where id in (");
        for (int index = 0; index < ids.length; index++) {
            if (index != 0) {
                sql.append(',');
            }
            sql.append('?');
        }
        sql.append(") order by id");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int index = 0; index < ids.length; index++) {
                statement.setInt(index + 1, ids[index]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                return DelosSqlSemanticOracle.query(
                        resultSet, DelosSqlSemanticOracle.RowOrder.ORDERED);
            }
        }
    }

    static DelosSqlSemanticOracle.Result authoritativeLongReaderState(
            Connection connection,
            String table,
            int startInclusive,
            int endExclusive) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select id, quantity from " + table
                        + " where id >= ? and id < ? order by id")) {
            statement.setInt(1, startInclusive);
            statement.setInt(2, endExclusive);
            try (ResultSet resultSet = statement.executeQuery()) {
                return DelosSqlSemanticOracle.query(
                        resultSet, DelosSqlSemanticOracle.RowOrder.ORDERED);
            }
        }
    }

    private static DelosSqlSemanticOracle.Result authoritativeMutationState(
            Connection connection,
            String table,
            int[] mutationIds) throws SQLException {
        if (mutationIds.length == 0) {
            throw new SQLException("Mutation SQL oracle requires at least one mutation id");
        }
        StringBuilder sql = new StringBuilder(
                "select id, quantity from " + table + " where id in (");
        for (int index = 0; index < mutationIds.length; index++) {
            if (index != 0) {
                sql.append(',');
            }
            sql.append('?');
        }
        sql.append(") order by id");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int index = 0; index < mutationIds.length; index++) {
                statement.setInt(index + 1, mutationIds[index]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                return DelosSqlSemanticOracle.query(
                        resultSet, DelosSqlSemanticOracle.RowOrder.ORDERED);
            }
        }
    }

    private record ExpectedOrder(int customerId, int status, int amount) {
    }

    private record OrderLineKey(int orderId, int lineNo) implements Comparable<OrderLineKey> {
        @Override
        public int compareTo(OrderLineKey other) {
            int byOrder = Integer.compare(orderId, other.orderId);
            return byOrder != 0 ? byOrder : Integer.compare(lineNo, other.lineNo);
        }
    }

    private record ExpectedOrderLine(int stockId, int quantity, int amount) {
    }
}
