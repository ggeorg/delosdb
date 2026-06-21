/*

   Derby - Class org.apache.derby.iapi.store.types.DelosRow

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
import java.util.Objects;
import java.util.Optional;

/** A row crossing the Delos table-access boundary. */
public record DelosRow(Optional<DelosRowIdentity> rowIdentity, List<StoreDataValue> values) {
    public DelosRow {
        rowIdentity = Objects.requireNonNull(rowIdentity, "rowIdentity");
        values = List.copyOf(values);
    }

    public static DelosRow withoutIdentity(List<StoreDataValue> values) {
        return new DelosRow(Optional.empty(), values);
    }

    public static DelosRow withIdentity(DelosRowIdentity rowIdentity, List<StoreDataValue> values) {
        return new DelosRow(Optional.of(rowIdentity), values);
    }
}
