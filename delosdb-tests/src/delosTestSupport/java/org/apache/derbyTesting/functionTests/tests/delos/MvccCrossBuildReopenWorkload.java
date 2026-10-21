/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccCrossBuildReopenWorkload

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

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Cross-build reopen workload. One DelosDB build runs {@code create} to write a
 * database; another build runs {@code verify} in a separate JVM to reopen it.
 * The program uses public JDBC only and is compiled without DelosDB classes,
 * so the same class runs against both builds.
 *
 * <p>{@code create DIR clean} shuts the database down; {@code create DIR halt}
 * halts the JVM right after the last commit, so the reopen replays the log.
 * {@code verify DIR} compares both tables with the model below, checks the
 * secondary and unique indexes and the key constraints, writes, and reopens
 * once more. Each mode prints the jar the embedded driver was loaded from.</p>
 */
public final class MvccCrossBuildReopenWorkload {
    private static final String[] TABLES = {"H", "M"};
    private static final int ROWS = 400;

    private MvccCrossBuildReopenWorkload() {
    }

    public static void main(String[] args) throws Exception {
        Path database = Path.of(args[1]).toAbsolutePath();
        String url = "jdbc:derby:" + database;
        switch (args[0]) {
            case "create" -> create(url, "halt".equals(args[2]));
            case "verify" -> verify(url);
            default -> throw new IllegalArgumentException("unknown mode " + args[0]);
        }
    }

