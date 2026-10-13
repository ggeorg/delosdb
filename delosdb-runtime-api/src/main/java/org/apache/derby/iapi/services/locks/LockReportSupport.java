/*

   Derby - Class org.apache.derby.iapi.services.locks.LockReportSupport

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

package org.apache.derby.iapi.services.locks;

import java.util.List;

import org.apache.derby.shared.common.error.StandardException;

/**
 * Engine-provided bridge used by the lock manager to name lock objects in
 * timeout and deadlock reports without depending on the SQL layer. Each
 * method takes the current language connection context.
 */
public interface LockReportSupport {
    long findConglomid(Object languageConnectionContext, long containerId)
            throws StandardException;

    long findContainerid(Object languageConnectionContext, long conglomId)
            throws StandardException;

    LockObjectNames lockObjectNames(
            Object languageConnectionContext, boolean includeIndexNames)
            throws StandardException;

    /** @return the active transactions, in the access manager's order */
    List<LockTransactionInfo> transactionInfo(Object languageConnectionContext)
            throws StandardException;
}
