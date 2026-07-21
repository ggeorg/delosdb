/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccConglomerateFactory

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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Properties;

import org.apache.derby.iapi.services.io.Storable;
import org.apache.derby.iapi.services.io.StoredFormatIds;
import org.apache.derby.iapi.services.monitor.ModuleControl;
import org.apache.derby.iapi.services.monitor.ModuleFactory;
import org.apache.derby.iapi.services.monitor.ModuleSupportable;
import org.apache.derby.iapi.services.monitor.Monitor;
import org.apache.derby.iapi.services.uuid.UUIDFactory;
import org.apache.derby.iapi.store.access.AccessFactory;
import org.apache.derby.iapi.store.access.ColumnOrdering;
import org.apache.derby.iapi.store.access.conglomerate.AccessMethodBootContext;
import org.apache.derby.iapi.store.access.conglomerate.Conglomerate;
import org.apache.derby.iapi.store.access.conglomerate.ConglomerateFactory;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.PageKey;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.DelosMvccConglomerateLifecycle;
import org.apache.derby.iapi.store.types.DelosStorageTransactionRegistry;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/**
 * Access-method factory for {@code delos_mvcc} conglomerates.
 *
 * <p>The factory registers the MVCC conglomerate implementation with Derby's
 * monitor and access-method infrastructure and reconstructs persisted
 * conglomerate descriptors during database boot.</p>
 */
