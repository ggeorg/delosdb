/*

   Derby - Class org.apache.derby.iapi.store.access.conglomerate.AccessMethodBootContext

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

package org.apache.derby.iapi.store.access.conglomerate;

import java.util.Objects;
import java.util.Properties;

import org.apache.derby.iapi.store.raw.RawStoreFactory;
import org.apache.derby.iapi.store.raw.data.DataFactory;
import org.apache.derby.io.StorageFactory;

/**
 * Database-owned boot context for externally provided access methods.
 *
 * <p>The context is created by the owning {@code RAMAccessManager} after its
 * RawStore has booted.  External access methods receive the actual database
 * services instead of reconstructing database ownership from service-name or
 * filesystem properties.</p>
 */
public final class AccessMethodBootContext {
    private final RawStoreFactory rawStoreFactory;
    private final DataFactory dataFactory;
    private final StorageFactory storageFactory;
    private final Properties serviceProperties;
    private final boolean create;
    private final boolean readOnly;
    private final Object databaseIdentity;

    public AccessMethodBootContext(
            RawStoreFactory rawStoreFactory,
            DataFactory dataFactory,
            StorageFactory storageFactory,
            Properties serviceProperties,
            boolean create,
            boolean readOnly,
            Object databaseIdentity) {
        this.rawStoreFactory = Objects.requireNonNull(rawStoreFactory, "rawStoreFactory");
        this.dataFactory = Objects.requireNonNull(dataFactory, "dataFactory");
        this.storageFactory = Objects.requireNonNull(storageFactory, "storageFactory");
        this.serviceProperties = copyProperties(serviceProperties);
        this.create = create;
        this.readOnly = readOnly;
        this.databaseIdentity = Objects.requireNonNull(databaseIdentity, "databaseIdentity");
    }

    public RawStoreFactory rawStoreFactory() {
        return rawStoreFactory;
    }

    public DataFactory dataFactory() {
        return dataFactory;
    }

    public StorageFactory storageFactory() {
        return storageFactory;
    }

    public Properties serviceProperties() {
        return copyProperties(serviceProperties);
    }

    public boolean create() {
        return create;
    }

    public boolean readOnly() {
        return readOnly;
    }

    /**
     * Returns the owning database-service identity.
     *
     * <p>Consumers may retain this value for identity comparison only.  They
     * must not infer a path, database name, or process-global lookup key from
     * it.</p>
     */
    public Object databaseIdentity() {
        return databaseIdentity;
    }

    public AccessMethodBootContext withServiceProperties(Properties properties) {
        return new AccessMethodBootContext(
                rawStoreFactory,
                dataFactory,
                storageFactory,
                properties,
                create,
                readOnly,
                databaseIdentity);
    }

    private static Properties copyProperties(Properties source) {
        Properties copy = new Properties();
        if (source != null) {
            copy.putAll(source);
        }
        return copy;
    }
}
