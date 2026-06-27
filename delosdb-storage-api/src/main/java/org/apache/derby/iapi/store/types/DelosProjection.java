/*

   Derby - Class org.apache.derby.iapi.store.types.DelosProjection

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

/** Optional projection passed with a filterable scan. */
public record DelosProjection(boolean allColumns, List<String> columnNames) {
    public DelosProjection {
        columnNames = List.copyOf(columnNames);
        if (allColumns && !columnNames.isEmpty()) {
            throw new IllegalArgumentException("all-column projection must not name columns");
        }
        if (!allColumns && columnNames.isEmpty()) {
            throw new IllegalArgumentException("projected column list must not be empty");
        }
    }

    public static DelosProjection all() {
        return new DelosProjection(true, List.of());
    }

    public static DelosProjection columns(List<String> columnNames) {
        return new DelosProjection(false, columnNames);
    }
}