    /** Writes the committed workload; the same steps build the expected model. */
    private static void create(String url, boolean halt) throws SQLException {
        try (Connection c = DriverManager.getConnection(url + ";create=true")) {
            reportDriver(url);
            c.setAutoCommit(false);
            for (String table : TABLES) {
                String provider = table.equals("M") ? " using delos_mvcc" : "";
                update(c, "create table " + table + " (id int primary key,"
                        + " u varchar(20) not null unique, s int, v varchar(40))" + provider);
                update(c, "create index " + table + "_S on " + table + "(s)");
                try (PreparedStatement insert = c.prepareStatement(
                        "insert into " + table + " values (?, ?, ?, ?)")) {
                    for (int id = 1; id <= ROWS; id++) {
                        insert.setInt(1, id);
                        insert.setString(2, "u" + id);
                        insert.setInt(3, id % 37);
                        insert.setString(4, "v" + id);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                c.commit();
                update(c, "update " + table + " set s = s + 1000, v = 'upd' || v where mod(id, 5) = 0");
                c.commit();
                try (PreparedStatement move = c.prepareStatement(
                        "update " + table + " set u = ? where id = ?")) {
                    for (int id = 1; id <= ROWS; id += 25) {
                        move.setString(1, "moved" + id);
                        move.setInt(2, id);
                        move.executeUpdate();
                    }
                }
                c.commit();
                update(c, "delete from " + table + " where mod(id, 10) = 3");
                c.commit();
                // Deleted rows' unique keys come back under new primary keys.
                try (PreparedStatement reuse = c.prepareStatement(
                        "insert into " + table + " values (?, ?, 7, 'reused')")) {
                    for (int id = 3; id <= 93; id += 10) {
                        reuse.setInt(1, id + 1000);
                        reuse.setString(2, "u" + id);
                        reuse.executeUpdate();
                    }
                }
                c.commit();
                // An uncommitted change must not survive.
                update(c, "delete from " + table + " where id <= 50");
                c.rollback();
                assertTable(c, table, createdModel());
            }
            c.commit();
            if (halt) {
                System.out.println("HALT after commit");
                System.out.flush();
                Runtime.getRuntime().halt(0);
            }
        }
        shutdown(url);
        System.out.println("CREATED " + url);
    }

    /** Reopens with this build and checks contents, indexes, constraints and writes. */
    private static void verify(String url) throws SQLException {
        Map<Integer, Row> expected = createdModel();
        try (Connection c = DriverManager.getConnection(url)) {
            reportDriver(url);
            c.setAutoCommit(false);
            for (String table : TABLES) {
                assertTable(c, table, expected);
                assertIndexes(c, table, expected);
                check("1".equals(single(c, "values syscs_util.syscs_check_table('APP', '" + table + "')")),
                        table + ": check_table after reopening the earlier build's database");
                assertConstraints(c, table);
            }
            for (String table : TABLES) {
                update(c, "update " + table + " set v = 'after' where mod(id, 7) = 0");
                update(c, "delete from " + table + " where mod(id, 11) = 0");
                update(c, "insert into " + table + " values (2001, 'u1', 1, 'new')");
            }
            c.commit();
        }
        Map<Integer, Row> afterWrites = afterReopenWrites(expected);
        shutdown(url);
        try (Connection c = DriverManager.getConnection(url)) {
            for (String table : TABLES) {
                assertTable(c, table, afterWrites);
                assertIndexes(c, table, afterWrites);
                check("1".equals(single(c, "values syscs_util.syscs_check_table('APP', '" + table + "')")),
                        table + ": check_table after the second reopen");
            }
            c.commit();
        }
        shutdown(url);
        System.out.println("VERIFIED " + url);
    }

    private static Map<Integer, Row> createdModel() {
        Map<Integer, Row> rows = new TreeMap<>();
        for (int id = 1; id <= ROWS; id++) {
            rows.put(id, new Row("u" + id, id % 37, "v" + id));
        }
        rows.replaceAll((id, row) -> id % 5 == 0 ? new Row(row.u, row.s + 1000, "upd" + row.v) : row);
        for (int id = 1; id <= ROWS; id += 25) {
            Row row = rows.get(id);
            rows.put(id, new Row("moved" + id, row.s, row.v));
        }
        rows.keySet().removeIf(id -> id % 10 == 3);
        for (int id = 3; id <= 93; id += 10) {
            rows.put(id + 1000, new Row("u" + id, 7, "reused"));
        }
        return rows;
    }

    private static Map<Integer, Row> afterReopenWrites(Map<Integer, Row> created) {
        Map<Integer, Row> rows = new TreeMap<>(created);
        rows.replaceAll((id, row) -> id % 7 == 0 ? new Row(row.u, row.s, "after") : row);
        rows.keySet().removeIf(id -> id % 11 == 0);
        rows.put(2001, new Row("u1", 1, "new"));
        return rows;
    }

    private static void assertTable(Connection c, String table, Map<Integer, Row> expected) throws SQLException {
        Map<Integer, Row> actual = new TreeMap<>();
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("select id, u, s, v from " + table + " order by id")) {
            while (r.next()) {
                actual.put(r.getInt(1), new Row(r.getString(2), r.getInt(3), r.getString(4)));
            }
        }
        check(actual.equals(expected), table + ": rows differ from the model; expected "
                + expected.size() + " rows, found " + actual.size() + " " + firstDifference(expected, actual));
    }

    /** Reads through the secondary, unique and primary-key indexes. */
    private static void assertIndexes(Connection c, String table, Map<Integer, Row> expected) throws SQLException {
        for (int key : new int[] {0, 7, 12, 1005, 1030}) {
            List<Integer> want = new ArrayList<>();
            expected.forEach((id, row) -> { if (row.s == key) { want.add(id); } });
            check(ids(c, "select id from " + table + " --DERBY-PROPERTIES index=" + table + "_S\n"
                    + " where s = " + key + " order by id").equals(want),
                    table + ": secondary index lookup s=" + key);
        }
        for (Map.Entry<Integer, Row> entry : expected.entrySet()) {
            if (entry.getKey() % 9 != 0 && entry.getKey() < 1000) {
                continue;
            }
            check(ids(c, "select id from " + table + " where u = '" + entry.getValue().u + "'")
                            .equals(List.of(entry.getKey())),
                    table + ": unique index lookup u=" + entry.getValue().u);
            check(ids(c, "select id from " + table + " where id = " + entry.getKey())
                            .equals(List.of(entry.getKey())),
                    table + ": primary key lookup id=" + entry.getKey());
        }
        check(ids(c, "select id from " + table + " where u = 'u3'").equals(List.of(1003)),
                table + ": reused unique key u3 belongs to the new row");
    }

    private static void assertConstraints(Connection c, String table) throws SQLException {
        assertDuplicate(c, "insert into " + table + " values (2, 'fresh2', 0, 'dup id')", table + ": primary key");
        assertDuplicate(c, "insert into " + table + " values (9001, 'u2', 0, 'dup u')", table + ": unique key");
        assertDuplicate(c, "insert into " + table + " values (9002, 'moved26', 0, 'dup moved')",
                table + ": unique key written by an update");
        assertDuplicate(c, "insert into " + table + " values (9003, 'u13', 0, 'dup reused')",
                table + ": reused unique key");
    }

    private static void assertDuplicate(Connection c, String sql, String what) throws SQLException {
        try {
            update(c, sql);
            throw new AssertionError(what + " accepted a duplicate");
        } catch (SQLException expected) {
            check("23505".equals(expected.getSQLState()),
                    what + ": expected 23505, got " + expected.getSQLState() + " " + expected.getMessage());
            c.rollback();
        }
    }

    private static List<Integer> ids(Connection c, String sql) throws SQLException {
        List<Integer> ids = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            while (r.next()) {
                ids.add(r.getInt(1));
            }
        }
        return ids;
    }

    private static String firstDifference(Map<Integer, Row> expected, Map<Integer, Row> actual) {
        for (Map.Entry<Integer, Row> entry : expected.entrySet()) {
            if (!Objects.equals(entry.getValue(), actual.get(entry.getKey()))) {
                return "at id " + entry.getKey() + ": expected " + entry.getValue() + ", found " + actual.get(entry.getKey());
            }
        }
        for (Integer id : actual.keySet()) {
            if (!expected.containsKey(id)) {
                return "unexpected id " + id;
            }
        }
        return "";
    }

    private static void reportDriver(String url) throws SQLException {
        Driver driver = DriverManager.getDriver(url);
        System.out.println("DRIVER " + driver.getClass().getProtectionDomain().getCodeSource().getLocation());
    }

    private static String single(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getString(1);
        }
    }

    private static void update(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    private static void shutdown(String url) {
        try {
            DriverManager.getConnection(url + ";shutdown=true");
        } catch (SQLException expected) {
            // Derby reports a successful database shutdown as SQLState 08006.
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Row(String u, int s, String v) {
    }
}
