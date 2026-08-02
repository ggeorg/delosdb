/*

   Derby - Class org.apache.derbyTesting.junit.DelosDbTestBaselines

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

package org.apache.derbyTesting.junit;

/**
 * DelosDB-specific catalog baseline adjustments for inherited Derby tests.
 *
 * <p>Derby tests sometimes assert exact row counts in system catalogs. DelosDB
 * intentionally adds a small number of built-in SYSCS_UTIL routines for
 * storage-cost controls. Those routines receive PUBLIC execute permissions and
 * therefore add rows to SYS.SYSROUTINEPERMS. Keep that product delta here so
 * new DelosDB system routines do not require scattered test magic numbers.</p>
 */
public final class DelosDbTestBaselines
{
    private DelosDbTestBaselines()
    {
    }

    /**
     * Extra PUBLIC routine-permission rows introduced by DelosDB system
     * routines beyond the inherited Derby baseline.
     *
     * <p>Current routines:</p>
     * <ul>
     *   <li>SYSCS_UTIL.SYSCS_SET_DELOSDB_UNCACHED_ROW_FETCH_COST(double)</li>
     *   <li>SYSCS_UTIL.SYSCS_CLEAR_DELOSDB_UNCACHED_ROW_FETCH_COST()</li>
     * </ul>
     */
    public static final int EXTRA_SYSTEM_ROUTINE_PERMISSION_ROWS = 2;

    public static int withExtraSystemRoutinePermissions(int derbyBaseline)
    {
        return derbyBaseline + EXTRA_SYSTEM_ROUTINE_PERMISSION_ROWS;
    }

    public static String[][] publicSystemRoutinePermissionRows(String grantor)
    {
        return publicSystemRoutinePermissionRows(grantor, new String[0][]);
    }

    public static String[][] publicSystemRoutinePermissionRows(
            String grantor,
            String[][] additionalRows)
    {
        int publicRows = withExtraSystemRoutinePermissions(
            DERBY_PUBLIC_SYSTEM_ROUTINE_PERMISSION_ROWS);
        String[][] rows = new String[publicRows + additionalRows.length][3];

        for (int i = 0; i < publicRows; i++) {
            rows[i] = new String[] {"PUBLIC", grantor, "N"};
        }

        for (int i = 0; i < additionalRows.length; i++) {
            rows[publicRows + i] = additionalRows[i];
        }

        return rows;
    }

    private static final int DERBY_PUBLIC_SYSTEM_ROUTINE_PERMISSION_ROWS = 11;
}