public final class MvccConglomerateFactory
        implements ConglomerateFactory, ModuleControl, ModuleSupportable {
    public static final String IMPLEMENTATION_ID = "delos_mvcc";

    private static final String FORMAT_UUID_STRING = "3FD22170-28F5-4EF4-8C32-EC5FB6E6115B";

    private Object formatUUID;
    private MvccDatabaseRuntime runtime;
    private MvccRawStoreRuntime rawStoreRuntime;
    private Path rawStoreDiagnosticsDirectory;
    private boolean rawStoreVerticalSliceEnabled;
    private long nextRawStoreConglomerateId;

    @Override
    public Properties defaultProperties() {
        return new Properties();
    }

    @Override
    public boolean supportsImplementation(String implementationId) {
        return IMPLEMENTATION_ID.equals(implementationId);
    }

    @Override
    public String primaryImplementationType() {
        return IMPLEMENTATION_ID;
    }

    @Override
    public boolean supportsFormat(Object formatid) {
        return formatUUID != null && formatUUID.equals(formatid);
    }

    @Override
    public Object primaryFormat() {
        return formatUUID;
    }

    @Override
    public int getConglomerateFactoryId() {
        return ConglomerateFactory.MVCC_FACTORY_ID;
    }

    @Override
    public Conglomerate createConglomerate(
            TransactionManager xact_mgr,
            int segment,
            long input_containerid,
            StoreDataValue[] template,
            ColumnOrdering[] columnOrder,
            int[] collationIds,
            Properties properties,
            int temporaryFlag) throws StandardException {
        rejectUnsupportedDurableTypes(template);
        rejectGlobalLifecycle(xact_mgr);
        if (rawStoreVerticalSliceEnabled) {
            rawStoreRuntime().ensureMetadata(xact_mgr);
            registerRawStoreOwnedMvcc(xact_mgr);
            long rawStoreContainerId = reserveRawStoreConglomerateId(input_containerid);
            MvccRawStoreTable.Descriptor descriptor = MvccRawStoreTable.create(
                    xact_mgr.getRawStoreXact(),
                    segment,
                    rawStoreContainerId,
                    template,
                    collationIds,
                    properties,
                    temporaryFlag);
            rawStoreRuntime().context(xact_mgr, xact_mgr.getRawStoreXact())
                    .markCreatedTable(descriptor);
            return new MvccConglomerate(rawStoreRuntime(), descriptor);
        }
        MvccDatabaseRuntime currentRuntime = runtime();
        ContainerKey key = new ContainerKey(segment, input_containerid);
        DelosMvccConglomerateLifecycle lifecycle = new DelosMvccConglomerateLifecycle(
                DelosMvccConglomerateLifecycle.Operation.CREATE,
                segment,
                input_containerid);
        try {
            currentRuntime.stageCreate(lifecycle);
            MvccConglomerate conglomerate = new MvccConglomerate(
                    currentRuntime,
                    segment,
                    input_containerid,
                    template,
                    collationIds,
                    temporaryFlag);
            DelosStorageTransactionRegistry.registerLifecycleAction(
                    xact_mgr,
                    MvccConglomerateLifecycleAction.create(
                            currentRuntime, key, lifecycle));
            return conglomerate;
        } catch (RuntimeException | Error failure) {
            try {
                currentRuntime.abortCreate(key, lifecycle);
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw StandardException.plainWrapException(failure);
        }
    }

    private synchronized long reserveRawStoreConglomerateId(long proposedContainerId) {
        if (proposedContainerId == ContainerHandle.DEFAULT_ASSIGN_ID) {
            return proposedContainerId;
        }

        long candidate = proposedContainerId;
        if (nextRawStoreConglomerateId != 0L && candidate < nextRawStoreConglomerateId) {
            candidate = nextRawStoreConglomerateId;
        }
        nextRawStoreConglomerateId = Math.addExact(candidate, 16L);
        return candidate;
    }

    private static void registerRawStoreOwnedMvcc(TransactionManager transaction)
            throws StandardException {
        try {
            DelosStorageTransactionRegistry.registerRawStoreOwnedMvcc(transaction);
        } catch (IllegalStateException mixedAuthorities) {
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED,
                    mixedAuthorities,
                    mixedAuthorities.getMessage());
        }
    }

    private static void rejectGlobalLifecycle(TransactionManager transaction)
            throws StandardException {
        if (transaction.isGlobal()) {
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED,
                    "delos_mvcc DDL in XA transactions");
        }
    }

    private static void rejectUnsupportedDurableTypes(StoreDataValue[] template)
            throws StandardException {
        if (template == null) {
            return;
        }
        for (StoreDataValue value : template) {
            if (!(value instanceof Storable storable)) {
                continue;
            }
            int formatId = storable.getTypeFormatId();
            if (formatId == StoredFormatIds.SERIALIZABLE_FORMAT_ID
                    || formatId == StoredFormatIds.SQL_USERTYPE_ID_V3) {
                throw StandardException.newException(
                        SQLState.NOT_IMPLEMENTED,
                        "JAVA_OBJECT/UserType columns for delos_mvcc");
            }
        }
    }

    @Override
    public Conglomerate readConglomerate(
            TransactionManager xact_mgr,
            ContainerKey container_key) throws StandardException {
        MvccRawStoreTable.Descriptor descriptor = MvccRawStoreTable.read(
                xact_mgr.getRawStoreXact(),
                container_key);
        if (descriptor != null) {
            if (!rawStoreVerticalSliceEnabled) {
                throw StandardException.newException(
                        SQLState.NOT_IMPLEMENTED,
                        "RawStore-backed delos_mvcc table requires "
                                + MvccRawStoreFormat.ENABLED_PROPERTY + "=true");
            }
            rawStoreRuntime().ensureMetadata(xact_mgr);
            rawStoreRuntime().registerTable(descriptor);
            return new MvccConglomerate(rawStoreRuntime(), descriptor);
        }
        if (rawStoreVerticalSliceEnabled) {
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED,
                    "RawStore-owned delos_mvcc mode cannot open a retained external-format table; "
                            + "boot with " + MvccRawStoreFormat.ENABLED_PROPERTY
                            + "=false to access retained Phase 8 state");
        }
        return new MvccConglomerate(runtime(), container_key);
    }

    @Override
    public void insertUndoNotify(
            AccessFactory access_factory,
            Transaction xact,
            PageKey page_key) throws StandardException {
        // MVCC undo and recovery are owned by the DelosDB storage engine and are not
        // wired into inherited raw-store undo notifications in this milestone.
    }

    @Override
    public boolean canSupport(Properties startParams) {
        String implementation = startParams.getProperty("derby.access.Conglomerate.type");
        return supportsImplementation(implementation);
    }

    void boot(AccessMethodBootContext context) throws StandardException {
        java.util.Objects.requireNonNull(context, "context");
        ModuleFactory monitor = Monitor.getMonitor();
        UUIDFactory uuidFactory = (UUIDFactory) monitor.getUUIDFactory();
        formatUUID = uuidFactory.recreateUUID(FORMAT_UUID_STRING);

        Properties serviceProperties = context.serviceProperties();
        String storageRoot = context.dataFactory().getRootDirectory();
        Path legacyStorageDirectory = storageRoot == null || storageRoot.isBlank()
                ? null
                : Path.of(storageRoot);
        String configuredRawStoreMode = serviceProperties.getProperty(
                MvccRawStoreFormat.ENABLED_PROPERTY,
                System.getProperty(MvccRawStoreFormat.ENABLED_PROPERTY, "false"));
        rawStoreVerticalSliceEnabled = Boolean.parseBoolean(configuredRawStoreMode);
        if (rawStoreVerticalSliceEnabled) {
            rejectRetainedExternalState(legacyStorageDirectory);
            rawStoreRuntime = new MvccRawStoreRuntime(
                    context.databaseIdentity(),
                    context.rawStoreFactory().getLockFactory());
            String diagnosticIdentity = legacyStorageDirectory != null
                    && legacyStorageDirectory.isAbsolute()
                    ? legacyStorageDirectory.toAbsolutePath().normalize().toString()
                    : "memory-" + Integer.toHexString(
                            System.identityHashCode(context.databaseIdentity()));
            rawStoreRuntime.startMaintenance(
                    diagnosticIdentity,
                    context.rawStoreFactory(),
                    context.readOnly(),
                    serviceProperties);
            if (legacyStorageDirectory != null && legacyStorageDirectory.isAbsolute()) {
                rawStoreDiagnosticsDirectory = legacyStorageDirectory;
                MvccRawStoreDiagnosticsDirectory.register(
                        rawStoreDiagnosticsDirectory, rawStoreRuntime);
            } else {
                MvccRawStoreDiagnosticsDirectory.register(null, rawStoreRuntime);
            }
            return;
        }

        if (legacyStorageDirectory != null && legacyStorageDirectory.isAbsolute()) {
            runtime = new MvccDatabaseRuntime(
                    context.databaseIdentity(),
                    legacyStorageDirectory);
            return;
        }
        if (!rawStoreVerticalSliceEnabled) {
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED,
                    "delos_mvcc memory and non-directory databases require the RawStore-backed table format");
        }
    }

    private static void rejectRetainedExternalState(Path databaseDirectory)
            throws StandardException {
        if (databaseDirectory == null || !databaseDirectory.isAbsolute()) {
            return;
        }

        Path providerDirectory = databaseDirectory
                .toAbsolutePath()
                .normalize()
                .resolve(DelosMvccConglomerateLifecycle.PROVIDER_DIRECTORY);
        if (Files.notExists(providerDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        try {
            if (!Files.isDirectory(providerDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw retainedExternalState(providerDirectory);
            }
            try (var paths = Files.walk(providerDirectory)) {
                boolean retainedStatePresent = paths
                        .skip(1L)
                        .anyMatch(path -> Files.isSymbolicLink(path)
                                || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS));
                if (retainedStatePresent) {
                    throw retainedExternalState(providerDirectory);
                }
            }
        } catch (IOException | UncheckedIOException failure) {
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED,
                    failure,
                    "Unable to verify that retained external delos_mvcc state is absent under "
                            + providerDirectory);
        }
    }

    private static StandardException retainedExternalState(Path providerDirectory) {
        return StandardException.newException(
                SQLState.NOT_IMPLEMENTED,
                "RawStore-owned delos_mvcc mode cannot boot while retained external "
                        + "delos_mvcc state exists under " + providerDirectory
                        + "; boot with " + MvccRawStoreFormat.ENABLED_PROPERTY
                        + "=false to access the retained format");
    }

    @Override
    public void boot(boolean create, Properties startParams) throws StandardException {
        throw StandardException.newException(
                SQLState.NOT_IMPLEMENTED,
                "delos_mvcc must be booted with a database-owned access-method context");
    }

    @Override
    public void stop() {
        MvccDatabaseRuntime currentRuntime = runtime;
        MvccRawStoreRuntime currentRawStoreRuntime = rawStoreRuntime;
        Path currentRawStoreDiagnosticsDirectory = rawStoreDiagnosticsDirectory;
        runtime = null;
        rawStoreRuntime = null;
        rawStoreDiagnosticsDirectory = null;
        rawStoreVerticalSliceEnabled = false;
        nextRawStoreConglomerateId = 0L;
        if (currentRawStoreRuntime != null) {
            MvccRawStoreDiagnosticsDirectory.unregister(
                    currentRawStoreDiagnosticsDirectory, currentRawStoreRuntime);
            currentRawStoreRuntime.close();
        }
        if (currentRuntime != null) {
            currentRuntime.close();
        }
    }

    private MvccDatabaseRuntime runtime() {
        MvccDatabaseRuntime current = runtime;
        if (current == null) {
            throw new IllegalStateException("delos_mvcc conglomerate factory is not booted");
        }
        return current;
    }
    private MvccRawStoreRuntime rawStoreRuntime() {
        MvccRawStoreRuntime current = rawStoreRuntime;
        if (current == null) {
            throw new IllegalStateException("RawStore delos_mvcc runtime is not enabled");
        }
        return current;
    }

}
