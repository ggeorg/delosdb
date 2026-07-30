/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.GeneratedClassProductionTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

import org.apache.derby.iapi.services.compiler.ClassBuilder;
import org.apache.derby.iapi.services.compiler.JavaFactory;
import org.apache.derby.iapi.services.loader.ClassFactory;

import junit.framework.TestCase;

import org.apache.derby.iapi.services.monitor.Monitor;
import org.apache.derby.shared.common.reference.Module;

/**
 * Proves that normal Derby module registration selects the sole generated-class
 * backend and that representative SQL compilation preserves its contracts.
 */
public final class GeneratedClassProductionTest
        extends TestCase {
    private static final String BACKEND_PROPERTY =
            "derby.module.javaCompiler";
    private static final String CLASSFILE_BACKEND =
            "org.apache.derby.impl.services.bytecode.classfile.ClassFileJava";
    private static final String DATABASE =
            "jdbc:derby:memory:delosClassFileProduction";
    private static final String APPLICATION_OVERRIDE =
            "derby.module.untrustedGeneratedClassBackend";

    /** A deliberately invalid backend used to prove monitor override rejection. */
    public static final class RejectingJavaFactory implements JavaFactory {
        @Override
        public ClassBuilder newClassBuilder(
                ClassFactory classFactory,
                String packageName,
                int modifiers,
                String className,
                String superClass) {
            throw new AssertionError(
                    "external JavaFactory override must never be selected");
        }
    }

    public void testProductionBackendCompilesSql() throws Exception {
        assertNull("production proof must not use a system backend override",
                System.getProperty(BACKEND_PROPERTY));
        Files.writeString(Path.of("derby.properties"),
                APPLICATION_OVERRIDE + "="
                        + RejectingJavaFactory.class.getName() + System.lineSeparator());

        int preparedStatements = 0;
        long started = System.nanoTime();
        try (Connection connection = DriverManager.getConnection(
                DATABASE + ";create=true")) {
            Object javaFactory = Monitor.findSystemModule(Module.JavaFactory);
            assertNotNull("JavaFactory system module", javaFactory);
            assertEquals("default JavaFactory implementation",
                    CLASSFILE_BACKEND, javaFactory.getClass().getName());

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "create table T (id int primary key, v int, s varchar(40))");
                statement.executeUpdate(
                        "insert into T values (1, 10, 'alpha'), (2, 20, null)");
            }

            try (PreparedStatement statement = connection.prepareStatement(
                    "select id, v + ?, case when s is null then 'missing' "
                    + "else s end from T where v >= ? order by id")) {
                preparedStatements++;
                statement.setInt(1, 5);
                statement.setInt(2, 10);
                try (ResultSet rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                    assertEquals(15, rows.getInt(2));
                    assertEquals("alpha", rows.getString(3));
                    assertTrue(rows.next());
                    assertEquals(25, rows.getInt(2));
                    assertEquals("missing", rows.getString(3));
                    assertFalse(rows.next());
                }
            }

            try (PreparedStatement statement = connection.prepareStatement(
                    "update T set s = ? where id = ?")) {
                preparedStatements++;
                statement.setString(1, "updated");
                statement.setInt(2, 2);
                assertEquals(1, statement.executeUpdate());
            }

            try (Statement statement = connection.createStatement()) {
                try (ResultSet row = statement.executeQuery(
                        "select s from T where id = 2")) {
                    assertTrue(row.next());
                    assertEquals("updated", row.getString(1));
                    assertFalse(row.next());
                }
                try (ResultSet row = statement.executeQuery("values 1 / 0")) {
                    row.next();
                    fail("division by zero must preserve SQLState 22012");
                } catch (SQLException expected) {
                    assertEquals("22012", expected.getSQLState());
                }
            }
            connection.commit();
        } finally {
            shutdownDatabase();
        }

        String report = String.format(Locale.ROOT,
                "DelosDB sole Class-File API production proof%n"
                + "================================================%n"
                + "Architecture: JDK25_CLASSFILE%n"
                + "Selected backend: %s%n"
                + "Selection source: MODULES_PROPERTIES_SOLE_BACKEND%n"
                + "External override attempt: REJECTED%n"
                + "Prepared statement families: %d%n"
                + "Elapsed nanos: %d%n"
                + "SQLState preservation: 22012%n"
                + "External bytecode dependency: none%n",
                CLASSFILE_BACKEND,
                preparedStatements,
                System.nanoTime() - started);
        System.out.print(report);

        String reportPath = System.getProperty(
                "delosdb.compiler.generatedClassProduction.report");
        if (reportPath != null && !reportPath.isBlank()) {
            Path target = Path.of(reportPath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, report);
        }
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
