/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccStorageDiagnostics

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

import java.nio.file.Path;

import org.apache.derby.iapi.store.types.DelosDatabaseMemorySnapshot;
import org.apache.derby.iapi.store.types.DelosDatabaseStorageSnapshot;
import org.apache.derby.iapi.store.types.DelosRawStoreIoSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsContext;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;
import org.apache.derby.iapi.store.types.DelosStorageMaintenanceSnapshot;

/** Non-owning diagnostics adapter for the RawStore-backed MVCC runtime. */
public final class MvccStorageDiagnostics implements DelosStorageDiagnostics {
    private final Path databaseDirectory;
    private final String databaseIdentity;

    public MvccStorageDiagnostics() {
        this(null, null);
    }

    private MvccStorageDiagnostics(Path databaseDirectory, String databaseIdentity) {
        this.databaseDirectory = databaseDirectory == null
                ? null
                : databaseDirectory.toAbsolutePath().normalize();
        this.databaseIdentity = databaseIdentity;
    }

    @Override
    public DelosStorageDiagnostics withContext(DelosStorageDiagnosticsContext context) {
        if (context == null) {
            return this;
        }
        if (context.hasDatabaseDirectory()) {
            return new MvccStorageDiagnostics(context.databaseDirectory(), null);
        }
        if (context.hasDatabaseIdentity()) {
            return new MvccStorageDiagnostics(null, context.databaseIdentity());
        }
        return this;
    }

    @Override
    public String providerId() {
        return DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID;
    }

    @Override
    public void clearRuntimeStateForTesting() {
        if (databaseIdentity != null) {
            MvccRawStoreDiagnosticsDirectory.clearForTesting(databaseIdentity);
        } else if (databaseDirectory != null) {
            MvccRawStoreDiagnosticsDirectory.clearForTesting(databaseDirectory);
        } else {
            MvccRawStoreDiagnosticsDirectory.clearAllForTesting();
        }
    }

    @Override
    public int runtimeStateCountForTesting() {
        if (!runtimeActiveForTesting()) {
            return 0;
        }
        return databaseMaintenanceSnapshot().registeredTableCount();
    }

    @Override
    public boolean runtimeActiveForTesting() {
        if (databaseIdentity != null) {
            return MvccRawStoreDiagnosticsDirectory.isActive(databaseIdentity);
        }
        return databaseDirectory == null
                ? MvccRawStoreDiagnosticsDirectory.runtimeCount() > 0
                : MvccRawStoreDiagnosticsDirectory.isActive(databaseDirectory);
    }

    @Override
    public DelosStorageMaintenanceSnapshot databaseMaintenanceSnapshot() {
        return rawStoreRuntime().maintenanceSnapshot();
    }

    @Override
    public DelosDatabaseMemorySnapshot databaseMemorySnapshot() {
        return rawStoreRuntime().memorySnapshot();
    }

    @Override
    public DelosRawStoreIoSnapshot databaseRawStoreIoSnapshot() {
        return rawStoreRuntime().rawStoreIoSnapshot();
    }

    @Override
    public DelosDatabaseStorageSnapshot databaseStorageSnapshot() {
        throw new IllegalStateException(
                "Retained delos_mvcc database-storage diagnostics were retired with the external persistence runtime");
    }

    @Override
    public long overflowPageCountForTesting(int segment, long containerId) {
        return rawStoreRuntime().tableStorageSnapshot(segment, containerId).overflowPageCount();
    }

    @Override
    public long reusablePageCountForTesting(int segment, long containerId) {
        return rawStoreRuntime().tableStorageSnapshot(segment, containerId).reusablePageCount();
    }

    private MvccRawStoreRuntime rawStoreRuntime() {
        if (databaseIdentity != null) {
            return MvccRawStoreDiagnosticsDirectory.require(databaseIdentity);
        }
        return databaseDirectory == null
                ? MvccRawStoreDiagnosticsDirectory.requireSingle()
                : MvccRawStoreDiagnosticsDirectory.require(databaseDirectory);
    }
}
