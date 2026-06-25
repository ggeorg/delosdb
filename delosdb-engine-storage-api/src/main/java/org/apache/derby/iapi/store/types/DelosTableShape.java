/*

   Derby - Class org.apache.derby.iapi.store.types.DelosTableShape

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

import java.util.List;

/** Logical row shape for a table access object. */
public record DelosTableShape(List<Column> columns) {
    public DelosTableShape {
        columns = List.copyOf(columns);
    }

    public static DelosTableShape of(List<Column> columns) {
        return new DelosTableShape(columns);
    }

    public record Column(String name, String typeName, boolean nullable) {
        public Column {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("column name must not be blank");
            }
            if (typeName == null || typeName.isBlank()) {
                throw new IllegalArgumentException("column type name must not be blank");
            }
        }
    }
}
