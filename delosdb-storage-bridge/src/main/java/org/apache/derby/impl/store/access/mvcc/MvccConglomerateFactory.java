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

/** Access-method factory for the RawStore-owned {@code delos_mvcc} format. */
public final class MvccConglomerateFactory
        implements ConglomerateFactory, ModuleControl, ModuleSupportable {
    public static final String IMPLEMENTATION_ID = "delos_mvcc";

    private static final String FORMAT_UUID_STRING = "3FD22170-28F5-4EF4-8C32-EC5FB6E6115B";

    private Object formatUUID;
    private MvccRawStoreRuntime runtime;
    private Path diagnosticsDirectory;
    private long nextConglomerateId;

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
            TransactionManager xactManager,
            int segment,
            long inputContainerId,
            StoreDataValue[] template,
            ColumnOrdering[] columnOrder,
            int[] collationIds,
            Properties properties,
            int temporaryFlag) throws StandardException {
        rejectUnsupportedDurableTypes(template);
        rejectGlobalLifecycle(xactManager);

        MvccRawStoreRuntime currentRuntime = runtime();
        currentRuntime.ensureMetadata(xactManager);
        registerRawStoreOwnedMvcc(xactManager);
        long containerId = reserveConglomerateId(inputContainerId);
        MvccRawStoreTable.Descriptor descriptor = MvccRawStoreTable.create(
                xactManager.getRawStoreXact(),
                segment,
                containerId,
                template,
                collationIds,
                properties,
                temporaryFlag);
        currentRuntime.context(xactManager, xactManager.getRawStoreXact())
                .markCreatedTable(descriptor);
        return new MvccConglomerate(currentRuntime, descriptor);
    }

    @Override
    public Conglomerate readConglomerate(
            TransactionManager xactManager,
            ContainerKey containerKey) throws StandardException {
        MvccRawStoreTable.Descriptor descriptor = MvccRawStoreTable.read(
                xactManager.getRawStoreXact(),
                containerKey);
        if (descriptor == null) {
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED,
                    "The retained external delos_mvcc format has been retired; "
                            + "only RawStore-backed delos_mvcc tables are supported");
        }
        MvccRawStoreRuntime currentRuntime = runtime();
        currentRuntime.ensureMetadata(xactManager);
        currentRuntime.registerTable(descriptor);
        return new MvccConglomerate(currentRuntime, descriptor);
    }

    @Override
    public void insertUndoNotify(
            AccessFactory accessFactory,
            Transaction transaction,
            PageKey pageKey) {
        // All physical undo is inherited RawStore undo.
    }

    @Override
    public boolean canSupport(Properties startParams) {
        return supportsImplementation(
                startParams.getProperty("derby.access.Conglomerate.type"));
    }

    void boot(AccessMethodBootContext context) throws StandardException {
        java.util.Objects.requireNonNull(context, "context");
        ModuleFactory monitor = Monitor.getMonitor();
        UUIDFactory uuidFactory = (UUIDFactory) monitor.getUUIDFactory();
        formatUUID = uuidFactory.recreateUUID(FORMAT_UUID_STRING);

        String storageRoot = context.dataFactory().getRootDirectory();
        Path databaseDirectory = storageRoot == null || storageRoot.isBlank()
                ? null
                : Path.of(storageRoot);
        rejectRetainedExternalState(databaseDirectory);

        runtime = new MvccRawStoreRuntime(
                context.databaseIdentity(),
                context.rawStoreFactory().getLockFactory());
        String diagnosticIdentity = databaseDirectory != null && databaseDirectory.isAbsolute()
                ? databaseDirectory.toAbsolutePath().normalize().toString()
                : "memory-" + Integer.toHexString(
                        System.identityHashCode(context.databaseIdentity()));
        runtime.startMaintenance(
                diagnosticIdentity,
                context.rawStoreFactory(),
                context.readOnly(),
                context.serviceProperties());
        if (databaseDirectory != null && databaseDirectory.isAbsolute()) {
            diagnosticsDirectory = databaseDirectory.toAbsolutePath().normalize();
            MvccRawStoreDiagnosticsDirectory.register(diagnosticsDirectory, runtime);
        } else {
            MvccRawStoreDiagnosticsDirectory.register(null, runtime);
        }
    }

    private synchronized long reserveConglomerateId(long proposedContainerId) {
        if (proposedContainerId == ContainerHandle.DEFAULT_ASSIGN_ID) {
            return proposedContainerId;
        }
        long candidate = proposedContainerId;
        if (nextConglomerateId != 0L && candidate < nextConglomerateId) {
            candidate = nextConglomerateId;
        }
        nextConglomerateId = Math.addExact(candidate, 16L);
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
                "The retained external delos_mvcc format has been retired; "
                        + "remove or migrate state under " + providerDirectory
                        + " before booting this database");
    }

    @Override
    public void boot(boolean create, Properties startParams) throws StandardException {
        throw StandardException.newException(
                SQLState.NOT_IMPLEMENTED,
                "delos_mvcc must be booted with a database-owned access-method context");
    }

    @Override
    public void stop() {
        MvccRawStoreRuntime current = runtime;
        Path currentDirectory = diagnosticsDirectory;
        runtime = null;
        diagnosticsDirectory = null;
        nextConglomerateId = 0L;
        if (current != null) {
            MvccRawStoreDiagnosticsDirectory.unregister(currentDirectory, current);
            current.close();
        }
    }

    private MvccRawStoreRuntime runtime() {
        MvccRawStoreRuntime current = runtime;
        if (current == null) {
            throw new IllegalStateException("delos_mvcc conglomerate factory is not booted");
        }
        return current;
    }
}
