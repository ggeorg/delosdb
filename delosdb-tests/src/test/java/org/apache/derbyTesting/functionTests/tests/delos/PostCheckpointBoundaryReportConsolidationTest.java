/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.PostCheckpointBoundaryReportConsolidationTest

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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import junit.framework.TestCase;

/** Gate that keeps the post-checkpoint boundary report wired into closeout verification. */
public final class PostCheckpointBoundaryReportConsolidationTest extends TestCase {
    public void testPostCheckpointBoundaryReportIsWiredIntoCloseoutVerification() throws Exception {
        Path staticAnalysisScript = storageStaticAnalysisScript();
        assertTrue("storage static-analysis script should exist", Files.exists(staticAnalysisScript));

        String script = Files.readString(staticAnalysisScript, StandardCharsets.UTF_8);
        assertTrue("post-checkpoint boundary report task should exist",
                script.contains("tasks.register('delosPostCheckpointBoundaryReport')"));
        assertTrue("post-checkpoint boundary report should be part of S0 closeout",
                script.contains("'delosPostCheckpointBoundaryReport'"));
        assertTrue("report should track stale ordered-index authority aliases",
                script.contains("orderedIndexCandidateRowIds"));
        assertTrue("report should track mutable diagnostics context hooks",
                script.contains("setDatabaseDirectoryForTesting"));
        assertTrue("report should track optimizer/storage opt-in properties",
                script.contains("delosdb\\\\.(?:optimizer|storage)"));
        assertTrue("report should track inherited/new-code boundary imports",
                script.contains("heap -> MVCC implementation imports"));

        assertTrue("diagnostics API surface report task should exist",
                script.contains("tasks.register('delosDiagnosticsApiSurfaceReport')"));
        assertTrue("diagnostics API surface report should be part of S0 closeout",
                script.contains("'delosDiagnosticsApiSurfaceReport'"));
        assertTrue("diagnostics API surface report should classify stable diagnostics candidates",
                script.contains("stable diagnostics candidates"));
        assertTrue("diagnostics API surface report should classify reset/state hooks",
                script.contains("reset/state-hook"));
        assertTrue("diagnostics API surface report should classify legacy checkpoint vocabulary",
                script.contains("legacy proof/checkpoint vocabulary"));

        assertTrue("full-tree ownership report task should exist",
                script.contains("tasks.register('delosFullTreeOwnershipReport')"));
        assertTrue("full-tree ownership report should be part of S0 closeout",
                script.contains("'delosFullTreeOwnershipReport'"));
        assertTrue("full-tree ownership report should cover both DelosDB namespaces as one codebase",
                script.contains("org.apache.derby and io.github.ggeorg code as one DelosDB-owned codebase"));
        assertTrue("full-tree ownership report should emit clone-partner hints",
                script.contains("Clone-partner hints for active seam files"));
        assertTrue("full-tree ownership report should classify reflective-risk dead-code candidates",
                script.contains("REFLECTIVE_DERBY_DIAGNOSTICABLE_KEEP_OR_KILL_DECISION"));
    }

    private static Path storageStaticAnalysisScript() {
        Path direct = Path.of("gradle", "delosdb-storage-static-analysis.gradle");
        if (Files.exists(direct)) {
            return direct;
        }
        Path fromFocusedTestWorkDir = Path.of(
                "..", "..", "..", "..",
                "gradle",
                "delosdb-storage-static-analysis.gradle").normalize();
        if (Files.exists(fromFocusedTestWorkDir)) {
            return fromFocusedTestWorkDir;
        }
        return direct;
    }
}
