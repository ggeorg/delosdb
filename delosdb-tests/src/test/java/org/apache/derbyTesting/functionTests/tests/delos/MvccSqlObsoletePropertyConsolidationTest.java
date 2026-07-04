/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlObsoletePropertyConsolidationTest

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

import java.lang.reflect.Field;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import org.apache.derby.impl.sql.execute.DelosObsoleteStorageProperties;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

/** Gate that keeps obsolete proof-era properties out of active scan routing. */
public final class MvccSqlObsoletePropertyConsolidationTest extends MvccSqlTestSupport {
    public void testObsoleteNativeMvccPropertiesDoNotRouteSqlExecution() throws Exception {
        List<SystemPropertyScope> scopes = setAllObsoleteStorageProperties();
        try {
            assertFalse("obsolete MVCC proof properties must not enable legacy routing",
                    DelosTableScanProviderLookup.legacyNativeMvccCrudProofRoutesEnabledForTesting());

            String databaseName = databaseName("mvcc-sql-obsolete-properties-db");
            try (Connection connection = openDatabase(databaseName, true)) {
                executeUpdate(connection, "create table obsolete_property_t "
                        + "(id int primary key, name varchar(20)) using delos_mvcc");
                assertEquals(1, executeUpdate(connection,
                        "insert into obsolete_property_t values (1, 'alpha')"));
                assertRows(connection,
                        "select id, name from obsolete_property_t order by id",
                        "1|alpha");
                assertEquals(1, executeUpdate(connection,
                        "update obsolete_property_t set name = 'beta' where id = 1"));
                assertRows(connection,
                        "select id, name from obsolete_property_t order by id",
                        "1|beta");
                assertEquals(1, executeUpdate(connection,
                        "delete from obsolete_property_t where id = 1"));
                assertRows(connection,
                        "select id, name from obsolete_property_t order by id");
            }
        } finally {
            closeReverse(scopes);
        }
    }

    public void testObsoletePropertyNamesAreNotPublishedByActiveProviderLookup() {
        assertDoesNotExposeObsoleteField("FACTORY_SKELETON_BRANCH_PROPERTY");
        assertDoesNotExposeObsoleteField("FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY");
        assertDoesNotExposeObsoleteField("FACTORY_NATIVE_RANGE_PREDICATES_PROPERTY");
        assertDoesNotExposeObsoleteField("FACTORY_NATIVE_BETWEEN_PREDICATES_PROPERTY");
        assertDoesNotExposeObsoleteField("FACTORY_NATIVE_NULL_PREDICATES_PROPERTY");
        assertDoesNotExposeObsoleteField("FACTORY_NATIVE_OR_PREDICATES_PROPERTY");
        assertDoesNotExposeObsoleteField("FACTORY_NATIVE_PROJECTION_VARIANTS_PROPERTY");
        assertDoesNotExposeObsoleteField("FACTORY_NATIVE_ORDER_BY_RESIDUAL_PROPERTY");
        assertDoesNotExposeObsoleteField("FACTORY_NATIVE_SELECT_ALL_PROPERTY");
        assertDoesNotExposeObsoleteField("FACTORY_NATIVE_COUNT_AGGREGATE_PROPERTY");
        assertDoesNotExposeObsoleteField("FACTORY_NATIVE_INSERT_PROPERTY");
        assertDoesNotExposeObsoleteField("FACTORY_NATIVE_DELETE_EQUALITY_PROPERTY");
        assertDoesNotExposeObsoleteField("FACTORY_NATIVE_UPDATE_EQUALITY_PROPERTY");
    }

    private static List<SystemPropertyScope> setAllObsoleteStorageProperties() {
        List<SystemPropertyScope> scopes = new ArrayList<>();
        for (String propertyName : DelosObsoleteStorageProperties.all()) {
            scopes.add(setSystemProperty(propertyName, "true"));
        }
        return scopes;
    }

    private static void closeReverse(List<SystemPropertyScope> scopes) throws Exception {
        Exception first = null;
        for (int i = scopes.size() - 1; i >= 0; i--) {
            try {
                scopes.get(i).close();
            } catch (Exception e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    private static void assertDoesNotExposeObsoleteField(String fieldName) {
        try {
            Field field = DelosTableScanProviderLookup.class.getDeclaredField(fieldName);
            fail("obsolete property field should live only in DelosObsoleteStorageProperties: " + field);
        } catch (NoSuchFieldException expected) {
            // Expected: active provider lookup exposes only live routing properties.
        }
    }
}
