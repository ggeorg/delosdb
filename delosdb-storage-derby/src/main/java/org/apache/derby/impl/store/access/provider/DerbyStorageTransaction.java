/*

   Derby - Class org.apache.derby.impl.store.access.provider.DerbyStorageTransaction

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
import org.apache.derby.iapi.store.types.DelosAccessContext;
import org.apache.derby.iapi.store.types.DelosContextKey;
import org.apache.derby.iapi.store.types.StoreDataValue;

/** Transaction-handle adapter for inherited Derby storage access. */
public final class DerbyStorageTransaction {
    public static final DelosContextKey<TransactionController> TRANSACTION_CONTROLLER_KEY =
            DelosContextKey.of("delosdb.storage.derby.transaction.controller", TransactionController.class);

    @SuppressWarnings("unchecked")
    public static final DelosContextKey<StoreDataValue[]> ROW_TEMPLATE_KEY =
            DelosContextKey.of("delosdb.storage.derby.row.template", (Class<StoreDataValue[]>) StoreDataValue[].class);

    private final TransactionController transactionController;
    private final StoreDataValue[] rowTemplate;

    public DerbyStorageTransaction(TransactionController transactionController) {
        this(transactionController, null);
    }

    public DerbyStorageTransaction(
            TransactionController transactionController,
            StoreDataValue[] rowTemplate) {
        this.transactionController = Objects.requireNonNull(transactionController, "transactionController");
        this.rowTemplate = rowTemplate == null ? null : rowTemplate.clone();
    }

    public TransactionController transactionController() {
        return transactionController;
    }

    public StoreDataValue[] rowTemplate() {
        return rowTemplate == null ? null : rowTemplate.clone();
    }

    public DelosAccessContext accessContext(boolean physicalAccessAllowed) {
        DelosAccessContext.Builder builder = DelosAccessContext.builder(physicalAccessAllowed)
                .put(TRANSACTION_CONTROLLER_KEY, transactionController);
        if (rowTemplate != null) {
            builder.put(ROW_TEMPLATE_KEY, rowTemplate.clone());
        }
        return builder.build();
    }

    public DelosAccessContext accessContext() {
        return accessContext(true);
    }
}
