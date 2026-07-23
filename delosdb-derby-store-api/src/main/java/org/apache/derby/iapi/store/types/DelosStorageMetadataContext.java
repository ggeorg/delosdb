/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageMetadataContext

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

import java.util.Objects;

/**
 * Explicit context for read-only DelosDB storage metadata queries.
 *
 * <p>The context is deliberately separate from execution state.  It lets tests
 * and future callers describe why metadata is being read without turning a
 * capability/cost snapshot into an optimizer or scan-routing instruction.</p>
 */
public record DelosStorageMetadataContext(Purpose purpose,
                                          boolean readOnlyRequired,
                                          boolean optimizerConsumptionAllowed,
                                          boolean executionRoutingAllowed) {
    public enum Purpose {
        METADATA_SNAPSHOT,
        INSPECTION_REPORT,
        CONSISTENCY_REPORT,
        STATISTICS_REPORT,
        COST_REPORT,
        CAPABILITIES_REPORT,
        PREDICATE_PUSHDOWN_PLANNING,
        OPTIMIZER_REVIEW
    }

    public DelosStorageMetadataContext {
        purpose = Objects.requireNonNull(purpose, "purpose");
        if (!readOnlyRequired) {
            throw new IllegalArgumentException("storage metadata context must be read-only");
        }
        if (optimizerConsumptionAllowed) {
            throw new IllegalArgumentException(
                    "storage metadata context must not enable Derby optimizer consumption");
        }
        if (executionRoutingAllowed) {
            throw new IllegalArgumentException(
                    "storage metadata context must not enable execution routing");
        }
    }

    public static DelosStorageMetadataContext snapshot() {
        return readOnly(Purpose.METADATA_SNAPSHOT);
    }

    public static DelosStorageMetadataContext inspectionReport() {
        return readOnly(Purpose.INSPECTION_REPORT);
    }

    public static DelosStorageMetadataContext consistencyReport() {
        return readOnly(Purpose.CONSISTENCY_REPORT);
    }

    public static DelosStorageMetadataContext statisticsReport() {
        return readOnly(Purpose.STATISTICS_REPORT);
    }

    public static DelosStorageMetadataContext costReport() {
        return readOnly(Purpose.COST_REPORT);
    }

    public static DelosStorageMetadataContext capabilitiesReport() {
        return readOnly(Purpose.CAPABILITIES_REPORT);
    }

    public static DelosStorageMetadataContext predicatePushdownPlanning() {
        return readOnly(Purpose.PREDICATE_PUSHDOWN_PLANNING);
    }

    public static DelosStorageMetadataContext optimizerReview() {
        return readOnly(Purpose.OPTIMIZER_REVIEW);
    }

    public static DelosStorageMetadataContext readOnly(Purpose purpose) {
        return new DelosStorageMetadataContext(purpose, true, false, false);
    }

    public boolean optimizerSafe() {
        return readOnlyRequired && !optimizerConsumptionAllowed && !executionRoutingAllowed;
    }

    public String summary() {
        return purpose
                + " readOnly=" + readOnlyRequired
                + " optimizerConsumption=" + optimizerConsumptionAllowed
                + " executionRouting=" + executionRoutingAllowed;
    }
}
