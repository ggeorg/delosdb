/*

   Derby - Class org.apache.derby.iapi.services.property.DatabaseVersionIds

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

package org.apache.derby.iapi.services.property;

/**
 * Database format/version ids that must be visible outside the SQL catalog.
 *
 * <p>The SQL data dictionary remains the owner of catalog semantics, but some
 * legacy store code needs version ids while running backup/boot logic. Keeping
 * the raw ids here removes a store-to-catalog compile-time dependency without
 * changing the values or disk-format assumptions.</p>
 */
public final class DatabaseVersionIds {
    /** Derby 10.9 System Catalog version. */
    public static final int DERBY_10_9 = 210;

    private DatabaseVersionIds() {
    }
}
