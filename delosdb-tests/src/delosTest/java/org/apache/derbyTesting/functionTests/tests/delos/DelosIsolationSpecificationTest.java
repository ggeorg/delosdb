/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.DelosIsolationSpecificationTest

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

import org.apache.derbyTesting.functionTests.tests.delos.isolation.DelosIsolationSpecification;
import org.apache.derbyTesting.functionTests.tests.delos.isolation.DelosIsolationSpecificationLoader;

/** Complete DelosDB-owned PostgreSQL-style isolation specification catalogue. */
public final class DelosIsolationSpecificationTest extends MvccSqlTestSupport {
    private static final String RESOURCE_ROOT =
            "/org/apache/derbyTesting/functionTests/tests/delos/isolation/specs/";

    public void testSnapshotStabilitySpecifications() throws Exception {
        runAll("DEL-ISO-001", "DEL-ISO-002", "DEL-ISO-003", "DEL-ISO-004");
    }

    public void testSavepointSpecifications() throws Exception {
        runAll("DEL-ISO-010", "DEL-ISO-011", "DEL-ISO-012");
    }

    public void testDeadlockSpecifications() throws Exception {
        runAll("DEL-DEADLOCK-001", "DEL-DEADLOCK-002",
                "DEL-DEADLOCK-003", "DEL-DEADLOCK-004");
    }

    public void testUpdateDeleteTraversalSpecifications() throws Exception {
        runAll("DEL-TRAVERSAL-001", "DEL-TRAVERSAL-002",
                "DEL-TRAVERSAL-003", "DEL-TRAVERSAL-004");
    }

    public void testForeignKeyConcurrencySpecifications() throws Exception {
        runAll("DEL-FK-001", "DEL-FK-002", "DEL-FK-003", "DEL-FK-004");
    }

    public void testDdlConcurrencySpecifications() throws Exception {
        runAll("DEL-DDL-001", "DEL-DDL-002", "DEL-DDL-003", "DEL-DDL-004");
    }

    public void testMergeConcurrencySpecifications() throws Exception {
        runAll("DEL-MERGE-001", "DEL-MERGE-002");
    }

    public void testStage6BPostgresConcurrencySpecifications() throws Exception {
        runAll("DEL-ISO-013", "DEL-FK-005", "DEL-MERGE-003", "DEL-MERGE-004");
    }

    public void testStage6BPostgresSerializableUniquenessSpecification() throws Exception {
        runAll("DEL-ISO-014");
    }

    private static void runAll(String... caseIds) throws Exception {
        for (String caseId : caseIds) {
            String resource = RESOURCE_ROOT + caseId + ".json";
            DelosIsolationSpecification specification =
                    DelosIsolationSpecificationLoader.load(resource);
            try {
                int cases = DelosIsolationSpecificationRunner.run(specification);
                System.out.println(caseId + " passed " + cases + " matrix cases");
            } catch (Throwable failure) {
                throw new AssertionError("Isolation case failed: " + caseId, failure);
            }
        }
    }
}
