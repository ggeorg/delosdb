/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageOptimizerReviewReport

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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Read-only pre-optimizer review report for DelosDB storage metadata.
 *
 * <p>This is the boundary review before Derby optimizer integration.  It
 * verifies that metadata, statistics, cost estimates, capabilities, and
 * predicate-pushdown plans remain proof-only and optimizer-neutral.  A clean
 * report means it is safe to start a later <em>opt-in</em> optimizer experiment;
 * it does not mean Derby consumes these estimates yet.</p>
 */
public record DelosStorageOptimizerReviewReport(
        List<DelosStorageMetadataSnapshot> snapshots,
        DelosStoragePredicatePushdownReport predicatePushdownReport,
        List<String> observations) {
    public DelosStorageOptimizerReviewReport {
        snapshots = List.copyOf(Objects.requireNonNull(snapshots, "snapshots"));
        predicatePushdownReport = Objects.requireNonNull(predicatePushdownReport, "predicatePushdownReport");
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        if (snapshots.isEmpty()) {
            throw new IllegalArgumentException("optimizer review report must contain at least one metadata snapshot");
        }
    }

    public static DelosStorageOptimizerReviewReport from(
            List<DelosStorageMetadataSnapshot> snapshots,
            DelosStoragePredicatePushdownReport predicatePushdownReport) {
        Objects.requireNonNull(snapshots, "snapshots");
        Objects.requireNonNull(predicatePushdownReport, "predicatePushdownReport");
        List<String> observations = new ArrayList<>();
        observations.add("optimizer review report is read-only");
        observations.add("storage metadata remains provider-chain based");
        observations.add("cost estimates remain proof-only");
        observations.add("predicate pushdown remains metadata-only");
        observations.add("Derby optimizer consumption remains disabled");
        return new DelosStorageOptimizerReviewReport(snapshots, predicatePushdownReport, observations);
    }

    public int targetCount() {
        return snapshots.size();
    }

    public int predicatePlanCount() {
        return predicatePushdownReport.targetCount();
    }

    public boolean readOnly() {
        if (!predicatePushdownReport.readOnly()) {
            return false;
        }
        for (DelosStorageMetadataSnapshot snapshot : snapshots) {
            if (!snapshot.readOnly()) {
                return false;
            }
        }
        return true;
    }

    public boolean optimizerNeutral() {
        if (predicatePushdownReport.consumedByDerbyOptimizer()) {
            return false;
        }
        for (DelosStorageMetadataSnapshot snapshot : snapshots) {
            if (snapshot.costEstimate().consumedByDerbyOptimizer()
                    || snapshot.capabilities().consumedByDerbyOptimizer()) {
                return false;
            }
        }
        return true;
    }

    public boolean costEstimatesProofOnly() {
        for (DelosStorageMetadataSnapshot snapshot : snapshots) {
            if (!snapshot.costEstimate().proofOnly()) {
                return false;
            }
        }
        return true;
    }

    public boolean storageStatisticsAvailable() {
        for (DelosStorageMetadataSnapshot snapshot : snapshots) {
            if (!snapshot.statistics().readOnly()
                    || !snapshot.capabilities().supportsStorageStatistics()) {
                return false;
            }
        }
        return true;
    }

    public boolean hasMvccOrderedAccessAuthority() {
        for (DelosStorageMetadataSnapshot snapshot : snapshots) {
            DelosStorageCapabilities capabilities = snapshot.capabilities();
            if (DelosStorageProviderIds.isMvcc(snapshot.providerId())
                    && capabilities.supportsRowIdLookup()
                    && capabilities.supportsOrderedEqualityLookup()
                    && capabilities.supportsOrderedRangeScan()
                    && capabilities.supportsStableKeyOrder()
                    && capabilities.supportsCurrentCommittedShortcut()
                    && capabilities.candidateIndexAuthorityRemoved()) {
                return true;
            }
        }
        return false;
    }

    public boolean snapshotShortcutsStillDisabled() {
        for (DelosStorageMetadataSnapshot snapshot : snapshots) {
            if (snapshot.capabilities().supportsSnapshotShortcut()) {
                return false;
            }
        }
        for (DelosStoragePredicatePushdown plan : predicatePushdownReport.plans()) {
            if (plan.safeForSnapshotShortcut()) {
                return false;
            }
            if (!"current-committed".equals(plan.readMode()) && plan.pushedToStorage()) {
                return false;
            }
        }
        return true;
    }

    public boolean predicatePushdownSafeForReview() {
        if (predicatePushdownReport.consumedByDerbyOptimizer()) {
            return false;
        }
        for (DelosStoragePredicatePushdown plan : predicatePushdownReport.plans()) {
            if (!plan.readOnly()) {
                return false;
            }
            if (plan.pushedToStorage()
                    && (!plan.safeForCurrentCommittedShortcut()
                    || plan.safeForSnapshotShortcut()
                    || !"current-committed".equals(plan.readMode()))) {
                return false;
            }
        }
        return true;
    }

    public List<String> blockingIssues() {
        List<String> issues = new ArrayList<>();
        if (!readOnly()) {
            issues.add("metadata review contains a non-read-only component");
        }
        if (!optimizerNeutral()) {
            issues.add("metadata review contains optimizer-consumed metadata");
        }
        if (!costEstimatesProofOnly()) {
            issues.add("storage cost estimates are not proof-only");
        }
        if (!storageStatisticsAvailable()) {
            issues.add("storage statistics are missing or not read-only");
        }
        if (!hasMvccOrderedAccessAuthority()) {
            issues.add("MVCC ordered access authority is not represented in capabilities");
        }
        if (!snapshotShortcutsStillDisabled()) {
            issues.add("snapshot or non-current-committed shortcuts are exposed too early");
        }
        if (!predicatePushdownSafeForReview()) {
            issues.add("predicate pushdown model is not safe for optimizer review");
        }
        return List.copyOf(issues);
    }

    public boolean readyForOptInOptimizerIntegration() {
        return blockingIssues().isEmpty();
    }

    public String summary() {
        return "targets=" + targetCount()
                + " predicatePlans=" + predicatePlanCount()
                + " readOnly=" + readOnly()
                + " optimizerNeutral=" + optimizerNeutral()
                + " proofOnlyCosts=" + costEstimatesProofOnly()
                + " mvccOrderedAuthority=" + hasMvccOrderedAccessAuthority()
                + " snapshotShortcutsDisabled=" + snapshotShortcutsStillDisabled()
                + " readyForOptInOptimizerIntegration=" + readyForOptInOptimizerIntegration();
    }
}
