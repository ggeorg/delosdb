/*

   Derby - Class org.apache.derby.iapi.store.types.DelosCrossEngineConsistencyReport

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
package org.apache.derby.iapi.store.types;

import java.util.List;
import java.util.Objects;

/**
 * Read-only consistency snapshot spanning heap and MVCC storage targets.
 *
 * <p>The report is an aggregation boundary only.  It does not introduce a new
 * storage format and it does not make heap depend on MVCC or MVCC depend on
 * heap.</p>
 */
public record DelosCrossEngineConsistencyReport(List<DelosStorageConsistencyFinding> findings) {
    public DelosCrossEngineConsistencyReport {
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        if (findings.isEmpty()) {
            throw new IllegalArgumentException("consistency report must contain at least one finding");
        }
    }

    public int targetCount() {
        return findings.size();
    }

    public int errorCount() {
        int total = 0;
        for (DelosStorageConsistencyFinding finding : findings) {
            total += finding.errorCount();
        }
        return total;
    }

    public boolean clean() {
        return errorCount() == 0;
    }

    public List<DelosStorageConsistencyFinding> failedFindings() {
        return findings.stream().filter(finding -> !finding.clean()).toList();
    }

    public List<String> summaries() {
        return findings.stream().map(DelosStorageConsistencyFinding::summary).toList();
    }

    public DelosStorageConsistencyFinding finding(String providerId, int segment, long containerId) {
        String normalizedProvider = Objects.requireNonNull(providerId, "providerId").trim();
        return findings.stream()
                .filter(finding -> finding.providerId().equalsIgnoreCase(normalizedProvider)
                        && finding.segment() == segment
                        && finding.containerId() == containerId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No consistency finding for " + providerId + " " + segment + ":" + containerId));
    }
}
