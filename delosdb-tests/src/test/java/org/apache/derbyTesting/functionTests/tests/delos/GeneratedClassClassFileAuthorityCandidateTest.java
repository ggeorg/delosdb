/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.GeneratedClassClassFileAuthorityCandidateTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

import junit.framework.TestCase;

import org.apache.derby.iapi.services.monitor.Monitor;
import org.apache.derby.shared.common.reference.Module;

/**
 * Compiler Phase 5.1 proof that the production-packaged Class-File API
 * candidate can be selected through the inherited test-only module override
 * and execute representative real SQL compilation paths.
 */
public final class GeneratedClassClassFileAuthorityCandidateTest
        extends TestCase {
    private static final String BACKEND_PROPERTY =
            "derby.module.javaCompiler";
    private static final String CLASSFILE_BACKEND =
            "org.apache.derby.impl.services.bytecode.classfile.ClassFileJava";
    private static final String DATABASE =
            "jdbc:derby:memory:delosClassFileAuthorityCandidate";

    public void testProductionCandidateCompilesRepresentativeSql()
            throws Exception {
        assertEquals("focused task must select the Class-File API candidate",
                CLASSFILE_BACKEND,
                System.getProperty(BACKEND_PROPERTY));

        ClassLoadingMXBean classLoading =
                ManagementFactory.getClassLoadingMXBean();
        long loadedBefore = classLoading.getTotalLoadedClassCount();
        long unloadedBefore = classLoading.getUnloadedClassCount();
        long started = System.nanoTime();
        int preparedStatements = 0;
        int wideProjectionColumns = 64;

        try (Connection connection = DriverManager.getConnection(
                DATABASE + ";create=true")) {
            Object javaFactory = Monitor.findSystemModule(Module.JavaFactory);
            assertNotNull("JavaFactory system module", javaFactory);
            assertEquals("selected JavaFactory implementation",
                    CLASSFILE_BACKEND,
                    javaFactory.getClass().getName());

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "create table T (id int primary key, i int, "
                        + "d decimal(12,2), s varchar(40))");
                statement.executeUpdate(
                        "insert into T values "
                        + "(1, 10, 12.50, 'alpha'), "
                        + "(2, 20, 7.25, null), "
                        + "(3, 30, 3.00, 'gamma')");
                statement.executeUpdate(
                        "create function APP.DELOS_DOUBLE(v int) "
                        + "returns int parameter style java no sql "
                        + "language java external name '"
                        + GeneratedClassClassFileAuthorityCandidateTest.class
                                .getName()
                        + ".doubleValue'");
            }

            try (PreparedStatement statement = connection.prepareStatement(
                    "select id, i + ?, cast(d as decimal(12,2)), "
                    + "case when s is null then 'missing' else s end, "
                    + "(select max(i) from T), APP.DELOS_DOUBLE(i) "
                    + "from T where i > ? and (s is null or id = ?) "
                    + "order by id")) {
                preparedStatements++;
                statement.setInt(1, 5);
                statement.setInt(2, 9);
                statement.setInt(3, 1);
                try (ResultSet rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                    assertEquals(15, rows.getInt(2));
                    assertEquals(new BigDecimal("12.50"), rows.getBigDecimal(3));
                    assertEquals("alpha", rows.getString(4));
                    assertEquals(30, rows.getInt(5));
                    assertEquals(20, rows.getInt(6));

                    assertTrue(rows.next());
                    assertEquals(2, rows.getInt(1));
                    assertEquals(25, rows.getInt(2));
                    assertEquals("missing", rows.getString(4));
                    assertEquals(40, rows.getInt(6));
                    assertFalse(rows.next());
                }
            }

            StringBuilder wideSql = new StringBuilder("select ");
            for (int column = 0; column < wideProjectionColumns; column++) {
                if (column > 0) {
                    wideSql.append(',');
                }
                wideSql.append("case when i >= ")
                        .append(column % 31)
                        .append(" then i + ")
                        .append(column)
                        .append(" else i - ")
                        .append(column)
                        .append(" end");
            }
            wideSql.append(" from T where id = ?");
            try (PreparedStatement statement = connection.prepareStatement(
                    wideSql.toString())) {
                preparedStatements++;
                statement.setInt(1, 3);
                try (ResultSet row = statement.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals(wideProjectionColumns,
                            row.getMetaData().getColumnCount());
                    assertEquals(30, row.getInt(1));
                    assertFalse(row.next());
                }
            }

            try (PreparedStatement statement = connection.prepareStatement(
                    "select sum(i), avg(d), count(*) from T")) {
                preparedStatements++;
                try (ResultSet row = statement.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals(60, row.getInt(1));
                    BigDecimal average = row.getBigDecimal(2);
                    assertNotNull(average);
                    assertTrue(average.compareTo(new BigDecimal("7.5")) > 0);
                    assertTrue(average.compareTo(new BigDecimal("8.0")) < 0);
                    assertEquals(3, row.getInt(3));
                    assertFalse(row.next());
                }
            }

            try (Statement statement = connection.createStatement()) {
                try {
                    statement.executeQuery("values 1 / 0");
                    fail("division by zero must preserve SQLState 22012");
                } catch (SQLException expected) {
                    assertEquals("22012", expected.getSQLState());
                }
            }

            connection.commit();
        } finally {
            shutdownDatabase();
        }

        long elapsedNanos = System.nanoTime() - started;
        long loadedDelta = classLoading.getTotalLoadedClassCount() - loadedBefore;
        long unloadedDelta = classLoading.getUnloadedClassCount() - unloadedBefore;
        String report = String.format(Locale.ROOT,
                "DelosDB Class-File API authority candidate SQL proof%n"
                + "=================================================%n"
                + "Phase: COMPILER_PHASE_5_1_AUTHORITY_CANDIDATE%n"
                + "Selected backend: %s%n"
                + "Production registration: ASM_TRANSITIONAL%n"
                + "Selection scope: FOCUSED_TEST_JVM_ONLY%n"
                + "Prepared statement families: %d%n"
                + "Wide projection columns: %d%n"
                + "Loaded-class delta: %d%n"
                + "Unloaded-class delta: %d%n"
                + "Elapsed nanos: %d%n"
                + "SQLState preservation: 22012%n"
                + "Normal runtime backend selector: none%n",
                CLASSFILE_BACKEND,
                preparedStatements,
                wideProjectionColumns,
                loadedDelta,
                unloadedDelta,
                elapsedNanos);
        System.out.print(report);
        String reportPath = System.getProperty(
                "delosdb.compiler.classFileAuthorityCandidate.report");
        if (reportPath != null && !reportPath.isBlank()) {
            Path target = Path.of(reportPath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, report);
        }
    }

    public static int doubleValue(int value) {
        return value * 2;
    }

    private static void shutdownDatabase() throws SQLException {
        try {
            DriverManager.getConnection(DATABASE + ";shutdown=true");
            fail("memory database shutdown must raise 08006");
        } catch (SQLException expected) {
            if (!"08006".equals(expected.getSQLState())) {
                throw expected;
            }
        }
    }
}
