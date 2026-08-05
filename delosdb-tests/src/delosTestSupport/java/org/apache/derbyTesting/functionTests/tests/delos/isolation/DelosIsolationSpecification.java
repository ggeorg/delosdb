/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.isolation.DelosIsolationSpecification

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

package org.apache.derbyTesting.functionTests.tests.delos.isolation;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable DelosDB-owned isolation-specification model. */
public record DelosIsolationSpecification(
        String id,
        String description,
        String category,
        Set<Provider> providers,
        Set<Storage> storages,
        Set<ConnectionMode> connections,
        List<String> setup,
        Map<String, Session> sessions,
        List<Permutation> permutations,
        List<QueryAssertion> finalAssertions,
        List<String> teardown) {

    public enum Provider {
        HEAP,
        MVCC;

        public static Provider parse(String value) {
            return valueOf(value.trim().toUpperCase());
        }

        public String tableClause() {
            return this == MVCC ? " using delos_mvcc" : "";
        }
    }

    public enum Storage {
        FILE,
        MEMORY;

        public static Storage parse(String value) {
            return valueOf(value.trim().toUpperCase());
        }
    }

    public enum ConnectionMode {
        EMBEDDED;

        public static ConnectionMode parse(String value) {
            return valueOf(value.trim().toUpperCase());
        }
    }

    public enum Action {
        SQL,
        COMMIT,
        ROLLBACK,
        SAVEPOINT,
        ROLLBACK_TO_SAVEPOINT,
        RELEASE_SAVEPOINT;

        public static Action parse(String value) {
            return valueOf(value.trim().toUpperCase());
        }
    }

    public enum OperationType {
        RUN,
        START,
        ASSERT_BLOCKED,
        AWAIT,
        DRAIN_AND_COMMIT;

        public static OperationType parse(String value) {
            return valueOf(value.trim().toUpperCase());
        }
    }

    public record Session(
            String name,
            Map<Provider, Integer> isolationByProvider,
            int defaultIsolation,
            Map<String, Step> steps) {

        public int isolation(Provider provider) {
            return isolationByProvider.getOrDefault(provider, defaultIsolation);
        }
    }

    public record Step(
            String name,
            Action action,
            String sql,
            String savepoint,
            List<String> expectedRows,
            boolean rowsDeclared,
            Integer expectedUpdateCount,
            Set<String> acceptedSqlStates,
            boolean successAllowed) {
    }

    public record Permutation(
            String name,
            Set<Provider> providers,
            Set<Storage> storages,
            List<Operation> operations,
            List<SqlStateAssertion> sqlStateAssertions) {

        public boolean appliesTo(Provider provider, Storage storage) {
            return (providers.isEmpty() || providers.contains(provider))
                    && (storages.isEmpty() || storages.contains(storage));
        }
    }

    public record Operation(
            OperationType type,
            String step,
            String token,
            long timeoutMillis) {
    }

    public record SqlStateAssertion(String sqlState, int minimum, int maximum) {
    }

    public record QueryAssertion(String sql, List<String> rows) {
    }
}
