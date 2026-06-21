/*

   Derby - Class org.apache.derby.iapi.store.types.DelosPredicate

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

/** Predicate representation passed to filterable table access implementations. */
public record DelosPredicate(String columnName,
                             DelosPredicateOperator operator,
                             List<StoreDataValue> operands) {
    public DelosPredicate {
        if (columnName == null || columnName.isBlank()) {
            throw new IllegalArgumentException("predicate column name must not be blank");
        }
        if (operator == null) {
            throw new IllegalArgumentException("predicate operator must not be null");
        }
        operands = List.copyOf(operands);
    }

    public static DelosPredicate equalsTo(String columnName, StoreDataValue value) {
        return new DelosPredicate(columnName, DelosPredicateOperator.EQUAL, List.of(value));
    }

    public static DelosPredicate range(String columnName,
                                       DelosPredicateOperator operator,
                                       StoreDataValue boundaryValue) {
        return new DelosPredicate(columnName, operator, List.of(boundaryValue));
    }
}
