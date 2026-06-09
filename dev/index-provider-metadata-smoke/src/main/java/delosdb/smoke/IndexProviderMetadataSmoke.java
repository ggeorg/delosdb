/*

   Derby - Class delosdb.smoke.IndexProviderMetadataSmoke

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

package delosdb.smoke;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Verifies that DelosDB CREATE INDEX provider metadata is visible through
 * Derby's existing catalog descriptor object after real SQL execution.
 *
 * <p>This smoke test intentionally uses only JDBC and reflection. It must run
 * from the Derby-compatible runtime jar set without requiring delosdb-spi.jar
 * or provider adapter classes on the runtime classpath.</p>
 */
public final class IndexProviderMetadataSmoke
{
    private static final String DEFAULT_PROVIDER = "btree";

    private IndexProviderMetadataSmoke()
    {
    }

    public static void main(String[] args) throws Exception
    {
        if (args.length != 1)
        {
            throw new IllegalArgumentException("Expected database path argument");
        }

        String databasePath = args[0];
        String url = "jdbc:derby:" + databasePath + ";create=true";

        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement())
        {
            statement.executeUpdate("create table idx_provider_smoke(id int, name varchar(32), code int)");
            statement.executeUpdate("create index idx_provider_default_idx on idx_provider_smoke(name)");
            statement.executeUpdate("create index idx_provider_explicit_idx on idx_provider_smoke(code) using btree");

            assertProvider(connection, "IDX_PROVIDER_DEFAULT_IDX", DEFAULT_PROVIDER);
            assertProvider(connection, "IDX_PROVIDER_EXPLICIT_IDX", DEFAULT_PROVIDER);

            statement.executeUpdate("drop table idx_provider_smoke");
        }
        finally
        {
            shutdown(databasePath);
        }

        System.out.println("DelosDB CREATE INDEX provider metadata smoke test passed.");
    }

    private static void assertProvider(Connection connection, String indexName, String expectedProvider)
            throws Exception
    {
        String sql = "select descriptor from sys.sysconglomerates "
                + "where conglomeratename = ? and isindex = true";
        try (PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setString(1, indexName.toUpperCase(Locale.ROOT));
            try (ResultSet results = statement.executeQuery())
            {
                if (!results.next())
                {
                    throw new IllegalStateException("Missing index descriptor for " + indexName);
                }

                Object descriptor = results.getObject(1);
                if (descriptor == null)
                {
                    throw new IllegalStateException("Null descriptor for " + indexName);
                }

                String provider = indexProviderName(descriptor);
                if (!expectedProvider.equals(provider))
                {
                    throw new IllegalStateException(
                            "Index " + indexName + " provider expected " + expectedProvider
                                    + " but was " + provider);
                }

                if (results.next())
                {
                    throw new IllegalStateException("More than one descriptor found for " + indexName);
                }
            }
        }
    }

    private static String indexProviderName(Object descriptor) throws Exception
    {
        Method method = descriptor.getClass().getMethod("indexProviderName");
        Object value = method.invoke(descriptor);
        if (!(value instanceof String providerName))
        {
            throw new IllegalStateException(
                    "indexProviderName() on " + descriptor.getClass().getName()
                            + " did not return a String: " + value);
        }
        return providerName;
    }

    private static void shutdown(String databasePath) throws SQLException
    {
        try
        {
            DriverManager.getConnection("jdbc:derby:" + databasePath + ";shutdown=true").close();
        }
        catch (SQLException expected)
        {
            if (!"08006".equals(expected.getSQLState()))
            {
                throw expected;
            }
        }
    }
}
