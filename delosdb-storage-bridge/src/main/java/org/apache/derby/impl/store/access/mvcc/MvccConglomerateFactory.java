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

import java.nio.file.Path;
import java.util.Properties;

import org.apache.derby.iapi.services.io.Storable;
import org.apache.derby.iapi.services.io.StoredFormatIds;
import org.apache.derby.iapi.services.monitor.ModuleControl;
import org.apache.derby.iapi.services.monitor.ModuleFactory;
import org.apache.derby.iapi.services.monitor.ModuleSupportable;
import org.apache.derby.iapi.services.monitor.Monitor;
import org.apache.derby.iapi.services.monitor.PersistentService;
import org.apache.derby.iapi.services.uuid.UUIDFactory;
import org.apache.derby.iapi.store.access.AccessFactory;
import org.apache.derby.iapi.store.access.ColumnOrdering;
import org.apache.derby.iapi.store.access.conglomerate.Conglomerate;
import org.apache.derby.iapi.store.access.conglomerate.ConglomerateFactory;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.PageKey;
import org.apache.derby.iapi.store.raw.Transaction;
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
    private Path databaseDirectory;
    private MvccDatabaseRuntime.Lease runtimeLease;
    private MvccDatabaseRuntime runtime;

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
        return new MvccConglomerate(runtime(), segment, input_containerid, template, collationIds, temporaryFlag);
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

    @Override
    public void boot(boolean create, Properties startParams) throws StandardException {
        ModuleFactory monitor = Monitor.getMonitor();
        UUIDFactory uuidFactory = (UUIDFactory) monitor.getUUIDFactory();
        formatUUID = uuidFactory.recreateUUID(FORMAT_UUID_STRING);
        String serviceDirectory = startParams.getProperty(PersistentService.ROOT);
        if (serviceDirectory == null || serviceDirectory.isBlank()) {
            throw new IllegalStateException("Missing Derby database directory for delos_mvcc runtime");
        }
        databaseDirectory = Path.of(serviceDirectory).toAbsolutePath().normalize();
        runtimeLease = MvccDatabaseRuntime.acquire(databaseDirectory);
        runtime = runtimeLease.runtime();
    }

    @Override
    public void stop() {
        MvccDatabaseRuntime.Lease lease = runtimeLease;
        runtimeLease = null;
        runtime = null;
        databaseDirectory = null;
        if (lease != null) {
            lease.close();
        }
    }

    private MvccDatabaseRuntime runtime() {
        MvccDatabaseRuntime current = runtime;
        if (current == null) {
            throw new IllegalStateException("delos_mvcc conglomerate factory is not booted");
        }
        return current;
    }
}
