/*

   DelosDB - Phase C20 table-access contracts smoke test

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
package io.github.ggeorg.delosdb.storage.c20;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import org.apache.derby.iapi.store.types.DelosAccessContext;
import org.apache.derby.iapi.store.types.DelosContextKey;
import org.apache.derby.iapi.store.types.DelosFilterableTableAccess;
import org.apache.derby.iapi.store.types.DelosIndexAccess;
import org.apache.derby.iapi.store.types.DelosIndexStats;
import org.apache.derby.iapi.store.types.DelosIndexableTableAccess;
import org.apache.derby.iapi.store.types.DelosMutationResult;
import org.apache.derby.iapi.store.types.DelosMutableTableAccess;
import org.apache.derby.iapi.store.types.DelosPredicate;
import org.apache.derby.iapi.store.types.DelosPredicateOperator;
import org.apache.derby.iapi.store.types.DelosProjection;
import org.apache.derby.iapi.store.types.DelosRange;
import org.apache.derby.iapi.store.types.DelosRow;
import org.apache.derby.iapi.store.types.DelosRowIdentity;
import org.apache.derby.iapi.store.types.DelosScan;
import org.apache.derby.iapi.store.types.DelosTableAccess;
import org.apache.derby.iapi.store.types.DelosTableCapabilities;
import org.apache.derby.iapi.store.types.DelosTableCapability;
import org.apache.derby.iapi.store.types.DelosTableIdentity;
import org.apache.derby.iapi.store.types.DelosTableShape;
import org.apache.derby.iapi.store.types.StoreDataValue;

public final class StoragePhaseC20ContractsSmoke {
    private StoragePhaseC20ContractsSmoke() {
    }

    public static void main(String[] args) {
        verifyStorelessBaseOnlyShape();
        verifyTypedAccessContext();
        verifyMutableFilterPushdownContract();
        verifyRowIdentityBasedMutationContract();
        verifyIndexableContractCompilesWithoutHandlerSurface();
        System.out.println("storage-phase-c20-contracts-smoke: PASS");
    }

    private static void verifyStorelessBaseOnlyShape() {
        DelosTableAccess storeless = new StorelessBaseOnlyAccess();
        require(storeless.identity().qualifiedName().equals("APP.STORELESS_C20"),
                "base table access must expose table identity");
        require(storeless.rowShape().columns().size() == 1,
                "base table access must expose row shape");
        require(!storeless.capabilities().supports(DelosTableCapability.FILTERABLE),
                "storeless base-only access must not advertise filterable capability");
        require(!(storeless instanceof DelosFilterableTableAccess),
                "storeless base-only access must not implement filterable access");
        require(!(storeless instanceof DelosIndexableTableAccess),
                "storeless base-only access must not implement indexable access");
        require(!(storeless instanceof DelosMutableTableAccess),
                "storeless base-only access must not implement mutable access");
    }

    private static void verifyTypedAccessContext() {
        DelosContextKey<String> txKey = DelosContextKey.of("c20.tx", String.class);
        DelosAccessContext context = DelosAccessContext.builder(true)
                .put(txKey, "tx-c20")
                .build();
        require(context.physicalAccessAllowed(), "physical access gate must be first-class");
        require(context.find(txKey).orElseThrow().equals("tx-c20"),
                "typed context key must retrieve provider-specific context");
        require(context.require(txKey).equals("tx-c20"),
                "typed context key require must retrieve provider-specific context");
    }

    private static void verifyMutableFilterPushdownContract() {
        FilterableAccess access = new FilterableAccess();
        List<DelosPredicate> mutableFilters = new java.util.ArrayList<>();
        mutableFilters.add(DelosPredicate.equalsTo("ID", new DummyValue("1")));
        mutableFilters.add(new DelosPredicate("NAME", DelosPredicateOperator.NOT_EQUAL,
                List.of(new DummyValue("unsupported"))));

        try (DelosScan scan = access.scan(DelosAccessContext.empty(true), mutableFilters, DelosProjection.all())) {
            require(!scan.next(), "empty proof scan should not return rows");
        }

        require(mutableFilters.size() == 1,
                "filterable access must remove only pushed predicates from mutable filter list");
        require(mutableFilters.get(0).operator() == DelosPredicateOperator.NOT_EQUAL,
                "unsupported predicate must remain for caller-side filtering");
    }

    private static void verifyRowIdentityBasedMutationContract() {
        MutableAccess access = new MutableAccess();
        DelosRowIdentity identity = new DummyIdentity("delos_mvcc", 7L);
        DelosMutationResult update = access.update(
                DelosAccessContext.empty(true),
                identity,
                DelosRow.withoutIdentity(List.of(new DummyValue("replacement"))));
        DelosMutationResult delete = access.delete(DelosAccessContext.empty(true), identity);

        require(update.affectedRows() == 1, "update must report one affected row");
        require(delete.affectedRows() == 1, "delete must report one affected row");
        require(access.lastIdentity == identity,
                "mutation must receive the row identity directly rather than re-derive it from SQL text");
    }

    private static void verifyIndexableContractCompilesWithoutHandlerSurface() {
        IndexableAccess access = new IndexableAccess();
        try (DelosIndexAccess index = access.openIndex(DelosAccessContext.empty(true), "C20_IDX")) {
            require(index.stats(DelosAccessContext.empty(true)).rowCount() == 0,
                    "indexable access must expose provider-owned index stats");
            try (DelosScan scan = index.scan(DelosAccessContext.empty(true),
                    DelosRange.all("ID"),
                    DelosProjection.all())) {
                require(!scan.next(), "empty proof index scan should not return rows");
            }
        }
    }

    private static DelosTableIdentity identity() {
        return DelosTableIdentity.of("APP", "STORELESS_C20");
    }

    private static DelosTableShape shape() {
        return DelosTableShape.of(List.of(new DelosTableShape.Column("ID", "INT", false)));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static class StorelessBaseOnlyAccess implements DelosTableAccess {
        @Override
        public DelosTableIdentity identity() {
            return StoragePhaseC20ContractsSmoke.identity();
        }

        @Override
        public DelosTableShape rowShape() {
            return shape();
        }

        @Override
        public DelosTableCapabilities capabilities() {
            return DelosTableCapabilities.none();
        }
    }

    private static final class FilterableAccess extends StorelessBaseOnlyAccess
            implements DelosFilterableTableAccess {
        @Override
        public DelosTableCapabilities capabilities() {
            return DelosTableCapabilities.of(
                    DelosTableCapability.FILTERABLE,
                    DelosTableCapability.PROJECTABLE);
        }

        @Override
        public DelosScan scan(DelosAccessContext context,
                              List<DelosPredicate> mutableFilters,
                              DelosProjection projection) {
            require(context.physicalAccessAllowed(), "scan must honor physical access gate");
            require(projection.allColumns(), "projection must be part of the filterable access call");
            for (Iterator<DelosPredicate> it = mutableFilters.iterator(); it.hasNext();) {
                DelosPredicate predicate = it.next();
                if (predicate.operator() == DelosPredicateOperator.EQUAL) {
                    it.remove();
                }
            }
            return EmptyScan.INSTANCE;
        }
    }

    private static final class MutableAccess extends StorelessBaseOnlyAccess
            implements DelosMutableTableAccess {
        private DelosRowIdentity lastIdentity;

        @Override
        public DelosTableCapabilities capabilities() {
            return DelosTableCapabilities.of(DelosTableCapability.MUTABLE);
        }

        @Override
        public DelosMutationResult insert(DelosAccessContext context, DelosRow row) {
            require(context.physicalAccessAllowed(), "insert must honor physical access gate");
            return DelosMutationResult.inserted(new DummyIdentity("delos_mvcc", 8L));
        }

        @Override
        public DelosMutationResult update(DelosAccessContext context,
                                          DelosRowIdentity rowIdentity,
                                          DelosRow replacement) {
            require(context.physicalAccessAllowed(), "update must honor physical access gate");
            require(replacement.values().size() == 1, "replacement row must cross the contract");
            lastIdentity = rowIdentity;
            return DelosMutationResult.affected(1);
        }

        @Override
        public DelosMutationResult delete(DelosAccessContext context, DelosRowIdentity rowIdentity) {
            require(context.physicalAccessAllowed(), "delete must honor physical access gate");
            lastIdentity = rowIdentity;
            return DelosMutationResult.affected(1);
        }
    }

    private static final class IndexableAccess extends StorelessBaseOnlyAccess
            implements DelosIndexableTableAccess {
        @Override
        public DelosTableCapabilities capabilities() {
            return DelosTableCapabilities.of(DelosTableCapability.INDEXABLE);
        }

        @Override
        public DelosIndexAccess openIndex(DelosAccessContext context, String indexName) {
            require(context.physicalAccessAllowed(), "index open must honor physical access gate");
            return new EmptyIndexAccess(indexName);
        }
    }

    private enum EmptyScan implements DelosScan {
        INSTANCE;

        @Override
        public boolean next() {
            return false;
        }

        @Override
        public DelosRow row() {
            throw new IllegalStateException("empty scan has no current row");
        }

        @Override
        public void close() {
        }
    }

    private record EmptyIndexAccess(String indexName) implements DelosIndexAccess {
        @Override
        public DelosIndexStats stats(DelosAccessContext context) {
            require(context.physicalAccessAllowed(), "index stats must honor physical access gate");
            return new DelosIndexStats(0, 0);
        }

        @Override
        public DelosScan scan(DelosAccessContext context, DelosRange range, DelosProjection projection) {
            require(context.physicalAccessAllowed(), "index scan must honor physical access gate");
            require(range.columnName().equals("ID"), "index range must cross the contract");
            require(projection.allColumns(), "index scan projection must cross the contract");
            return EmptyScan.INSTANCE;
        }

        @Override
        public void close() {
        }
    }

    private record DummyIdentity(String providerName, Object nativeIdentity) implements DelosRowIdentity {
    }

    private record DummyValue(String value) implements StoreDataValue {
    }
}
