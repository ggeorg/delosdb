/*

   Derby - Class org.apache.derby.impl.sql.execute.DelosNativeResultSetSupport

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

import org.apache.derby.catalog.UUID;
import org.apache.derby.iapi.sql.Activation;
import org.apache.derby.iapi.sql.conn.LanguageConnectionContext;
import org.apache.derby.iapi.sql.dictionary.DataDictionary;
import org.apache.derby.iapi.sql.dictionary.SchemaDescriptor;
import org.apache.derby.iapi.sql.dictionary.TableDescriptor;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Shared Derby catalog helpers for the remaining Delos heap proof result-set
 * paths.
 *
 * <p>The old native-registry MVCC result-set family was retired after normal
 * Derby store/access SELECT, INSERT, DELETE, and UPDATE reached MVCC physical
 * conglomerates directly. These helpers no longer open native registry table
 * access.</p>
 */
final class DelosNativeResultSetSupport {
    private DelosNativeResultSetSupport() {
    }

    static String qualifiedName(String schemaName, String tableName) {
        if (schemaName == null || schemaName.isBlank()) {
            return tableName;
        }
        return schemaName + "." + tableName;
    }

    static String qualifiedName(TableDescriptor tableDescriptor) {
        return tableDescriptor.getSchemaName() + "." + tableDescriptor.getName();
    }

    static TableDescriptor tableDescriptor(
            Activation activation,
            String schemaName,
            String tableName,
            String operation) throws StandardException {
        LanguageConnectionContext lcc = activation.getLanguageConnectionContext();
        DataDictionary dataDictionary = lcc.getDataDictionary();
        TransactionController transactionController = lcc.getTransactionExecute();
        SchemaDescriptor schema = dataDictionary.getSchemaDescriptor(
                schemaName,
                transactionController,
                true);
        TableDescriptor table = dataDictionary.getTableDescriptor(
                tableName,
                schema,
                transactionController);
        if (table == null) {
            throw StandardException.plainWrapException(new IllegalStateException(
                    "No TableDescriptor for " + operation + ": "
                            + qualifiedName(schemaName, tableName)));
        }
        return table;
    }

    static TableDescriptor tableDescriptor(Activation activation, UUID tableUUID)
            throws StandardException {
        LanguageConnectionContext lcc = activation.getLanguageConnectionContext();
        DataDictionary dataDictionary = lcc.getDataDictionary();
        return dataDictionary.getTableDescriptor(tableUUID);
    }
}
