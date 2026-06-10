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

import org.apache.derby.catalog.IndexDescriptor;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Verifies that DelosDB CREATE INDEX provider metadata is visible through
 * Derby's existing catalog descriptor object after real SQL execution and that
 * indexed predicates continue to execute through Derby-compatible costing.
 *
 * <p>This smoke test intentionally uses only JDBC and the public catalog
 * descriptor API. It must run from the Derby-compatible runtime jar set
 * without requiring delosdb-spi.jar or provider adapter classes on the
 * runtime classpath.</p>
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
            statement.executeUpdate("insert into idx_provider_smoke values (1, 'alpha', 10)");
            statement.executeUpdate("insert into idx_provider_smoke values (2, 'beta', 20)");
            statement.executeUpdate("insert into idx_provider_smoke values (3, 'gamma', 30)");

            assertProvider(connection, "IDX_PROVIDER_DEFAULT_IDX", DEFAULT_PROVIDER);
            assertProvider(connection, "IDX_PROVIDER_EXPLICIT_IDX", DEFAULT_PROVIDER);
            assertSingleId(connection,
                    "select id from idx_provider_smoke where name = ?",
                    "beta",
                    2,
                    "default-provider indexed predicate");
            assertSingleId(connection,
                    "select id from idx_provider_smoke where code = ?",
                    30,
                    3,
                    "explicit-provider indexed predicate");

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

    private static void assertSingleId(
            Connection connection,
            String sql,
            Object value,
            int expectedId,
            String label)
            throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setObject(1, value);
            try (ResultSet results = statement.executeQuery())
            {
                if (!results.next())
                {
                    throw new IllegalStateException("No row returned for " + label);
                }

                int actualId = results.getInt(1);
                if (actualId != expectedId)
                {
                    throw new IllegalStateException(
                            label + " expected id " + expectedId + " but was " + actualId);
                }

                if (results.next())
                {
                    throw new IllegalStateException("More than one row returned for " + label);
                }
            }
        }
    }

    private static String indexProviderName(Object descriptor)
    {
        if (!(descriptor instanceof IndexDescriptor indexDescriptor))
        {
            throw new IllegalStateException(
                    "Descriptor " + descriptor.getClass().getName()
                            + " is not an IndexDescriptor");
        }
        return indexDescriptor.indexProviderName();
    }

    private static void shutdown(String databasePath) throws SQLException
    {
        try
        {
            DriverManager.getConnection("jdbc:derby:" + databasePath + ";shutdown=true").close();
        }
        catch (SQLException expected)
        {
            if ("08006".equals(expected.getSQLState()))
            {
                return;
            }

            // The metadata smoke has already opened the embedded database and
            // verified the catalog descriptor. Some rapid Gradle smoke runs can
            // leave DriverManager without a registered embedded driver during
            // the best-effort shutdown cleanup path. Do not turn that cleanup
            // condition into a false smoke failure.
            String message = expected.getMessage();
            if (message != null && message.contains("No suitable driver"))
            {
                return;
            }

            throw expected;
        }
    }
}
