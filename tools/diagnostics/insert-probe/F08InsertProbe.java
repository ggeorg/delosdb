/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;

/**
 * Embedded F08 INSERT_100 probe, independent of the benchmark harness.
 *
 * <p>Usage: {@code F08InsertProbe <jdbc-url> <clients> <rows-per-client> <bare|pk>}.
 * Each client inserts its own ascending id range (128-byte payload) and commits
 * every 100 rows. Prints throughput, then verifies the committed contents.
 */
public final class F08InsertProbe {
    private F08InsertProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !(args[3].equals("bare") || args[3].equals("pk"))) {
            System.err.println("usage: F08InsertProbe <jdbc-url> <clients> <rows-per-client> <bare|pk>");
            System.exit(64);
        }
        String url = args[0];
        int clients = Integer.parseInt(args[1]);
        int rowsPerClient = Integer.parseInt(args[2]);
        String idColumn = args[3].equals("pk") ? "id int not null primary key" : "id int not null";
        String payload = "x".repeat(128);

        try (Connection connection = DriverManager.getConnection(url + ";create=true");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table t (" + idColumn + ", category int not null,"
                    + " bucket int not null, quantity int not null, payload varchar(4096) not null)");
        }

        CyclicBarrier start = new CyclicBarrier(clients + 1);
        List<Thread> writers = new ArrayList<>();
        for (int client = 0; client < clients; client++) {
            int base = client * rowsPerClient + 1;
            Thread writer = new Thread(() -> {
                try (Connection connection = DriverManager.getConnection(url);
                     PreparedStatement insert = connection.prepareStatement(
                             "insert into t (id, category, bucket, quantity, payload) values (?, ?, ?, ?, ?)")) {
                    connection.setAutoCommit(false);
                    start.await();
                    for (int row = 0; row < rowsPerClient; row++) {
                        int id = base + row;
                        insert.setInt(1, id);
                        insert.setInt(2, id % 97);
                        insert.setInt(3, id % 1013);
                        insert.setInt(4, id % 7);
                        insert.setString(5, payload);
                        insert.executeUpdate();
                        if ((row + 1) % 100 == 0) {
                            connection.commit();
                        }
                    }
                    connection.commit();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "writer-" + client);
            writers.add(writer);
            writer.start();
        }

        start.await();
        long started = System.nanoTime();
        for (Thread writer : writers) {
            writer.join();
        }
        long elapsed = System.nanoTime() - started;
        long rows = (long) clients * rowsPerClient;
        System.out.printf("rows=%d seconds=%.3f rows_per_second=%.0f%n",
                rows, elapsed / 1e9, rows * 1e9 / elapsed);

        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "select count(*), count(distinct id), min(id), max(id) from t")) {
            result.next();
            boolean ok = result.getLong(1) == rows && result.getLong(2) == rows
                    && result.getLong(3) == 1 && result.getLong(4) == rows;
            System.out.printf("verify count=%d distinct=%d min=%d max=%d %s%n", result.getLong(1),
                    result.getLong(2), result.getLong(3), result.getLong(4), ok ? "OK" : "MISMATCH");
            if (!ok) {
                System.exit(1);
            }
        }
        try {
            DriverManager.getConnection(url + ";shutdown=true");
        } catch (SQLException expected) {
            // Derby reports a successful database shutdown as an exception.
        }
    }
}
