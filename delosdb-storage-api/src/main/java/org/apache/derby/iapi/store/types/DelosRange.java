/*

   Derby - Class org.apache.derby.iapi.store.types.DelosRange

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

import java.util.Optional;

/** Neutral range descriptor for index or table access paths. */
public record DelosRange(String columnName,
                         Optional<StoreDataValue> lowerBound,
                         boolean lowerInclusive,
                         Optional<StoreDataValue> upperBound,
                         boolean upperInclusive) {
    public DelosRange {
        if (columnName == null || columnName.isBlank()) {
            throw new IllegalArgumentException("range column name must not be blank");
        }
        lowerBound = lowerBound == null ? Optional.empty() : lowerBound;
        upperBound = upperBound == null ? Optional.empty() : upperBound;
    }

    public static DelosRange all(String columnName) {
        return new DelosRange(columnName, Optional.empty(), true, Optional.empty(), true);
    }

    public static DelosRange closed(String columnName,
                                    StoreDataValue lowerBound,
                                    StoreDataValue upperBound) {
        return new DelosRange(columnName,
                Optional.of(lowerBound),
                true,
                Optional.of(upperBound),
                true);
    }
}
