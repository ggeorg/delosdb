/*

   Derby - Class org.apache.derby.iapi.store.types.DelosTableIdentity

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
package org.apache.derby.iapi.store.types;

/** Logical catalog identity for a table. */
public record DelosTableIdentity(String schemaName, String tableName) {
    public DelosTableIdentity {
        if (schemaName == null || schemaName.isBlank()) {
            throw new IllegalArgumentException("schemaName must not be blank");
        }
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName must not be blank");
        }
    }

    public static DelosTableIdentity of(String schemaName, String tableName) {
        return new DelosTableIdentity(schemaName, tableName);
    }

    public String qualifiedName() {
        return schemaName + "." + tableName;
    }
}
