/*

   Derby - Class org.apache.derby.impl.sql.execute.DelosObsoleteStorageProperties

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

package org.apache.derby.impl.sql.execute;

/**
 * Names of obsolete storage proof properties retained only for compatibility
 * with old smoke scripts and cleanup checks.
 *
 * <p>These names must not be used as live routing gates. Current Derby/DelosDB
 * execution routes from persisted storage-provider identity and the explicit
 * live heap/provider gates in {@link DelosTableScanProviderLookup}.</p>
 */
public final class DelosObsoleteStorageProperties
{
    /** Obsolete pre-MODULE6I MVCC table-scan proof gate name. */
    public static final String FACTORY_SKELETON_BRANCH_PROPERTY =
            "delosdb.storage.phaseF32.delosTableScanSkeleton";

    /** Obsolete pre-MODULE6I MVCC table-scan proof reached message. */
    public static final String FACTORY_SKELETON_REACHED_MESSAGE =
            "DelosTableScanResultSet skeleton reached; F4 must implement real MVCC scan materialization";

    /** Obsolete pre-MODULE6I MVCC SELECT equality proof gate name. */
    public static final String FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY =
            "delosdb.storage.phaseF4.nativeMvccSelectEquality";

    /** Obsolete pre-MODULE6I MVCC SELECT equality proof reached message. */
    public static final String FACTORY_NATIVE_SELECT_REACHED_MESSAGE =
            "DelosTableScanResultSet native MVCC SELECT equality reached";

    /** Obsolete pre-MODULE6I native range-predicate proof gate name. */
    public static final String FACTORY_NATIVE_RANGE_PREDICATES_PROPERTY =
            "delosdb.storage.phaseG1.nativeRangePredicates";

    /** Obsolete pre-MODULE6I native BETWEEN-predicate proof gate name. */
    public static final String FACTORY_NATIVE_BETWEEN_PREDICATES_PROPERTY =
            "delosdb.storage.phaseG2.nativeBetweenPredicates";

    /** Obsolete pre-MODULE6I native IS NULL / IS NOT NULL proof gate name. */
    public static final String FACTORY_NATIVE_NULL_PREDICATES_PROPERTY =
            "delosdb.storage.phaseL31.nativeNullPredicates";

    /** Obsolete pre-MODULE6I native OR-predicate residual proof gate name. */
    public static final String FACTORY_NATIVE_OR_PREDICATES_PROPERTY =
            "delosdb.storage.phaseL33.nativeOrPredicateResidual";

    /** Obsolete pre-MODULE6I native projection-variant proof gate name. */
    public static final String FACTORY_NATIVE_PROJECTION_VARIANTS_PROPERTY =
            "delosdb.storage.phaseL34.nativeProjectionVariants";

    /** Obsolete pre-MODULE6I ORDER BY residual-sort proof gate name. */
    public static final String FACTORY_NATIVE_ORDER_BY_RESIDUAL_PROPERTY =
            "delosdb.storage.phaseL35.nativeOrderByResidual";

    /** Obsolete MODULE5-era compatibility property name. */
    public static final String FACTORY_NATIVE_SELECT_ALL_PROPERTY =
            "delosdb.storage.phaseG3.nativeSelectAll";

    /** Obsolete pre-MODULE6I native SELECT COUNT(*) proof gate name. */
    public static final String FACTORY_NATIVE_COUNT_AGGREGATE_PROPERTY =
            "delosdb.storage.phaseG4.nativeCountAggregate";

    /** Obsolete MODULE5-era compatibility property name. */
    public static final String FACTORY_NATIVE_INSERT_PROPERTY =
            "delosdb.storage.phaseF5.nativeMvccInsert";

    /** Obsolete MODULE5-era compatibility property name. */
    public static final String FACTORY_NATIVE_DELETE_EQUALITY_PROPERTY =
            "delosdb.storage.phaseF6.nativeMvccDeleteEquality";

    /** Obsolete MODULE5-era compatibility property name. */
    public static final String FACTORY_NATIVE_UPDATE_EQUALITY_PROPERTY =
            "delosdb.storage.phaseF7.nativeMvccUpdateEquality";

    private static final String[] ALL = {
            FACTORY_SKELETON_BRANCH_PROPERTY,
            FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY,
            FACTORY_NATIVE_RANGE_PREDICATES_PROPERTY,
            FACTORY_NATIVE_BETWEEN_PREDICATES_PROPERTY,
            FACTORY_NATIVE_NULL_PREDICATES_PROPERTY,
            FACTORY_NATIVE_OR_PREDICATES_PROPERTY,
            FACTORY_NATIVE_PROJECTION_VARIANTS_PROPERTY,
            FACTORY_NATIVE_ORDER_BY_RESIDUAL_PROPERTY,
            FACTORY_NATIVE_SELECT_ALL_PROPERTY,
            FACTORY_NATIVE_COUNT_AGGREGATE_PROPERTY,
            FACTORY_NATIVE_INSERT_PROPERTY,
            FACTORY_NATIVE_DELETE_EQUALITY_PROPERTY,
            FACTORY_NATIVE_UPDATE_EQUALITY_PROPERTY
    };

    private DelosObsoleteStorageProperties()
    {
    }

    /**
     * Guardrail: obsolete native MVCC CRUD proof properties never enable live
     * routing, even when present in the JVM system properties.
     */
    public static boolean legacyNativeMvccCrudProofRoutesEnabledForTesting()
    {
        return false;
    }

    public static String[] all()
    {
        return ALL.clone();
    }
}
