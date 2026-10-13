/*

   Derby - Class org.apache.derby.impl.sql.conn.EngineLockReportSupport

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

package org.apache.derby.impl.sql.conn;

import java.util.ArrayList;
import java.util.List;

import org.apache.derby.iapi.services.locks.LockObjectNames;
import org.apache.derby.iapi.services.locks.LockReportSupport;
import org.apache.derby.iapi.services.locks.LockTransactionInfo;
import org.apache.derby.iapi.sql.conn.LanguageConnectionContext;
import org.apache.derby.iapi.store.access.TransactionInfo;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Engine-side bridge that names lock objects for the lock manager's reports.
 */
public final class EngineLockReportSupport implements LockReportSupport {
    @Override
    public long findConglomid(Object languageConnectionContext, long containerId)
            throws StandardException {
        return lcc(languageConnectionContext).getTransactionExecute()
                .findConglomid(containerId);
    }

    @Override
    public long findContainerid(Object languageConnectionContext, long conglomId)
            throws StandardException {
        return lcc(languageConnectionContext).getTransactionExecute()
                .findContainerid(conglomId);
    }

    @Override
    public LockObjectNames lockObjectNames(
            Object languageConnectionContext, boolean includeIndexNames)
            throws StandardException {
        return new TableNameInfo(lcc(languageConnectionContext), includeIndexNames);
    }

    @Override
    public List<LockTransactionInfo> transactionInfo(Object languageConnectionContext)
            throws StandardException {
        TransactionInfo[] transactions = lcc(languageConnectionContext)
                .getTransactionExecute().getAccessManager().getTransactionInfo();
        List<LockTransactionInfo> result = new ArrayList<>(transactions.length);
        for (TransactionInfo transaction : transactions) {
            // RESOLVE (track 2771): entries can be null.
            if (transaction != null) {
                result.add(new LockTransactionInfo(
                        transaction.getTransactionIdString(),
                        transaction.getUsernameString(),
                        transaction.getStatementTextString()));
            }
        }
        return result;
    }

    private static LanguageConnectionContext lcc(Object languageConnectionContext) {
        return (LanguageConnectionContext) languageConnectionContext;
    }
}
