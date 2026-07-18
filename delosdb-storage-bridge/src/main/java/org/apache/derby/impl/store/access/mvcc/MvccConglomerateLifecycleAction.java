/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccConglomerateLifecycleAction

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
package org.apache.derby.impl.store.access.mvcc;

import java.util.Objects;

import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.types.DelosMvccConglomerateLifecycle;
import org.apache.derby.iapi.store.types.DelosStorageTransactionLifecycleAction;

/** Live-runtime completion for one raw-store-owned MVCC DDL lifecycle. */
final class MvccConglomerateLifecycleAction
        implements DelosStorageTransactionLifecycleAction {
    private final MvccDatabaseRuntime runtime;
    private final ContainerKey key;
    private final DelosMvccConglomerateLifecycle lifecycle;

    private MvccConglomerateLifecycleAction(
            MvccDatabaseRuntime runtime,
            ContainerKey key,
            DelosMvccConglomerateLifecycle lifecycle) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.key = Objects.requireNonNull(key, "key");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    static MvccConglomerateLifecycleAction create(
            MvccDatabaseRuntime runtime,
            ContainerKey key,
            DelosMvccConglomerateLifecycle lifecycle) {
        if (lifecycle.operation() != DelosMvccConglomerateLifecycle.Operation.CREATE) {
            throw new IllegalArgumentException("Expected a delos_mvcc CREATE lifecycle");
        }
        return new MvccConglomerateLifecycleAction(runtime, key, lifecycle);
    }

    static MvccConglomerateLifecycleAction drop(
            MvccDatabaseRuntime runtime,
            ContainerKey key,
            DelosMvccConglomerateLifecycle lifecycle) {
        if (lifecycle.operation() != DelosMvccConglomerateLifecycle.Operation.DROP) {
            throw new IllegalArgumentException("Expected a delos_mvcc DROP lifecycle");
        }
        return new MvccConglomerateLifecycleAction(runtime, key, lifecycle);
    }

    @Override
    public DelosMvccConglomerateLifecycle lifecycle() {
        return lifecycle;
    }

    @Override
    public void commitAfterRawStoreCommit() {
        switch (lifecycle.operation()) {
            case CREATE -> runtime.completeCreate(lifecycle);
            case DROP -> runtime.completeDrop(key, lifecycle);
        }
    }

    @Override
    public void abortBeforeRawStoreCommit() {
        if (lifecycle.operation() == DelosMvccConglomerateLifecycle.Operation.CREATE) {
            runtime.abortCreate(key, lifecycle);
        }
    }
}
