/*

   Derby - Class org.apache.derby.impl.security.EngineStoreSecuritySupport

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

package org.apache.derby.impl.security;

import org.apache.derby.iapi.security.Securable;
import org.apache.derby.iapi.security.SecurityUtil;
import org.apache.derby.iapi.services.security.StoreSecurable;
import org.apache.derby.iapi.services.security.StoreSecuritySupport;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Engine-side bridge from the kernel-owned store security facade to Derby's
 * SQL authorization implementation.
 */
public final class EngineStoreSecuritySupport implements StoreSecuritySupport
{
    @Override
    public void authorize(StoreSecurable operation) throws StandardException
    {
        SecurityUtil.authorize(toEngineSecurable(operation));
    }

    @Override
    public void checkDerbyInternalsPrivilege()
    {
        SecurityUtil.checkDerbyInternalsPrivilege();
    }

    private static Securable toEngineSecurable(StoreSecurable operation)
    {
        switch (operation)
        {
            case FREEZE_DATABASE:
                return Securable.FREEZE_DATABASE;
            case UNFREEZE_DATABASE:
                return Securable.UNFREEZE_DATABASE;
            case CHECKPOINT_DATABASE:
                return Securable.CHECKPOINT_DATABASE;
            case BACKUP_DATABASE:
                return Securable.BACKUP_DATABASE;
            case BACKUP_DATABASE_NOWAIT:
                return Securable.BACKUP_DATABASE_NOWAIT;
            case BACKUP_DATABASE_AND_ENABLE_LOG_ARCHIVE_MODE:
                return Securable.BACKUP_DATABASE_AND_ENABLE_LOG_ARCHIVE_MODE;
            case BACKUP_DATABASE_AND_ENABLE_LOG_ARCHIVE_MODE_NOWAIT:
                return Securable.BACKUP_DATABASE_AND_ENABLE_LOG_ARCHIVE_MODE_NOWAIT;
            case DISABLE_LOG_ARCHIVE_MODE:
                return Securable.DISABLE_LOG_ARCHIVE_MODE;
            default:
                throw new IllegalArgumentException("Unsupported store securable: " + operation);
        }
    }
}
