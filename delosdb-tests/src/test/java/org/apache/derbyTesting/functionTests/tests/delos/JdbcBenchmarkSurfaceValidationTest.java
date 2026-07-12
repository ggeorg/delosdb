/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.JdbcBenchmarkSurfaceValidationTest

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

import java.nio.file.Path;

import io.github.ggeorg.delosdb.benchmark.jdbc.DelosJdbcBenchmarkSurfaceValidation;

/** Runs the deterministic JDBC benchmark surface against heap and MVCC. */
public final class JdbcBenchmarkSurfaceValidationTest extends MvccSqlTestSupport {
    private static final String DATABASE_ROOT = "jdbc-benchmark-surface-db";

    @Override
    protected void tearDown() throws Exception {
        deleteDatabase(DATABASE_ROOT + "-heap");
        deleteDatabase(DATABASE_ROOT + "-mvcc");
        super.tearDown();
    }

    public void testHeapAndMvccBenchmarkSurfacesRemainSemanticallyEquivalent() throws Exception {
        DelosJdbcBenchmarkSurfaceValidation.main(new String[] {Path.of(DATABASE_ROOT).toString()});
    }
}
