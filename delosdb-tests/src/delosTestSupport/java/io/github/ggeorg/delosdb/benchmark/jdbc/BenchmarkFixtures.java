/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.OrderEntryType;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Spec;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Target;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Workload;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.PREFIX;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.mvccRefreshMultiJoinStatisticsEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.refreshSimpleJoinStatisticsEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.SEED;

/** Shared JDBC fixture construction and database setup. */
final class BenchmarkFixtures {
    static final int BANK_BRANCHES = 10;
    static final int BANK_TELLERS_PER_BRANCH = 10;
    static final int ORDER_CUSTOMERS = 1000;
    static final int ORDER_WAREHOUSES = 4;
    static final int ORDER_NEW_LINE_COUNT = 3;
    static final int ORDER_DELIVERY_BASE = 100_000;
    static final int ORDER_NEW_BASE = 1_000_000;
    static final int ORDER_ROLLBACK_BASE = 2_000_000;

    private BenchmarkFixtures() {
    }

    static void phase2BUpdateStatistics(Connection connection, String table)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "call SYSCS_UTIL.SYSCS_UPDATE_STATISTICS('APP', ?, null)")) {
            statement.setString(1, table);
            statement.execute();
        }
    }

    static void configureRangeBulkFetchDefault(
            Connection connection, BenchmarkConfiguration options, Spec spec) throws SQLException {
        String value = System.getProperty(PREFIX + "rangeBulkFetchDefault", "").trim();
        if (value.isEmpty()) {
            return;
        }
        if (!spec.workload().isRangeScan()
                || (options.target() != Target.DELOS_HEAP
                        && options.target() != Target.UPSTREAM_DERBY)) {
            throw new IllegalStateException(
                    "rangeBulkFetchDefault is valid only for Delos heap/upstream Derby range scans");
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid rangeBulkFetchDefault=" + value, failure);
        }
        if (parsed < 1) {
            throw new IllegalArgumentException("rangeBulkFetchDefault must be >= 1");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "call syscs_util.syscs_set_database_property("
                        + "'derby.language.bulkFetchDefault','" + parsed + "')")) {
            statement.execute();
        }
        connection.commit();
    }

    static List<String> prepareTables(
            Connection verifier, BenchmarkConfiguration options, Spec spec, DelosBenchmarkConfig config) throws SQLException {
        int tableCount = spec.workload().isConglomerateLocalityDiagnostic() ? spec.clients() : 1;
        List<String> tables = new ArrayList<>(tableCount);
        for (int index = 0; index < tableCount; index++) {
            String targetId = options.target().id();
            if (tableCount > 1) {
                targetId += "_client_" + (index + 1);
            }
            DelosJdbcBenchmarkScenario scenario = new DelosJdbcBenchmarkScenario(
                    verifier, targetId, options.target().createTableSuffix(),
                    options.target().isContainer(), config);
            scenario.prepare();
            if (spec.workload() == Workload.JOIN_INDEXED_1TO1) {
                prepareJoinDimensionFixture(
                        verifier, scenario.tableName(), options.target().createTableSuffix(),
                        config.rowCount(), config.commitBatchSize());
                if (refreshSimpleJoinStatisticsEnabled()
                        && (options.target() == Target.DELOS_HEAP_DRDA
                                || options.target() == Target.DELOS_MVCC_DRDA)) {
                    phase2BUpdateStatistics(verifier, scenario.tableName());
                    phase2BUpdateStatistics(verifier, joinDimensionTableName(scenario.tableName()));
                    verifier.commit();
                    System.out.printf(Locale.ROOT,
                            "FIXTURE simple-join-statistics-refreshed target=%s workload=%s%n",
                            options.target().id(), spec.workload().name());
                    System.out.flush();
                }
            } else if (spec.workload() == Workload.JOIN_INDEXED_FANOUT) {
                prepareJoinFanoutFixture(
                        verifier, scenario.tableName(), options.target().createTableSuffix(),
                        config.rowCount(), config.commitBatchSize());
                if (refreshSimpleJoinStatisticsEnabled()
                        && (options.target() == Target.DELOS_HEAP_DRDA
                                || options.target() == Target.DELOS_MVCC_DRDA)) {
                    phase2BUpdateStatistics(
                            verifier, joinFanoutParentTableName(scenario.tableName()));
                    phase2BUpdateStatistics(
                            verifier, joinFanoutChildTableName(scenario.tableName()));
                    verifier.commit();
                    System.out.printf(Locale.ROOT,
                            "FIXTURE simple-join-statistics-refreshed target=%s workload=%s%n",
                            options.target().id(), spec.workload().name());
                    System.out.flush();
                }
            } else if (spec.workload() == Workload.JOIN_3WAY_SELECTIVE
                    || spec.workload() == Workload.JOIN_4WAY_FANOUT) {
                prepareMultiJoinFixture(
                        verifier, scenario.tableName(), options.target().createTableSuffix(),
                        config.rowCount(), config.commitBatchSize());
                if (mvccRefreshMultiJoinStatisticsEnabled()
                        && options.target() == Target.DELOS_MVCC_DRDA) {
                    phase2BUpdateStatistics(verifier, multiJoinCustomerTableName(scenario.tableName()));
                    phase2BUpdateStatistics(verifier, multiJoinOrderTableName(scenario.tableName()));
                    phase2BUpdateStatistics(verifier, multiJoinLineTableName(scenario.tableName()));
                    verifier.commit();
                    System.out.printf(Locale.ROOT,
                            "FIXTURE mvcc-multi-join-statistics-refreshed target=%s workload=%s%n",
                            options.target().id(), spec.workload().name());
                    System.out.flush();
                }
            } else if (spec.workload() == Workload.GROUP_HIGH_CARD) {
                prepareHighCardGroupFixture(
                        verifier, scenario.tableName(), options.target().createTableSuffix(),
                        config.rowCount(), config.commitBatchSize());
            } else if (spec.workload() == Workload.BANK_TRANSACTION) {
                prepareBankFixture(
                        verifier, scenario.tableName(), options.target().createTableSuffix(),
                        config.rowCount(), config.commitBatchSize());
            } else if (spec.workload() == Workload.ORDER_ENTRY_MIX) {
                prepareOrderEntryFixture(
                        verifier, scenario.tableName(), options.target().createTableSuffix(),
                        config.rowCount(), spec.clients(), options.transactionsPerClient(spec, config.rowCount()),
                        config.commitBatchSize());
            }
            if (spec.workload().isCoveringRangeScan()
                    || (spec.workload().isRowBearingComparisonRangeScan()
                            && (options.target() == Target.DELOS_HEAP
                                    || options.target() == Target.UPSTREAM_DERBY))) {
                String coveringIndex = rangeCoveringIndexName(scenario.tableName());
                try (java.sql.Statement statement = verifier.createStatement()) {
                    statement.executeUpdate("create index " + coveringIndex + " on "
                            + scenario.tableName() + " (id, quantity)");
                }
                verifier.commit();
            }
            tables.add(scenario.tableName());
        }
        return List.copyOf(tables);
    }

    static String joinDimensionTableName(String table) {
        return table + "_JOIN_DIM";
    }

    static String highCardGroupTableName(String table) {
        return table + "_GROUP";
    }

    static void resetAuxiliaryTables(Connection connection, String... tableNames) throws SQLException {
        for (int index = tableNames.length - 1; index >= 0; index--) {
            String tableName = tableNames[index];
            if (!databaseTableExists(connection, tableName)) {
                continue;
            }
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("drop table " + tableName);
            }
            connection.commit();
        }
    }

    static boolean databaseTableExists(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String lookupName = tableName;
        if (metadata.storesUpperCaseIdentifiers()) {
            lookupName = tableName.toUpperCase(Locale.ROOT);
        } else if (metadata.storesLowerCaseIdentifiers()) {
            lookupName = tableName.toLowerCase(Locale.ROOT);
        }
        try (ResultSet tables = metadata.getTables(
                connection.getCatalog(), null, lookupName, new String[] {"TABLE"})) {
            return tables.next();
        }
    }

    static void prepareJoinDimensionFixture(
            Connection connection,
            String table,
            String createTableSuffix,
            int rowCount,
            int commitBatchSize) throws SQLException {
        String dimension = joinDimensionTableName(table);
        resetAuxiliaryTables(connection, dimension);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "create table " + dimension + " (id int not null primary key)" + createTableSuffix);
        }
        connection.commit();
        int rows = Math.min(1000, rowCount);
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into " + dimension + " (id) values (?)")) {
            for (int id = 1; id <= rows; id++) {
                insert.setInt(1, id);
                insert.addBatch();
                if (id % commitBatchSize == 0) {
                    insert.executeBatch();
                    connection.commit();
                }
            }
            if (rows % commitBatchSize != 0) {
                insert.executeBatch();
                connection.commit();
            }
        }
    }

    static String joinFanoutParentTableName(String table) {
        return table + "_JOIN_PARENT";
    }

    static String joinFanoutChildTableName(String table) {
        return table + "_JOIN_CHILD";
    }

    static String multiJoinCustomerTableName(String table) {
        return table + "_MJ_CUSTOMER";
    }

    static String multiJoinOrderTableName(String table) {
        return table + "_MJ_ORDER";
    }

    static String multiJoinLineTableName(String table) {
        return table + "_MJ_LINE";
    }

    static String multiJoinItemTableName(String table) {
        return table + "_MJ_ITEM";
    }

    static int joinParentRows(int rowCount) {
        return Math.min(1000, Math.max(1, rowCount / 10));
    }

    static void prepareJoinFanoutFixture(
            Connection connection,
            String table,
            String createTableSuffix,
            int rowCount,
            int commitBatchSize) throws SQLException {
        String parent = joinFanoutParentTableName(table);
        String child = joinFanoutChildTableName(table);
        resetAuxiliaryTables(connection, parent, child);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "create table " + parent + " (id int not null primary key)" + createTableSuffix);
            statement.executeUpdate(
                    "create table " + child
                            + " (id int not null primary key, parent_id int not null)" + createTableSuffix);
            statement.executeUpdate("create index " + child + "_P_IDX on " + child + " (parent_id)");
        }
        connection.commit();

        int parents = joinParentRows(rowCount);
        try (PreparedStatement insertParent = connection.prepareStatement(
                        "insert into " + parent + " (id) values (?)");
                PreparedStatement insertChild = connection.prepareStatement(
                        "insert into " + child + " (id, parent_id) values (?, ?)")) {
            int pending = 0;
            for (int parentId = 1; parentId <= parents; parentId++) {
                insertParent.setInt(1, parentId);
                insertParent.addBatch();
                for (int childNo = 1; childNo <= 10; childNo++) {
                    insertChild.setInt(1, (parentId - 1) * 10 + childNo);
                    insertChild.setInt(2, parentId);
                    insertChild.addBatch();
                    pending++;
                }
                if (pending >= commitBatchSize) {
                    insertParent.executeBatch();
                    insertChild.executeBatch();
                    connection.commit();
                    pending = 0;
                }
            }
            if (pending != 0) {
                insertParent.executeBatch();
                insertChild.executeBatch();
                connection.commit();
            }
        }
    }

    static void prepareMultiJoinFixture(
            Connection connection,
            String table,
            String createTableSuffix,
            int rowCount,
            int commitBatchSize) throws SQLException {
        String customer = multiJoinCustomerTableName(table);
        String order = multiJoinOrderTableName(table);
        String line = multiJoinLineTableName(table);
        String item = multiJoinItemTableName(table);
        resetAuxiliaryTables(connection, customer, order, line, item);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "create table " + customer
                            + " (id int not null primary key, bucket int not null)" + createTableSuffix);
            statement.executeUpdate("create index " + customer + "_B_IDX on " + customer + " (bucket)");
            statement.executeUpdate(
                    "create table " + order
                            + " (id int not null primary key, customer_id int not null)" + createTableSuffix);
            statement.executeUpdate("create index " + order + "_C_IDX on " + order + " (customer_id)");
            statement.executeUpdate(
                    "create table " + line
                            + " (id int not null primary key, order_id int not null,"
                            + " line_no int not null, item_id int not null)" + createTableSuffix);
            statement.executeUpdate("create index " + line + "_O_IDX on " + line + " (order_id)");
            statement.executeUpdate(
                    "create table " + item + " (id int not null primary key)" + createTableSuffix);
        }
        connection.commit();

        int customers = joinParentRows(rowCount);
        int items = Math.min(128, Math.max(1, customers));
        try (PreparedStatement insertItem = connection.prepareStatement(
                "insert into " + item + " (id) values (?)")) {
            for (int itemId = 1; itemId <= items; itemId++) {
                insertItem.setInt(1, itemId);
                insertItem.addBatch();
            }
            insertItem.executeBatch();
            connection.commit();
        }

        try (PreparedStatement insertCustomer = connection.prepareStatement(
                        "insert into " + customer + " (id, bucket) values (?, ?)");
                PreparedStatement insertOrder = connection.prepareStatement(
                        "insert into " + order + " (id, customer_id) values (?, ?)");
                PreparedStatement insertLine = connection.prepareStatement(
                        "insert into " + line + " (id, order_id, line_no, item_id) values (?, ?, ?, ?)")) {
            int pending = 0;
            for (int customerId = 1; customerId <= customers; customerId++) {
                insertCustomer.setInt(1, customerId);
                insertCustomer.setInt(2, customerId % 10);
                insertCustomer.addBatch();
                for (int orderNo = 1; orderNo <= 4; orderNo++) {
                    int orderId = (customerId - 1) * 4 + orderNo;
                    insertOrder.setInt(1, orderId);
                    insertOrder.setInt(2, customerId);
                    insertOrder.addBatch();
                    for (int lineNo = 1; lineNo <= 3; lineNo++) {
                        int lineId = (orderId - 1) * 3 + lineNo;
                        insertLine.setInt(1, lineId);
                        insertLine.setInt(2, orderId);
                        insertLine.setInt(3, lineNo);
                        insertLine.setInt(4, 1 + ((lineId - 1) % items));
                        insertLine.addBatch();
                        pending++;
                    }
                }
                if (pending >= commitBatchSize) {
                    insertCustomer.executeBatch();
                    insertOrder.executeBatch();
                    insertLine.executeBatch();
                    connection.commit();
                    pending = 0;
                }
            }
            if (pending != 0) {
                insertCustomer.executeBatch();
                insertOrder.executeBatch();
                insertLine.executeBatch();
                connection.commit();
            }
        }
    }

    static void prepareHighCardGroupFixture(
            Connection connection,
            String table,
            String createTableSuffix,
            int rowCount,
            int commitBatchSize) throws SQLException {
        String groupTable = highCardGroupTableName(table);
        resetAuxiliaryTables(connection, groupTable);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "create table " + groupTable
                            + " (id int not null primary key, group_key int not null, quantity int not null)"
                            + createTableSuffix);
        }
        connection.commit();
        int[] quantities = fixtureQuantities(rowCount);
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into " + groupTable + " (id, group_key, quantity) values (?, ?, ?)")) {
            for (int id = 1; id <= rowCount; id++) {
                insert.setInt(1, id);
                insert.setInt(2, id % Math.min(1000, rowCount));
                insert.setInt(3, quantities[id]);
                insert.addBatch();
                if (id % commitBatchSize == 0) {
                    insert.executeBatch();
                    connection.commit();
                }
            }
            if (rowCount % commitBatchSize != 0) {
                insert.executeBatch();
                connection.commit();
            }
        }
    }

    static String bankBranchTableName(String table) {
        return table + "_BANK_BRANCH";
    }

    static String bankTellerTableName(String table) {
        return table + "_BANK_TELLER";
    }

    static String bankHistoryTableName(String table) {
        return table + "_BANK_HISTORY";
    }

    static String orderWarehouseTableName(String table) {
        return table + "_OE_WAREHOUSE";
    }

    static String orderCustomerTableName(String table) {
        return table + "_OE_CUSTOMER";
    }

    static String orderTableName(String table) {
        return table + "_OE_ORDER";
    }

    static String orderLineTableName(String table) {
        return table + "_OE_LINE";
    }

    static void prepareBankFixture(
            Connection connection,
            String table,
            String createTableSuffix,
            int rowCount,
            int commitBatchSize) throws SQLException {
        if (rowCount < 100) {
            throw new SQLException("Bank transaction fitness requires at least 100 account rows");
        }
        resetAuxiliaryTables(
                connection,
                bankBranchTableName(table),
                bankTellerTableName(table),
                bankHistoryTableName(table));
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table " + bankBranchTableName(table)
                    + " (id int not null primary key, balance bigint not null)" + createTableSuffix);
            statement.executeUpdate("create table " + bankTellerTableName(table)
                    + " (id int not null primary key, branch_id int not null, balance bigint not null)"
                    + createTableSuffix);
            statement.executeUpdate("create table " + bankHistoryTableName(table)
                    + " (tx_id int not null primary key, account_id int not null, teller_id int not null, "
                    + "branch_id int not null, amount int not null)" + createTableSuffix);
        }
        connection.commit();
        try (PreparedStatement branch = connection.prepareStatement(
                        "insert into " + bankBranchTableName(table) + " (id, balance) values (?, 0)");
                PreparedStatement teller = connection.prepareStatement(
                        "insert into " + bankTellerTableName(table)
                                + " (id, branch_id, balance) values (?, ?, 0)")) {
            for (int branchId = 1; branchId <= BANK_BRANCHES; branchId++) {
                branch.setInt(1, branchId);
                branch.addBatch();
            }
            branch.executeBatch();
            for (int tellerId = 1; tellerId <= BANK_BRANCHES * BANK_TELLERS_PER_BRANCH; tellerId++) {
                teller.setInt(1, tellerId);
                teller.setInt(2, 1 + (tellerId - 1) / BANK_TELLERS_PER_BRANCH);
                teller.addBatch();
                if (tellerId % commitBatchSize == 0) {
                    teller.executeBatch();
                    connection.commit();
                }
            }
            if ((BANK_BRANCHES * BANK_TELLERS_PER_BRANCH) % commitBatchSize != 0) {
                teller.executeBatch();
            }
            connection.commit();
        }
    }

    static void prepareOrderEntryFixture(
            Connection connection,
            String table,
            String createTableSuffix,
            int rowCount,
            int clients,
            int transactionsPerClient,
            int commitBatchSize) throws SQLException {
        if (rowCount < 1000) {
            throw new SQLException("Order Entry fitness requires at least 1000 stock rows");
        }
        int customerCount = Math.min(ORDER_CUSTOMERS, rowCount);
        if (customerCount < clients) {
            throw new SQLException("Order Entry customer fixture is smaller than client count");
        }
        resetAuxiliaryTables(
                connection,
                orderWarehouseTableName(table),
                orderCustomerTableName(table),
                orderTableName(table),
                orderLineTableName(table));
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table " + orderWarehouseTableName(table)
                    + " (id int not null primary key, balance bigint not null)" + createTableSuffix);
            statement.executeUpdate("create table " + orderCustomerTableName(table)
                    + " (id int not null primary key, balance bigint not null, last_order int not null)"
                    + createTableSuffix);
            statement.executeUpdate("create table " + orderTableName(table)
                    + " (id int not null primary key, customer_id int not null, status int not null, amount int not null)"
                    + createTableSuffix);
            statement.executeUpdate("create table " + orderLineTableName(table)
                    + " (order_id int not null, line_no int not null, stock_id int not null, quantity int not null, "
                    + "amount int not null, primary key (order_id, line_no))" + createTableSuffix);
        }
        connection.commit();
        try (PreparedStatement warehouse = connection.prepareStatement(
                        "insert into " + orderWarehouseTableName(table) + " (id, balance) values (?, 0)");
                PreparedStatement customer = connection.prepareStatement(
                        "insert into " + orderCustomerTableName(table)
                                + " (id, balance, last_order) values (?, 0, 0)");
                PreparedStatement seedOrder = connection.prepareStatement(
                        "insert into " + orderTableName(table)
                                + " (id, customer_id, status, amount) values (?, ?, 0, 50)")) {
            for (int warehouseId = 1; warehouseId <= ORDER_WAREHOUSES; warehouseId++) {
                warehouse.setInt(1, warehouseId);
                warehouse.addBatch();
            }
            warehouse.executeBatch();
            for (int customerId = 1; customerId <= customerCount; customerId++) {
                customer.setInt(1, customerId);
                customer.addBatch();
                if (customerId % commitBatchSize == 0) {
                    customer.executeBatch();
                    connection.commit();
                }
            }
            if (customerCount % commitBatchSize != 0) {
                customer.executeBatch();
            }
            connection.commit();
            for (int client = 0; client < clients; client++) {
                for (int transaction = 0; transaction < transactionsPerClient; transaction++) {
                    if (orderEntryType(transaction) != OrderEntryType.DELIVERY) {
                        continue;
                    }
                    int globalOrdinal = client * transactionsPerClient + transaction;
                    seedOrder.setInt(1, ORDER_DELIVERY_BASE + globalOrdinal);
                    seedOrder.setInt(2, orderCustomerId(client, transaction, clients, customerCount));
                    seedOrder.addBatch();
                }
            }
            seedOrder.executeBatch();
            connection.commit();
        }
    }

    static int orderCustomerId(
            int clientIndex,
            int transaction,
            int clients,
            int customerCount) {
        int logicalTransaction = orderEntryType(transaction) == OrderEntryType.ORDER_STATUS
                ? (transaction / 20) * 20
                : transaction;
        int partition = customerCount / clients;
        if (partition == 0) {
            return 1 + Math.floorMod(clientIndex + logicalTransaction, customerCount);
        }
        int base = clientIndex * partition;
        int limit = clientIndex == clients - 1 ? customerCount - base : partition;
        return 1 + base + Math.floorMod(logicalTransaction * 13 + 3, limit);
    }

    static OrderEntryType orderEntryType(int transaction) {
        int slot = Math.floorMod(transaction, 20);
        if (slot < 8) {
            return OrderEntryType.NEW_ORDER;
        }
        if (slot < 16) {
            return OrderEntryType.PAYMENT;
        }
        return switch (slot) {
            case 16 -> OrderEntryType.ORDER_STATUS;
            case 17 -> OrderEntryType.DELIVERY;
            case 18 -> OrderEntryType.STOCK_LEVEL;
            case 19 -> OrderEntryType.NEW_ORDER_ROLLBACK;
            default -> throw new IllegalStateException("Unexpected Order Entry mix slot: " + slot);
        };
    }

    static int[] fixtureQuantities(int rowCount) {
        int[] quantities = new int[rowCount + 1];
        // Matches DelosJdbcBenchmarkScenario.prepare(); avoids touching database pages before timed reads.
        Random random = new Random(SEED);
        for (int id = 1; id <= rowCount; id++) {
            quantities[id] = random.nextInt(10_000);
        }
        return quantities;
    }

    static String rangeCoveringIndexName(String table) {
        return table + "_PK_COVER_IDX";
    }
}
