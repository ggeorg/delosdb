/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.StorageConsolidationBoundaryTest

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
import java.lang.reflect.Method;

import org.apache.derby.iapi.store.types.DelosStorageCandidateIndex;
import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsContext;

/** Consolidation gate for stale storage-boundary compatibility aliases. */
public final class StorageConsolidationBoundaryTest extends MvccSqlTestSupport {
    public void testStorageBoundaryCleanupRemovesStaleAliasesFromNewAndInheritedPaths() throws Exception {
        assertNoPublicMethod(DelosStorageCandidateIndex.class, "orderedIndexCandidateRowIdsFor");
        assertNoPublicMethod(DelosStorageCandidateIndex.class, "orderedIndexCandidateRowIdsInRangeFor");
        assertHasPublicMethod(DelosStorageCandidateIndex.class, "orderedIndexRowIdsFor",
                int.class, String.class);
        assertHasPublicMethod(DelosStorageCandidateIndex.class, "orderedIndexRowIdsInRangeFor",
                int.class, String.class, boolean.class, String.class, boolean.class);

        assertNoPublicMethod(DelosStorageDiagnostics.class, "setDatabaseDirectoryForTesting");
        assertNoPublicMethod(DelosStorageDiagnostics.class, "clearDatabaseDirectoryForTesting");
        assertHasPublicMethod(DelosStorageDiagnostics.class, "withContext",
                DelosStorageDiagnosticsContext.class);

        Class<?> heapDiagnostics = Class.forName(
                "org.apache.derby.impl.store.access.provider.DerbyHeapStorageDiagnostics");
        assertHasPublicMethod(heapDiagnostics, "withContext", DelosStorageDiagnosticsContext.class);
        assertNoDeclaredField(heapDiagnostics, "databaseDirectoryForTesting");
    }

    private static void assertNoPublicMethod(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            assertFalse("stale public method should be removed from " + type.getName() + ": " + name,
                    name.equals(method.getName()));
        }
    }

    private static void assertHasPublicMethod(Class<?> type, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = type.getMethod(name, parameterTypes);
        assertEquals("unexpected reflected method name", name, method.getName());
    }

    private static void assertNoDeclaredField(Class<?> type, String name) {
        for (Field field : type.getDeclaredFields()) {
            assertFalse("stale mutable diagnostics field should be removed from " + type.getName(),
                    name.equals(field.getName()));
        }
    }
}
