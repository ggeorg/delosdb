/*

   Derby - Class org.apache.derby.impl.store.access.provider.DerbyStorageProvider

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
package org.apache.derby.impl.store.access.provider;

import java.util.Objects;

import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.types.DelosTableIdentity;
import org.apache.derby.iapi.store.types.DelosTableShape;
import org.apache.derby.iapi.store.types.StoreDataValue;

/**
 * Storage-api facade for the inherited Derby heap/raw/btree provider.
 *
 * <p>This class is intentionally thin. It does not move or rewrite inherited
 * Derby storage implementation code. Callers supply the existing Derby store
 * handles, and the facade exposes them through {@code delosdb-storage-api}
 * contracts.</p>
 */
public final class DerbyStorageProvider {
    public static final String PROVIDER_NAME = "derby";

    public String name() {
        return PROVIDER_NAME;
    }

    public DerbyStorageTransaction transaction(TransactionController transactionController) {
        return new DerbyStorageTransaction(transactionController);
    }

    public DerbyStorageTable openTable(
            DelosTableIdentity identity,
            DelosTableShape rowShape,
            long conglomerateId) {
        return new DerbyStorageTable(
                Objects.requireNonNull(identity, "identity"),
                Objects.requireNonNull(rowShape, "rowShape"),
                conglomerateId);
    }

    public DerbyStorageTable openTable(
            String schemaName,
            String tableName,
            DelosTableShape rowShape,
            long conglomerateId) {
        return openTable(new DelosTableIdentity(schemaName, tableName), rowShape, conglomerateId);
    }

    public DerbyStorageTransaction transaction(
            TransactionController transactionController,
            StoreDataValue[] rowTemplate) {
        return new DerbyStorageTransaction(transactionController, rowTemplate);
    }
}
