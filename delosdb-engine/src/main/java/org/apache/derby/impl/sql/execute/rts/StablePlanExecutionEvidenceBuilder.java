/*

   Derby - Class org.apache.derby.impl.sql.execute.rts.StablePlanExecutionEvidenceBuilder

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

package org.apache.derby.impl.sql.execute.rts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.derby.iapi.sql.compile.StablePlanModel;
import org.apache.derby.iapi.sql.execute.ResultSetStatistics;
import org.apache.derby.iapi.sql.execute.StablePlanExecutionEvidence;
import org.apache.derby.shared.common.i18n.MessageService;
import org.apache.derby.shared.common.reference.SQLState;

/** Builds bounded execution evidence from Derby's existing runtime-statistics tree. */
public final class StablePlanExecutionEvidenceBuilder {
    private StablePlanExecutionEvidenceBuilder() {}

    public static StablePlanExecutionEvidence build(
            StablePlanModel plan,
            int[] resultSetNumbers,
            ResultSetStatistics statistics,
            long rootRowsReturned) {
        Set<Integer> wantedResultSets = new HashSet<>();
        if (resultSetNumbers != null) {
            for (int resultSetNumber : resultSetNumbers) {
                if (resultSetNumber >= 0) wantedResultSets.add(resultSetNumber);
            }
        }
        Map<Integer, RealNoPutResultSetStatistics> runtimeNodes = new HashMap<>();
        index(statistics, wantedResultSets, runtimeNodes);

        List<StablePlanExecutionEvidence.Node> evidence = new ArrayList<>(plan.nodes().size());
        boolean truncated = plan.truncated() || resultSetNumbers == null
                || resultSetNumbers.length != plan.nodes().size();
        for (int i = 0; i < plan.nodes().size(); i++) {
            int resultSetNumber = resultSetNumbers != null && i < resultSetNumbers.length
                    ? resultSetNumbers[i]
                    : -1;
            RealNoPutResultSetStatistics runtime = runtimeNodes.get(resultSetNumber);
            if (resultSetNumber < 0 || runtime == null) {
                evidence.add(new StablePlanExecutionEvidence.Node(
                        plan.nodes().get(i).id(), false, 0, 0, 0, List.of()));
                continue;
            }
            evidence.add(new StablePlanExecutionEvidence.Node(
                    plan.nodes().get(i).id(),
                    true,
                    runtime.numOpens,
                    runtime.rowsSeen,
                    runtime.rowsFiltered,
                    storageMetrics(runtime)));
        }
        return new StablePlanExecutionEvidence(
                StablePlanExecutionEvidence.CURRENT_SCHEMA_VERSION,
                plan.statementId(),
                rootRowsReturned,
                evidence,
                truncated);
    }

    private static void index(
            ResultSetStatistics statistics,
            Set<Integer> wantedResultSets,
            Map<Integer, RealNoPutResultSetStatistics> runtimeNodes) {
        if (runtimeNodes.size() == wantedResultSets.size()
                || !(statistics instanceof RealBasicNoPutResultSetStatistics basic)) {
            return;
        }
        if (basic instanceof RealNoPutResultSetStatistics noPut
                && wantedResultSets.contains(noPut.resultSetNumber)) {
            runtimeNodes.putIfAbsent(noPut.resultSetNumber, noPut);
        }
        for (ResultSetStatistics child : basic.getChildren()) {
            index(child, wantedResultSets, runtimeNodes);
        }
    }

    private static List<StablePlanExecutionEvidence.Metric> storageMetrics(
            RealNoPutResultSetStatistics runtime) {
        Properties properties = runtime instanceof RealTableScanStatistics scan
                ? scan.scanProperties
                : runtime instanceof RealHashScanStatistics scan ? scan.scanProperties : null;
        if (properties == null) return List.of();
        List<StablePlanExecutionEvidence.Metric> result = new ArrayList<>(20);
        metric(result, properties, "PAGES_VISITED",
                key(SQLState.STORE_RTS_NUM_PAGES_VISITED));
        metric(result, properties, "ROWS_VISITED",
                key(SQLState.STORE_RTS_NUM_ROWS_VISITED), "numRowsVisited");
        metric(result, properties, "ROWS_QUALIFIED",
                key(SQLState.STORE_RTS_NUM_ROWS_QUALIFIED), "numRowsQualified");
        metric(result, properties, "DELETED_ROWS_VISITED",
                key(SQLState.STORE_RTS_NUM_DELETED_ROWS_VISITED));
        metric(result, properties, "TREE_HEIGHT", key(SQLState.STORE_RTS_TREE_HEIGHT));
        metric(result, properties, "MVCC_ORDERED_CANDIDATES", "mvccOrderedCandidates");
        metric(result, properties, "MVCC_COVERING_CANDIDATES", "mvccCoveringCandidates");
        metric(result, properties, "MVCC_COVERED_CANDIDATES", "mvccCoveredCandidates");
        metric(result, properties, "MVCC_FALLBACK_CANDIDATES", "mvccFallbackCandidates");
        metric(result, properties, "MVCC_DIRECTORY_PAGE_ACQUISITIONS",
                "mvccDirectoryPageAcquisitions");
        metric(result, properties, "MVCC_DIRECTORY_PAGE_BATCH_CANDIDATES",
                "mvccDirectoryPageBatchCandidates");
        metric(result, properties, "MVCC_DIRECTORY_PAGE_REUSE_HITS",
                "mvccDirectoryPageReuseHits");
        metric(result, properties, "MVCC_DIRECTORY_LOGICAL_FALLBACKS",
                "mvccDirectoryLogicalFallbacks");
        metric(result, properties, "MVCC_DIRECTORY_HEAD_SUMMARY_CHECKS",
                "mvccDirectoryHeadSummaryChecks");
        metric(result, properties, "MVCC_DIRECTORY_HEAD_SUMMARY_HITS",
                "mvccDirectoryHeadSummaryHits");
        metric(result, properties, "MVCC_DIRECTORY_HEAD_SUMMARY_FALLBACKS",
                "mvccDirectoryHeadSummaryFallbacks");
        metric(result, properties, "MVCC_VERSION_PAGE_ACQUISITIONS",
                "mvccVersionPageAcquisitions");
        metric(result, properties, "MVCC_VERSION_SLOT_FETCHES", "mvccVersionSlotFetches");
        metric(result, properties, "MVCC_VISIBILITY_CHECKS", "mvccVisibilityChecks");
        metric(result, properties, "MVCC_VERSION_CHAIN_STEPS", "mvccVersionChainSteps");
        metric(result, properties, "MVCC_VERSION_LOGICAL_FALLBACKS",
                "mvccVersionLogicalFallbacks");
        return result;
    }

    private static void metric(
            List<StablePlanExecutionEvidence.Metric> result,
            Properties properties,
            String name,
            String... keys) {
        for (String key : keys) {
            String value = properties.getProperty(key);
            if (value == null) continue;
            result.add(new StablePlanExecutionEvidence.Metric(name, Long.parseLong(value)));
            return;
        }
    }

    private static String key(String sqlState) {
        return MessageService.getTextMessage(sqlState);
    }
}
