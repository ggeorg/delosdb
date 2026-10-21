/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** Boots the embedded engine resolved from Maven and runs heap and MVCC SQL. */
public final class ConsumerProbe {
    public static void main(String[] args) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:derby:memory:consumer;create=true");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table h (id int primary key, v varchar(10))");
            statement.executeUpdate("create table m (id int primary key, v varchar(10)) using delos_mvcc");
            statement.executeUpdate("insert into h values (1, 'heap'), (2, 'heap')");
            statement.executeUpdate("insert into m values (1, 'mvcc'), (2, 'mvcc')");
            try (ResultSet rows = statement.executeQuery(
                    "select count(*), sum(h.id * 10 + m.id) from h join m on h.id = m.id where m.v = 'mvcc'")) {
                rows.next();
                if (rows.getInt(1) != 2 || rows.getInt(2) != 33) {
                    throw new IllegalStateException("unexpected result " + rows.getInt(1) + ", " + rows.getInt(2));
                }
            }
            // The driver's module is null on the class path and the engine module on the module path.
            Class<?> driver = DriverManager.getDriver("jdbc:derby:memory:consumer").getClass();
            System.out.println("CONSUMER_OK module=" + driver.getModule().getName()
                    + " jar=" + driver.getProtectionDomain().getCodeSource().getLocation());
        }
    }
}
