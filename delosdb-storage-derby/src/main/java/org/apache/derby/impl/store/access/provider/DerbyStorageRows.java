/*

   Derby - Class org.apache.derby.impl.store.access.provider.DerbyStorageRows

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
package org.apache.derby.impl.store.access.provider;

import java.util.ArrayList;
import java.util.List;

import org.apache.derby.iapi.store.types.DelosProjection;
import org.apache.derby.iapi.store.types.DelosRow;
import org.apache.derby.iapi.store.types.DelosTableShape;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreValueCopySupport;
import org.apache.derby.shared.common.error.StandardException;

final class DerbyStorageRows {
    private DerbyStorageRows() {
    }

    static StoreDataValue[] rowArray(DelosRow row) {
        List<StoreDataValue> values = row.values();
        return values.toArray(StoreDataValue[]::new);
    }

    static StoreDataValue[] fetchTemplate(StoreDataValue[] rowTemplate) {
        if (rowTemplate == null) {
            return new StoreDataValue[0];
        }
        StoreDataValue[] copy = new StoreDataValue[rowTemplate.length];
        for (int i = 0; i < rowTemplate.length; i++) {
            copy[i] = StoreValueCopySupport.cloneHolderOrSelf(rowTemplate[i]);
        }
        return copy;
    }

    static List<StoreDataValue> projectedValues(
            DelosTableShape rowShape,
            StoreDataValue[] row,
            DelosProjection projection) throws StandardException {
        List<Integer> indexes = projectionIndexes(rowShape, projection);
        List<StoreDataValue> values = new ArrayList<>(indexes.size());
        for (int index : indexes) {
            values.add(StoreValueCopySupport.cloneValueOrSelf(row[index]));
        }
        return List.copyOf(values);
    }

    private static List<Integer> projectionIndexes(DelosTableShape rowShape, DelosProjection projection) {
        if (projection.allColumns()) {
            List<Integer> indexes = new ArrayList<>(rowShape.columns().size());
            for (int i = 0; i < rowShape.columns().size(); i++) {
                indexes.add(i);
            }
            return List.copyOf(indexes);
        }
        List<Integer> indexes = new ArrayList<>(projection.columnNames().size());
        for (String columnName : projection.columnNames()) {
            int index = columnIndexOrNegative(rowShape, columnName);
            if (index < 0) {
                throw new IllegalArgumentException("Unknown Derby storage column: " + columnName);
            }
            indexes.add(index);
        }
        return List.copyOf(indexes);
    }

    private static int columnIndexOrNegative(DelosTableShape rowShape, String columnName) {
        String normalized = normalize(columnName);
        for (int i = 0; i < rowShape.columns().size(); i++) {
            if (normalize(rowShape.columns().get(i).name()).equals(normalized)) {
                return i;
            }
        }
        return -1;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

}
