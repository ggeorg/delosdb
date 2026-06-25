/*

   Derby - Class org.apache.derby.iapi.services.security.StoreSecurityUtil

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

package org.apache.derby.iapi.services.security;

import org.apache.derby.shared.common.error.StandardException;

/**
 * Store-facing security facade which keeps the legacy store independent of
 * SQL-layer security and dictionary classes.
 */
public final class StoreSecurityUtil
{
    private StoreSecurityUtil()
    {
    }

    public static void authorize(StoreSecurable operation) throws StandardException
    {
        StoreSecuritySupportRegistry.support().authorize(operation);
    }

    public static void checkDerbyInternalsPrivilege()
    {
        StoreSecuritySupportRegistry.support().checkDerbyInternalsPrivilege();
    }
}
