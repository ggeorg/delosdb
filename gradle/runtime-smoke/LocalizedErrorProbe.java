/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Prints an engine error message in the JVM default locale given as the first
 * argument, so localeDiscoverySmoke can check which translation was found.
 */
public final class LocalizedErrorProbe {
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.forLanguageTag(args[0]));
        try (Connection connection = DriverManager.getConnection("jdbc:derby:memory:probe;create=true")) {
            connection.createStatement().executeQuery("select * from no_such_table");
            throw new IllegalStateException("the query on a missing table succeeded");
        } catch (SQLException expected) {
            System.out.println(expected.getSQLState() + " " + expected.getMessage());
        }
    }
}
